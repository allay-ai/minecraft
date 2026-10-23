package chat.allay.fabric.gametest

import chat.allay.core.tasks.Agents
import chat.allay.core.tasks.Kind
import chat.allay.core.tasks.Task
import chat.allay.fabric.FabricPlayer
import chat.allay.fabric.automation.Blocks as AutomationBlocks
import chat.allay.fabric.automation.Sources
import chat.allay.fabric.gametest.Players.survivor
import chat.allay.fabric.mixin.RecipeAccess
import chat.allay.fabric.tools.Recipes
import chat.allay.fabric.tools.ToolContext
import chat.allay.fabric.tools.at

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.crafting.RecipeType
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.portal.TeleportTransition
import net.minecraft.world.phys.Vec3

/**
 * What the audit of the mod against the plugin found: players who respawn or change worlds, the order and the reading
 * of the game's data, and the edges of the tools. Tests whose players respawn run in an environment of their own,
 * since a respawned player stands at the world spawn, outside every test's structure.
 */
class FixesGameTests {
  private companion object {
    const val RESPAWNS = "allay-gametest:respawns"
  }

  private fun equal(helper: GameTestHelper, actual: Any?, expected: Any?, what: String) =
    helper.assertTrue(actual == expected, "$what: expected $expected, got $actual")

  private fun carried(player: ServerPlayer, item: Item) = (0 until 41).map { player.inventory.getItem(it) }.filter { it.`is`(item) }.sumOf { it.count }

  /** An automation agent with no wake, as in the automation tests. */
  private fun agent(player: ServerPlayer, kind: Kind): Task {
    val automation = Players.features.automation
    val task = automation.book.start(player.uuid, kind, "test", "a game test", 1, null)
    task.running = true
    val field = Agents::class.java.getDeclaredField("agents").apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    (field.get(automation.agents) as MutableMap<String, Agents.Agent>)["${task.owner}:${task.id}"] = Agents.Agent(task, FabricPlayer(player).position)
    return task
  }

  /** The player respawns as after a death with keepInventory: a new [ServerPlayer] with the same stacks. */
  private fun respawn(player: ServerPlayer): ServerPlayer = player.level().server.playerList.respawn(player, true, Entity.RemovalReason.KILLED)

  @GameTest(environment = RESPAWNS, maxTicks = 400)
  fun aFurnaceJobDeliversToThePlayerAfterARespawn(helper: GameTestHelper) {
    val player = survivor(helper)
    val task = agent(player, Kind.FURNACES)
    val furnace = helper.absolutePos(BlockPos(3, 2, 1))
    helper.level.setBlockAndUpdate(furnace, Blocks.BLAST_FURNACE.defaultBlockState())
    player.inventory.setItem(9, ItemStack(Items.RAW_IRON, 1))
    player.inventory.setItem(10, ItemStack(Items.COAL, 1))
    Players.run(ToolContext(FabricPlayer(player), Players.features, task = task), "smelt", buildJsonObject {
      put("action", "start")
      put("item", "raw_iron")
      putJsonArray("furnaces") { add(buildJsonObject { put("x", furnace.x); put("y", furnace.y); put("z", furnace.z) }) }
      put("to", "inventory")
    })
    val fresh = respawn(player)
    helper.succeedWhen {
      helper.assertTrue(task.inbox.any { "smelting done" in it }, "the job ends by itself: ${task.inbox}")
      equal(helper, carried(fresh, Items.IRON_INGOT), 1, "the ingot reaches the respawned player")
      Players.features.automation.end(task, null)
      fresh.discard()
    }
  }

  @GameTest(environment = RESPAWNS)
  fun sourcesTakeFromThePlayerAfterARespawn(helper: GameTestHelper) {
    val player = survivor(helper)
    player.inventory.setItem(9, ItemStack(Items.COBBLESTONE, 10))
    val sources = Sources.around(player, 4)
    val fresh = respawn(player)
    val taken = sources.list.first().take({ it.`is`(Items.COBBLESTONE) }, 4)
    equal(helper, taken.sumOf { it.count }, 4, "taken")
    equal(helper, carried(fresh, Items.COBBLESTONE), 6, "cobblestone left with the respawned player")
    fresh.discard()
    helper.succeed()
  }

