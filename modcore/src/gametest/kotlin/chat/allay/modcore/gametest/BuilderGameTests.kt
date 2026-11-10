package chat.allay.modcore.gametest

import chat.allay.core.building.Blueprints
import chat.allay.core.building.Materials
import chat.allay.core.building.Schem
import chat.allay.core.building.Shell
import chat.allay.core.tasks.Agents
import chat.allay.core.tasks.Kind
import chat.allay.core.tasks.Task
import chat.allay.modcore.ModPlayer
import chat.allay.modcore.automation.Building
import chat.allay.modcore.gametest.Players.survivor
import chat.allay.modcore.tools.Previews
import chat.allay.modcore.tools.ToolContext

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.entity.ChestBlockEntity
import java.security.MessageDigest

/**
 * The builder on a real server: a survival player's preview of the starter hut goes up block by block from a chest
 * beside them, which ends up empty; short of one material it waits, and goes on once it's in the chest.
 */
class BuilderGameTests {
  /** Where the player stands: the hut, two blocks ahead and five wide, fits inside the test's 8×8 walls. */
  private val AT = net.minecraft.world.phys.Vec3(3.5, 2.0, 1.5)

  private val hut = BuilderGameTests::class.java.getResourceAsStream("/schematics/starter-hut.schem")!!.readBytes()
  private val blueprint = Schem.read(hut)

  /** What the hut takes, item by item, as the estimate counts it. */
  private val needs: Map<String, Int> = Materials.estimate(blueprint).items

