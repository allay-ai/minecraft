package chat.allay.fabric.gametest

import chat.allay.core.tasks.Agents
import chat.allay.core.tasks.Kind
import chat.allay.core.tasks.Task
import chat.allay.core.tools.Button
import chat.allay.core.tools.ToolError
import chat.allay.core.tools.ToolOutput
import chat.allay.fabric.FabricPlayer
import chat.allay.fabric.automation.Chests
import chat.allay.fabric.automation.Undo
import chat.allay.fabric.gametest.Players.survivor
import chat.allay.fabric.mixin.FurnaceAccess
import chat.allay.fabric.tools.ToolContext

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.ExperienceOrb
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.alchemy.PotionContents
import net.minecraft.world.item.alchemy.Potions
import net.minecraft.world.item.component.ItemContainerContents
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.LayeredCauldronBlock
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.block.state.properties.ChestType
import net.minecraft.world.level.storage.loot.BuiltInLootTables
import net.minecraft.world.phys.AABB

/**
 * Automation on a real server with real players and real blocks: chests sorted, moved, and put back, furnaces and
 * brewing stands the game drives, crafting at a real table, picking with a left click, and the rules that keep it to
 * the player's own things. Each test stands far from the others, so no scan finds another test's blocks.
 */
class AutomationGameTests {
  private fun equal(helper: GameTestHelper, actual: Any?, expected: Any?, what: String) =
    helper.assertTrue(actual == expected, "$what: expected $expected, got $actual")

  /**
   * An automation agent of [kind] for [player], as `start_task` makes one, but with no wake: a wake would ask the
   * backend, so the agent counts as busy and what it hears waits in its inbox for the test to read.
   */
  private fun agent(player: ServerPlayer, kind: Kind): Task {
    val automation = Players.features.automation
    val task = automation.book.start(player.uuid, kind, "test", "a game test", 1, null)
    task.running = true
    val field = Agents::class.java.getDeclaredField("agents").apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    (field.get(automation.agents) as MutableMap<String, Agents.Agent>)["${task.owner}:${task.id}"] = Agents.Agent(task, FabricPlayer(player).position)
    return task
  }

  private fun context(player: ServerPlayer, task: Task?) = ToolContext(FabricPlayer(player), Players.features, task = task)

  private fun run(player: ServerPlayer, task: Task?, name: String, args: JsonObject): ToolOutput = Players.run(context(player, task), name, args)

  /** The [ToolError] text [block] ends with, or null when it ran through. */
  private fun refused(block: () -> Unit): String? = try {
    block()
    null
  } catch (error: ToolError) {
    error.message.orEmpty()
  }

  private fun spot(pos: BlockPos) = buildJsonObject {
    put("x", pos.x)
    put("y", pos.y)
    put("z", pos.z)
  }

  private fun spots(vararg list: BlockPos) = kotlinx.serialization.json.buildJsonArray { list.forEach { add(spot(it)) } }