  @GameTest(padding = 48)
  fun sightGoesThroughWhatBukkitCallsTransparent(helper: GameTestHelper) {
    val player = survivor(helper)
    val chest = helper.absolutePos(BlockPos(5, 3, 1))
    val around = Direction.entries.map { chest.relative(it) }
    helper.level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState())
    // A sea lantern renders solid but doesn't occlude on Bukkit; a shulker box is the other way round.
    around.forEach { helper.level.setBlockAndUpdate(it, Blocks.SEA_LANTERN.defaultBlockState()) }
    helper.assertTrue(AutomationBlocks.sees(player, helper.level.at(chest)), "a chest behind sea lanterns is in sight")
    around.forEach { helper.level.setBlockAndUpdate(it, Blocks.SHULKER_BOX.defaultBlockState()) }
    helper.assertTrue(!AutomationBlocks.sees(player, helper.level.at(chest)), "a chest behind shulker boxes is out of sight")
    player.discard()
    helper.succeed()
  }

  /** The ids of the entities the server told [player]'s client about since the last look. */
  private fun added(player: Players.Survivor) = player.packets().filterIsInstance<ClientboundAddEntityPacket>().map { it.id }.toSet()

  @GameTest(environment = RESPAWNS)
  fun marksComeBackAfterARespawnAndInTheirOwnWorld(helper: GameTestHelper) {
    val player = survivor(helper)
    val spot = helper.absolutePos(BlockPos(2, 2, 2))
    player.packets()
    Players.run(player, "show_marks", buildJsonObject {
      putJsonArray("marks") { add(buildJsonObject { put("x", spot.x); put("y", spot.y); put("z", spot.z); put("block", "torch"); put("label", "here") }) }
    })
    val ghosts = added(player)
    equal(helper, ghosts.size, 2, "the ghost and its label")
    val fresh = respawn(player)
    helper.assertTrue(added(player).containsAll(ghosts), "the respawned player's client hears of the marks again")
    // In another world they wait; back in theirs, they show again.
    val nether = helper.level.server.getLevel(Level.NETHER)!!
    fresh.teleport(TeleportTransition(nether, Vec3(0.5, 100.0, 0.5), Vec3.ZERO, 0f, 0f, TeleportTransition.DO_NOTHING))
    helper.assertTrue(added(player).intersect(ghosts).isEmpty(), "marks of the overworld in the nether")
    fresh.teleport(TeleportTransition(helper.level, Vec3.atCenterOf(spot), Vec3.ZERO, 0f, 0f, TeleportTransition.DO_NOTHING))
    helper.assertTrue(added(player).containsAll(ghosts), "the marks show again back home")
    fresh.discard()
    helper.succeed()
  }

  @GameTest
  fun recipesComeByTypeAsOnBukkit(helper: GameTestHelper) {
    val server = helper.level.server
    val types = ((server.recipeManager as RecipeAccess.Manager).`allay$recipes`() as RecipeAccess.Map).`allay$byType`().keySet().toList()
    val found = Recipes.forItem(server, Items.IRON_INGOT).map { it.value().type }
    helper.assertTrue(found.toSet().size >= 3, "iron ingots are crafted, smelted, and blasted: $found")
    // Grouped by type, the groups in the order of the server's types, as Bukkit's recipeIterator walks them.
    val groups = found.fold(listOf<RecipeType<*>>()) { list, type -> if (list.lastOrNull() == type) list else list + type }
    equal(helper, groups, groups.distinct().sortedBy(types::indexOf), "the order of the recipe types")
    helper.succeed()
  }

  @GameTest(padding = 48)
  fun theBiomeIsTheBlocksOwnCellAsOnBukkit(helper: GameTestHelper) {
    val server = helper.level.server
    val near = helper.absolutePos(BlockPos(4, 4, 4))
    val cell = BlockPos((near.x shr 2) shl 2, (near.y shr 2) shl 2, (near.z shr 2) shl 2)
    server.commands.performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "fillbiome ${cell.x} ${cell.y} ${cell.z} ${cell.x + 3} ${cell.y + 3} ${cell.z + 3} minecraft:cherry_grove")
    val noise = helper.level.getNoiseBiome(cell.x shr 2, cell.y shr 2, cell.z shr 2).unwrapKey().get().identifier().path
    equal(helper, noise, "cherry_grove", "the cell's biome after fillbiome at $cell")
    // The game blends biomes for a block near a cell's edge; Bukkit reads the cell itself.
    val blended = BlockPos.betweenClosed(cell, cell.offset(3, 3, 3)).map { it.immutable() }
      .firstOrNull { helper.level.getBiome(it).unwrapKey().get().identifier().path != "cherry_grove" }
    helper.assertTrue(blended != null, "a block of the cell the game blends with its neighbors")
    val player = survivor(helper)
    player.teleportTo(blended!!.x + 0.5, blended.y.toDouble(), blended.z + 0.5)
    equal(helper, Players.run(player, "get_player_status").result.jsonObject["biome"]?.jsonPrimitive?.content, "cherry_grove", "the biome")
    player.discard()
    helper.succeed()
  }
}