  private fun stacks(items: Map<String, Int>): List<ItemStack> = items.flatMap { (id, count) ->
    val item = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(id))
    val max = item.defaultMaxStackSize
    (0 until (count + max - 1) / max).map { ItemStack(item, minOf(max, count - it * max)) }
  }

  /** A chest at [at] in the structure with [items]; its spot in the world. */
  private fun chest(helper: GameTestHelper, at: BlockPos, items: List<ItemStack>): BlockPos {
    val pos = helper.absolutePos(at)
    helper.level.setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState())
    val chest = helper.level.getBlockEntity(pos) as ChestBlockEntity
    items.forEachIndexed { index, item -> chest.setItem(index, item) }
    return pos
  }

  private fun chestItems(helper: GameTestHelper, pos: BlockPos): List<ItemStack> {
    val chest = helper.level.getBlockEntity(pos) as ChestBlockEntity
    return (0 until chest.containerSize).map { chest.getItem(it) }.filterNot { it.isEmpty }
  }

  /** Shows [file] (the hut unless said) to [player] through `build`, as the backend would ask. */
  private fun preview(player: ServerPlayer, file: ByteArray = hut) {
    val sha = MessageDigest.getInstance("SHA-256").digest(file).joinToString("") { "%02x".format(it) }
    Players.run(player, "build", buildJsonObject {
      put("action", "preview")
      putJsonObject("schematic") {
        put("id", "samples/starter-hut")
        put("url", "https://raw.githubusercontent.com/allay-ai/schematics/${"0".repeat(40)}/catalog/houses/starter-hut/build.schem")
        put("sha256", sha)
        put("bytes", file.size)
        put("title", "Starter hut")
      }
    })
  }

  /**
   * A builder for [player], as `start_task` kind building makes one (the preview becomes its job), but with no wake:
   * a wake would ask the backend, so the agent counts as busy and what it hears waits in its inbox.
   */
  private fun builder(player: ServerPlayer): Pair<Task, Building.Job> {
    val automation = Players.features.automation
    val task = automation.book.start(player.uuid, Kind.BUILDING, "hut", "build the hut", 1, null)
    task.running = true
    val site = Building.site(task, player)
    task.limits = site.limits
    val field = Agents::class.java.getDeclaredField("agents").apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    (field.get(automation.agents) as MutableMap<String, Agents.Agent>)["${task.owner}:${task.id}"] = Agents.Agent(task, site.anchor).also { it.job = site.job }
    val job = site.job as Building.Job
    // As fast as a tick allows: the test watches the end, not the pace.
    job.blocksPerSecond = 0
    return task to job
  }

  private fun construct(player: ServerPlayer, task: Task, args: JsonObject) =
    Players.run(ToolContext(ModPlayer(player), Players.features, task = task), "construct", args).result.jsonObject

  /** Whether every block of the hut stands in the world as the builder placed it. */
  private fun built(helper: GameTestHelper, job: Building.Job): String? {
    val datas = Previews.datas(blueprint, blueprint.palette, job.placement)
    // Joined, turned, and whole as the game settles them, not as the schematic says.
    val shaped = Previews.shaped(blueprint, blueprint.palette, job.placement)
    var wrong: String? = null
    blueprint.forEach { x, y, z, index ->
      if (wrong != null) return@forEach
      var want = shaped[chat.allay.core.building.Shell.pack(x, y, z)] ?: datas[index] ?: return@forEach
      if (want.hasProperty(LeavesBlock.PERSISTENT)) want = want.setValue(LeavesBlock.PERSISTENT, true)
      val (wx, wy, wz) = job.placement.world(blueprint, x, y, z)
      val have = helper.level.getBlockState(BlockPos(wx, wy, wz))
      if (have != want) wrong = "$wx $wy $wz: $have, not $want"
    }
    return wrong
  }

  private fun done(player: ServerPlayer, task: Task?, kept: Blueprints) {
    task?.let { Players.features.automation.end(it, null) }
    Previews.clear(player)
    Previews.blueprints = kept
    player.discard()
  }

  @GameTest(padding = 48, maxTicks = 600)
  fun aBuilderBuildsTheHutFromAChestBeside(helper: GameTestHelper) {
    Players.builds()
    val kept = Previews.blueprints
    Previews.blueprints = Blueprints(download = { hut })
    val player = survivor(helper, AT)
    // Behind the player, who looks south at the hut ahead.
    val box = chest(helper, BlockPos(3, 2, 0), stacks(needs))
    preview(player)
    val (task, job) = builder(player)
    val status = construct(player, task, buildJsonObject { put("action", "status") })
    helper.assertTrue(status["state"]!!.jsonPrimitive.content == "ready", "the builder waits for its go: $status")
    construct(player, task, buildJsonObject { put("action", "start") })
    helper.succeedWhen {
      helper.assertTrue(job.done, "the builder is still at ${job.next} of ${job.total}")
      helper.assertTrue(task.inbox.any { "the build is done" in it }, "the agent hears it's done: ${task.inbox}")
      built(helper, job)?.let { helper.assertTrue(false, "the hut stands as built: $it") }
      val left = chestItems(helper, box).filter { BuiltInRegistries.ITEM.getKey(it.item).path in needs }
      helper.assertTrue(left.isEmpty(), "the chest gave all of it: $left")
      done(player, task, kept)
    }
  }

  @GameTest(padding = 48, maxTicks = 800)
  fun aBuilderWaitsForAMissingMaterialAndGoesOn(helper: GameTestHelper) {
    Players.builds()
    val kept = Previews.blueprints
    Previews.blueprints = Blueprints(download = { hut })
    val player = survivor(helper, AT)
    // The material of the last blocks it places: everything before goes up, then it waits.
    val missing = needs.keys.first()
    val box = chest(helper, BlockPos(3, 2, 0), stacks(needs - missing))
    preview(player)
    val (task, job) = builder(player)
    construct(player, task, buildJsonObject { put("action", "start") })
    var brought = false
    helper.succeedWhen {
      if (!brought) {
        val status = construct(player, task, buildJsonObject { put("action", "status") })
        helper.assertTrue(status["state"]!!.jsonPrimitive.content == "waiting", "the builder waits for $missing: $status")
        val chest = helper.level.getBlockEntity(box) as ChestBlockEntity
        stacks(mapOf(missing to needs.getValue(missing))).forEach { stack ->
          val slot = (0 until chest.containerSize).first { chest.getItem(it).isEmpty }
          chest.setItem(slot, stack)
        }
        brought = true
        helper.assertTrue(false, "brought $missing; waiting for the builder to go on")
      }
      helper.assertTrue(job.done, "the builder is still at ${job.next} of ${job.total}, ${job.starved ?: "not waiting"}")
      built(helper, job)?.let { helper.assertTrue(false, "the hut stands as built: $it") }
      done(player, task, kept)
    }
  }

  private val corner = BuilderGameTests::class.java.getResourceAsStream("/schematics/decorated-corner.schem")!!.readBytes()

  @GameTest(padding = 48, maxTicks = 600)
  fun aBuilderPutsUpHeadsBannersAndFramesAsTheRulesSay(helper: GameTestHelper) {
    Players.builds()
    val kept = Previews.blueprints
    Previews.blueprints = Blueprints(download = { corner })
    val player = survivor(helper, AT)
    val decorated = Schem.read(corner)
    // No zombie head at hand: Steve's goes up in its place, for free.
    val wants = Materials.estimate(decorated, Materials.placed(decorated.palette)).items - "zombie_head"
    val box = chest(helper, BlockPos(3, 2, 0), stacks(wants))
    preview(player, corner)
    val (task, job) = builder(player)
    construct(player, task, buildJsonObject { put("action", "start") })
    helper.succeedWhen {
      helper.assertTrue(job.done, "the builder is still at ${job.progress} of ${job.total}, ${job.starved ?: "not waiting"}")
      fun at(x: Int, y: Int, z: Int): BlockPos = job.placement.world(decorated, x, y, z).let { (wx, wy, wz) -> BlockPos(wx, wy, wz) }
      val banner = helper.level.getBlockState(at(4, 1, 2))
      helper.assertTrue(BuiltInRegistries.BLOCK.getKey(banner.block).path == "white_banner", "the banner goes up white: $banner")
      val steve = helper.level.getBlockState(at(3, 1, 2))
      helper.assertTrue(steve.`is`(Blocks.PLAYER_HEAD), "Steve's head stands in for the zombie's: $steve")
      val skull = helper.level.getBlockEntity(at(0, 1, 2)) as? net.minecraft.world.level.block.entity.SkullBlockEntity
      val profile = skull?.ownerProfile
      helper.assertTrue(profile != null && profile.partialProfile().properties().containsKey("textures"), "the player head keeps its skin: $profile")
      val (low, high) = job.placement.bounds(decorated)
      val area = net.minecraft.world.phys.AABB(low.first.toDouble(), low.second.toDouble(), low.third.toDouble(), high.first + 1.0, high.second + 1.0, high.third + 1.0)
      val entities = helper.level.getEntities(null as net.minecraft.world.entity.Entity?, area) { it !is ServerPlayer }.groupingBy { net.minecraft.world.entity.EntityType.getKey(it.type).path }.eachCount()
      helper.assertTrue(entities == mapOf("item_frame" to 1, "painting" to 1, "armor_stand" to 1), "one frame, one painting, one stand: $entities")
      val left = chestItems(helper, box)
      helper.assertTrue(left.isEmpty(), "the chest gave all of it: $left")
      helper.level.getEntities(null as net.minecraft.world.entity.Entity?, area) { it !is ServerPlayer }.forEach { it.discard() }
      done(player, task, kept)
    }
  }
}