  /** A chest at [at] in the test's structure holding [items] from its first slot on; its spot in the world. */
  private fun chest(helper: GameTestHelper, at: BlockPos, vararg items: ItemStack): BlockPos {
    val pos = helper.absolutePos(at)
    helper.level.setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState())
    val chest = helper.level.getBlockEntity(pos) as ChestBlockEntity
    items.forEachIndexed { index, item -> chest.setItem(index, item) }
    return pos
  }

  private fun contents(helper: GameTestHelper, pos: BlockPos): List<ItemStack> {
    val chest = helper.level.getBlockEntity(pos) as ChestBlockEntity
    return (0 until chest.containerSize).map { chest.getItem(it).copy() }
  }

  private fun same(a: List<ItemStack>, b: List<ItemStack>) = a.size == b.size && a.indices.all { ItemStack.matches(a[it], b[it]) }

  private fun count(items: List<ItemStack>, item: Item) = items.filter { it.`is`(item) }.sumOf { it.count }

  private fun carried(player: ServerPlayer, item: Item) = count((0 until 41).map { player.inventory.getItem(it) }, item)

  private fun inventory(player: ServerPlayer) = (0 until 41).map { player.inventory.getItem(it).copy() }

  /** Ends the agent and lets the player go, as the end of every test does. */
  private fun done(player: ServerPlayer, task: Task?) {
    task?.let { Players.features.automation.end(it, null) }
    player.discard()
  }

  @GameTest(padding = 48)
  fun groupingChestsMovesEverythingAndUndoPutsItBack(helper: GameTestHelper) {
    val player = survivor(helper)
    val task = agent(player, Kind.CHESTS)
    val first = chest(helper, BlockPos(3, 2, 1), ItemStack(Items.COBBLESTONE, 40), ItemStack(Items.IRON_INGOT, 5), ItemStack(Items.BREAD, 3))
    val second = chest(helper, BlockPos(3, 2, 4), ItemStack(Items.IRON_INGOT, 7), ItemStack(Items.COBBLESTONE, 30), ItemStack(Items.OAK_LOG, 2))
    val before = contents(helper, first) to contents(helper, second)
    val planned = run(player, task, "chests", buildJsonObject {
      put("action", "plan")
      put("mode", "group")
      put("chests", spots(first, second))
    }).result.jsonObject
    equal(helper, planned["chests"]!!.jsonArray.size, 2, "chests in the plan")
    // Nothing moves before apply.
    helper.assertTrue(same(contents(helper, first), before.first) && same(contents(helper, second), before.second), "the plan alone moves nothing")
    val applied = run(player, task, "chests", buildJsonObject { put("action", "apply") }).result.jsonObject
    equal(helper, applied["applied"]!!.jsonPrimitive.boolean, true, "applied")
    val after = contents(helper, first) + contents(helper, second)
    val was = before.first + before.second
    listOf(Items.COBBLESTONE, Items.IRON_INGOT, Items.BREAD, Items.OAK_LOG).forEach { item -> equal(helper, count(after, item), count(was, item), "every ${item} is still there") }
    helper.assertTrue(!same(contents(helper, first), before.first), "the chests were grouped")
    // Put it back: both chests as they were.
    val undone = run(player, task, "chests", buildJsonObject { put("action", "undo") }).result.jsonObject
    equal(helper, undone["restored"]!!.jsonPrimitive.int, 2, "chests put back")
    helper.assertTrue(same(contents(helper, first), before.first) && same(contents(helper, second), before.second), "the chests hold what they held")
    equal(helper, refused { run(player, task, "chests", buildJsonObject { put("action", "undo") }) }?.startsWith("nothing to put back"), true, "a second undo")
    done(player, task)
    helper.succeed()
  }

  @GameTest(padding = 48)
  fun nothingMovesWhenAChestChangedAfterThePlan(helper: GameTestHelper) {
    val player = survivor(helper)
    val task = agent(player, Kind.CHESTS)
    val first = chest(helper, BlockPos(3, 2, 1), ItemStack(Items.COBBLESTONE, 10), ItemStack(Items.DIRT, 10), ItemStack(Items.COBBLESTONE, 10))
    val second = chest(helper, BlockPos(3, 2, 4), ItemStack(Items.DIRT, 5))
    run(player, task, "chests", buildJsonObject {
      put("action", "plan")
      put("mode", "sort")
      put("chests", spots(first, second))
    })
    // Someone puts a stick in meanwhile.
    (helper.level.getBlockEntity(second) as ChestBlockEntity).setItem(5, ItemStack(Items.STICK))
    val untouched = contents(helper, first)
    val reason = refused { run(player, task, "chests", buildJsonObject { put("action", "apply") }) }
    helper.assertTrue(reason?.contains("changed since the plan") == true, "a changed chest stops the apply: $reason")
    helper.assertTrue(same(contents(helper, first), untouched), "the other chest stayed as it was")
    done(player, task)
    helper.succeed()
  }

  @GameTest(padding = 48)
  fun aDoubleChestReadsAsOne(helper: GameTestHelper) {
    val player = survivor(helper)
    val task = agent(player, Kind.CHESTS)
    val left = helper.absolutePos(BlockPos(3, 2, 2))
    val right = left.east()
    val state = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.NORTH)
    helper.level.setBlockAndUpdate(left, state.setValue(ChestBlock.TYPE, ChestType.LEFT))
    helper.level.setBlockAndUpdate(right, state.setValue(ChestBlock.TYPE, ChestType.RIGHT))
    (helper.level.getBlockEntity(left) as ChestBlockEntity).setItem(0, ItemStack(Items.DIAMOND, 3))
    val rows = run(player, task, "chests", buildJsonObject {
      put("action", "read")
      put("chests", spots(left, right))
    }).result.jsonObject["chests"]!!.jsonArray
    equal(helper, rows.size, 1, "both halves are one chest")
    val row = rows[0].jsonObject
    equal(helper, row["double"]?.jsonPrimitive?.boolean, true, "double")
    equal(helper, row["slots"]!!.jsonPrimitive.int, 54, "slots")
    equal(helper, row["top"]!!.jsonObject["diamond"]?.jsonPrimitive?.int, 3, "what it holds")
    done(player, task)
    helper.succeed()
  }

  @GameTest(padding = 48)
  fun hoppersClaimsAndOpenWindowsKeepChestsOut(helper: GameTestHelper) {
    val player = survivor(helper)
    val task = agent(player, Kind.CHESTS)
    // A sorter's input: a hopper under it. Nothing is rearranged or taken there, but things may go in.
    val sorter = chest(helper, BlockPos(3, 3, 1), ItemStack(Items.DIRT, 3), ItemStack(Items.STONE, 3))
    helper.level.setBlockAndUpdate(sorter.below(), Blocks.HOPPER.defaultBlockState())
    val read = run(player, task, "chests", buildJsonObject {
      put("action", "read")
      put("chests", spots(sorter))
    }).result.jsonObject["chests"]!!.jsonArray[0].jsonObject
    helper.assertTrue(read["only_adding"] != null, "a hopper-linked chest only takes things in: $read")
    val sorting = refused { run(player, task, "chests", buildJsonObject { put("action", "plan"); put("mode", "sort"); put("chests", spots(sorter)) }) }
    helper.assertTrue(sorting?.contains("hopper") == true, "sorting a sorter's input is refused: $sorting")
    player.inventory.setItem(9, ItemStack(Items.DIRT, 10))
    run(player, task, "chests", buildJsonObject { put("action", "plan"); put("mode", "stash"); put("chests", spots(sorter)) })
    run(player, task, "chests", buildJsonObject { put("action", "apply") })
    equal(helper, count(contents(helper, sorter), Items.DIRT), 13, "the stash went into the sorter's input")
    equal(helper, carried(player, Items.DIRT), 0, "and out of the inventory")

    // An unopened structure chest is nobody's to open yet.
    val loot = chest(helper, BlockPos(5, 2, 1))
    (helper.level.getBlockEntity(loot) as ChestBlockEntity).setLootTable(BuiltInLootTables.SIMPLE_DUNGEON)
    val closed = run(player, task, "chests", buildJsonObject { put("action", "read"); put("chests", spots(loot)) }).result.jsonObject["chests"]!!.jsonArray[0].jsonObject
    helper.assertTrue(closed["unsuitable"]?.jsonPrimitive?.content?.contains("can't open it") == true, "a structure chest stays closed: $closed")
    helper.assertTrue(closed["top"] == null, "and what's in it stays hidden: $closed")
    val taking = refused { run(player, task, "chests", buildJsonObject { put("action", "plan"); put("mode", "take"); put("chests", spots(loot)); putJsonArray("items") { add(kotlinx.serialization.json.JsonPrimitive("diamond")) } }) }
    helper.assertTrue(taking?.contains("can't open it") == true, "nothing is taken from it: $taking")

    // A chest open in someone's window stays out.
    val open = chest(helper, BlockPos(5, 2, 4), ItemStack(Items.STONE, 1), ItemStack(Items.DIRT, 1))
    val viewer = survivor(helper, net.minecraft.world.phys.Vec3(5.5, 2.0, 5.5))
    viewer.openMenu(helper.level.getBlockEntity(open) as ChestBlockEntity)
    val busy = refused { run(player, task, "chests", buildJsonObject { put("action", "plan"); put("mode", "sort"); put("chests", spots(open)) }) }
    helper.assertTrue(busy?.contains("someone has it open") == true, "an open chest is left alone: $busy")
    viewer.closeContainer()
    viewer.discard()
    done(player, task)
    helper.succeed()
  }

  @GameTest(padding = 48)
  fun undoKeepsTheLastFiveChanges(helper: GameTestHelper) {
    val player = survivor(helper)
    val undo = Players.features.automation.undo
    repeat(6) { index ->
      val before = Chests.pack(player)
      player.inventory.setItem(9, ItemStack(Items.STONE, index + 1))
      undo.keep(player.uuid, Undo.Change(emptyList(), emptyMap(), emptyMap(), before to Chests.pack(player)))
    }
    repeat(Undo.DEPTH) { equal(helper, undo.restore(player, 5).inventory, true, "change ${Undo.DEPTH - it} put back") }
    equal(helper, player.inventory.getItem(9).count, 1, "the oldest kept change is where it ends")
    equal(helper, refused { undo.restore(player, 5) }?.startsWith("nothing to put back"), true, "a sixth undo")
    done(player, null)
    helper.succeed()
  }

  @GameTest(padding = 48)
  fun sortingTheInventoryMergesAndUndoes(helper: GameTestHelper) {
    val player = survivor(helper)
    player.inventory.setItem(0, ItemStack(Items.IRON_SWORD))
    player.inventory.setItem(30, ItemStack(Items.COBBLESTONE, 20))
    player.inventory.setItem(12, ItemStack(Items.OAK_LOG, 4))
    player.inventory.setItem(20, ItemStack(Items.COBBLESTONE, 10))
    val before = inventory(player)
    val context = context(player, null)
    val sorted = Players.run(context, "sort_inventory", buildJsonObject {}).result.jsonObject
    equal(helper, sorted["merged_stacks"]!!.jsonPrimitive.int, 1, "the cobblestone merged")
    equal(helper, sorted["hotbar"]!!.jsonArray[0].jsonPrimitive.content, "iron_sword", "the hotbar stays")
    equal(helper, carried(player, Items.COBBLESTONE), 30, "nothing lost")
    helper.assertTrue(context.buttons.any { it is Button.Undo }, "an undo button comes with it")
    val undone = Players.run(player, "sort_inventory", buildJsonObject { put("undo", true) }).result.jsonObject
    equal(helper, undone["restored"]!!.jsonPrimitive.boolean, true, "restored")
    helper.assertTrue(same(inventory(player), before), "the inventory is as it was")
    done(player, null)
    helper.succeed()
  }

  @GameTest(padding = 48, maxTicks = 600)
  fun smeltingPaysTheExperienceOnce(helper: GameTestHelper) {
    val player = survivor(helper)
    val task = agent(player, Kind.FURNACES)
    val furnace = helper.absolutePos(BlockPos(3, 2, 1))
    helper.level.setBlockAndUpdate(furnace, Blocks.BLAST_FURNACE.defaultBlockState())
    player.inventory.setItem(9, ItemStack(Items.RAW_IRON, 2))
    player.inventory.setItem(10, ItemStack(Items.COAL, 1))
    val started = run(player, task, "smelt", buildJsonObject {
      put("action", "start")
      put("item", "raw_iron")
      put("furnaces", spots(furnace))
      put("to", "inventory")
    }).result.jsonObject
    equal(helper, started["smelting"]!!.jsonPrimitive.int, 2, "smelting")
    equal(helper, started["fuel"]!!.jsonPrimitive.content, "coal", "the fuel")
    equal(helper, carried(player, Items.RAW_IRON), 0, "the raw iron went in")
    helper.succeedWhen {
      val news = task.inbox.firstOrNull { "smelting done" in it }
      helper.assertTrue(news != null, "the job ends by itself: ${task.inbox}")
      equal(helper, carried(player, Items.IRON_INGOT), 2, "the ingots reach the inventory")
      val paid = Regex("with (\\d+) experience").find(news!!)!!.groupValues[1].toInt()
      val orbs = helper.level.getEntitiesOfClass(ExperienceOrb::class.java, AABB(player.blockPosition()).inflate(2.0)).sumOf { it.value }
      // The player may have picked some of it up already.
      equal(helper, orbs + player.totalExperience, paid, "the experience waits at the player's feet or is theirs")
      helper.assertTrue(paid in 1..2, "two raw iron give one or two points: $paid")
      val store = (helper.level.getBlockEntity(furnace) as AbstractFurnaceBlockEntity) as FurnaceAccess
      helper.assertTrue(store.`allay$recipesUsed`().isEmpty(), "the furnace's store is empty")
      // Collecting again pays nothing more.
      val again = run(player, task, "smelt", buildJsonObject {
        put("action", "collect")
        put("furnaces", spots(furnace))
        put("to", "inventory")
      }).result.jsonObject
      equal(helper, again["experience"]!!.jsonPrimitive.int, 0, "experience on a second collect")
      done(player, task)
    }
  }

  @GameTest(padding = 48, maxTicks = 700)
  fun brewingRunsTheStandToTheEnd(helper: GameTestHelper) {
    val player = survivor(helper)
    val task = agent(player, Kind.BREWING)
    val stand = helper.absolutePos(BlockPos(3, 2, 1))
    helper.level.setBlockAndUpdate(stand, Blocks.BREWING_STAND.defaultBlockState())
    (0..2).forEach { player.inventory.setItem(9 + it, PotionContents.createItemStack(Items.POTION, Potions.WATER)) }
    player.inventory.setItem(12, ItemStack(Items.NETHER_WART))
    player.inventory.setItem(13, ItemStack(Items.BLAZE_POWDER))
    val started = run(player, task, "brew", buildJsonObject {
      put("action", "start")
      put("potion", "awkward")
      put("stands", spots(stand))
      put("to", "inventory")
    }).result.jsonObject
    equal(helper, started["started"]!!.jsonPrimitive.boolean, true, "started: $started")
    equal(helper, carried(player, Items.NETHER_WART), 0, "the wart went in")
    helper.succeedWhen {
      helper.assertTrue(task.inbox.any { "brewing done" in it }, "the job ends by itself: ${task.inbox}")
      val awkward = (0 until 36).map { player.inventory.getItem(it) }.filter { it.get(DataComponents.POTION_CONTENTS)?.`is`(Potions.AWKWARD) == true }.sumOf { it.count }
      equal(helper, awkward, 3, "awkward potions in the inventory")
      done(player, task)
    }
  }

  @GameTest(padding = 48)
  fun waterBottlesComeFromGlassBottlesAtACauldron(helper: GameTestHelper) {
    val player = survivor(helper)
    val task = agent(player, Kind.BREWING)
    val stand = helper.absolutePos(BlockPos(3, 2, 1))
    helper.level.setBlockAndUpdate(stand, Blocks.BREWING_STAND.defaultBlockState())
    val cauldron = helper.absolutePos(BlockPos(4, 2, 3))
    helper.level.setBlockAndUpdate(cauldron, Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3))
    player.inventory.setItem(9, ItemStack(Items.GLASS_BOTTLE, 3))
    player.inventory.setItem(12, ItemStack(Items.NETHER_WART))
    player.inventory.setItem(13, ItemStack(Items.BLAZE_POWDER))
    val started = run(player, task, "brew", buildJsonObject {
      put("action", "start")
      put("potion", "awkward")
      put("stands", spots(stand))
      put("to", "inventory")
    }).result.jsonObject
    equal(helper, started["started"]!!.jsonPrimitive.boolean, true, "started: $started")
    equal(helper, started["filled_water_bottles"]!!.jsonPrimitive.int, 3, "filled at the cauldron")
    equal(helper, carried(player, Items.GLASS_BOTTLE), 0, "the glass bottles were used")
    helper.assertTrue(helper.level.getBlockState(cauldron).`is`(Blocks.CAULDRON), "the cauldron gave a level a bottle and is empty")
    done(player, task)
    helper.succeed()
  }

  @GameTest(padding = 48)
  fun craftingNeedsARealTableInSight(helper: GameTestHelper) {
    val player = survivor(helper)
    val task = agent(player, Kind.CRAFTING)
    player.inventory.setItem(9, ItemStack(Items.OAK_PLANKS, 8))
    val chest = buildJsonObject { put("item", "chest") }
    val none = refused { run(player, task, "craft", chest) }
    helper.assertTrue(none?.contains("no crafting table") == true, "a 3×3 recipe needs a table: $none")
    // A table behind a wall isn't in sight.
    val hidden = helper.absolutePos(BlockPos(1, 2, 5))
    helper.level.setBlockAndUpdate(hidden, Blocks.CRAFTING_TABLE.defaultBlockState())
    for (x in 0..2) for (y in 1..4) helper.level.setBlockAndUpdate(helper.absolutePos(BlockPos(x, y, 3)), Blocks.STONE.defaultBlockState())
    val walled = refused { run(player, task, "craft", chest) }
    helper.assertTrue(walled?.contains("no crafting table") == true, "a walled-off table doesn't count: $walled")
    equal(helper, carried(player, Items.OAK_PLANKS), 8, "nothing was taken")
    val table = helper.absolutePos(BlockPos(3, 2, 1))
    helper.level.setBlockAndUpdate(table, Blocks.CRAFTING_TABLE.defaultBlockState())
    val crafted = run(player, task, "craft", chest).result.jsonObject
    equal(helper, crafted["crafted"]!!.jsonPrimitive.boolean, true, "crafted: $crafted")
    equal(helper, crafted["table"]!!.jsonObject["z"]!!.jsonPrimitive.int, table.z, "the table it used")
    equal(helper, carried(player, Items.CHEST), 1, "the chest")
    equal(helper, carried(player, Items.OAK_PLANKS), 0, "the planks it took")
    done(player, task)
    helper.succeed()
  }

  @GameTest(padding = 48)
  fun craftingNeverTakesFromShulkerBoxes(helper: GameTestHelper) {
    val player = survivor(helper)
    val task = agent(player, Kind.CRAFTING)
    helper.level.setBlockAndUpdate(helper.absolutePos(BlockPos(3, 2, 1)), Blocks.CRAFTING_TABLE.defaultBlockState())
    val box = ItemStack(Items.SHULKER_BOX)
    box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(listOf(ItemStack(Items.OAK_PLANKS, 8))))
    player.inventory.setItem(9, box)
    val result = run(player, task, "craft", buildJsonObject { put("item", "chest") }).result.jsonObject
    equal(helper, result["crafted"]!!.jsonPrimitive.boolean, false, "crafted: $result")
    helper.assertTrue(result["missing"]!!.jsonObject.isNotEmpty(), "the planks count as missing: $result")
    val inside = player.inventory.getItem(9).get(DataComponents.CONTAINER)!!.nonEmptyItemCopyStream().toList()
    equal(helper, count(inside, Items.OAK_PLANKS), 8, "the box keeps its planks")
    done(player, task)
    helper.succeed()
  }

  @GameTest(padding = 48)
  fun aLeftClickPicksAChestInsteadOfBreakingIt(helper: GameTestHelper) {
    val player = survivor(helper)
    player.setGameMode(GameType.CREATIVE)
    val task = agent(player, Kind.CHESTS)
    val picked = chest(helper, BlockPos(3, 2, 1), ItemStack(Items.DIAMOND))
    val loot = chest(helper, BlockPos(1, 2, 3))
    (helper.level.getBlockEntity(loot) as ChestBlockEntity).setLootTable(BuiltInLootTables.SIMPLE_DUNGEON)
    val output = run(player, task, "pick_blocks", buildJsonObject { put("kind", "chests") }).result.jsonObject
    equal(helper, output["picking"]!!.jsonPrimitive.boolean, true, "picking")
    val picking = Players.features.automation.picking
    val click = { pos: BlockPos -> player.gameMode.handleBlockBreakAction(pos, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, Direction.UP, helper.level.maxY, 0) }
    click(picked)
    helper.assertTrue(helper.level.getBlockState(picked).`is`(Blocks.CHEST), "a creative click doesn't break the chest while picking")
    equal(helper, picking.picked(player, task).map { it.pos }, listOf(picked), "picked")
    click(picked)
    equal(helper, picking.picked(player, task).size, 0, "a second click unpicks")
    click(picked)
    // A chest the player can't open flashes red and stays out.
    click(loot)
    equal(helper, picking.picked(player, task).map { it.pos }, listOf(picked), "only the chest the player may open")
    picking.button(player, "done")
    helper.assertTrue(task.inbox.any { it.startsWith("[job] picking: done: 1 chests picked") }, "the agent hears what was picked: ${task.inbox}")
    done(player, task)
    helper.assertTrue(picking.picked(player, task).isEmpty(), "the picks go with the agent")
    helper.succeed()
  }
}
