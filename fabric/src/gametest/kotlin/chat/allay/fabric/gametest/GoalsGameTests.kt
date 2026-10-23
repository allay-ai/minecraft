package chat.allay.fabric.gametest

import chat.allay.core.Handler
import chat.allay.core.tools.ToolError
import chat.allay.fabric.AllayMod
import chat.allay.fabric.FabricGame
import chat.allay.fabric.FabricPlayer
import chat.allay.fabric.Placeholders
import chat.allay.fabric.gametest.Players.run
import chat.allay.fabric.gametest.Players.survivor
import chat.allay.fabric.tools.Drafts

import eu.pb4.common.protection.api.CommonProtection
import eu.pb4.common.protection.api.ProtectionProvider
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundBossEventPacket
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.players.NameAndId
import net.minecraft.stats.Stats
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Goals with their bars and progress, drafts, redstone, and placeholders, on a real server with real players. They run
 * in an environment of their own, a batch after the others, since their players wait for the poll and would show on
 * other tests' locator bars.
 */
class GoalsGameTests {
  private companion object {
    const val ENVIRONMENT = "allay-gametest:goals"
  }

  private fun equal(helper: GameTestHelper, actual: Any?, expected: Any?, what: String) =
    helper.assertTrue(actual == expected, "$what: expected $expected, got $actual")

  private fun refused(block: () -> Unit): String? = (runCatching(block).exceptionOrNull() as? ToolError)?.message

  private fun goal(player: ServerPlayer, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
    run(player, "goals", buildJsonObject {
      put("action", "set")
      build()
    }).result.jsonObject

  private fun goals(player: ServerPlayer) = Players.features.goals.of(player.uuid).all()

  @GameTest(environment = ENVIRONMENT, maxTicks = 100)
  fun aLevelGoalShowsItsBarAndCompletes(helper: GameTestHelper) {
    val player = survivor(helper)
    player.setExperienceLevels(2)
    player.packets()
    val goal = goal(player) {
      put("kind", "level")
      put("title", "Level five")
      put("target", 5)
      put("color", "purple")
      put("style", "segmented_10")
    }
    // The level counts from where the player stands now.
    equal(helper, goal["progress"]!!.jsonPrimitive.int, 2, "progress at the start")
    equal(helper, goal["target"]!!.jsonPrimitive.int, 5, "target")
    val id = goal["id"]!!.jsonPrimitive.content
    val bar = Players.features.goals.bar(player.uuid, id)
    helper.assertTrue(bar != null, "the goal has a bar")
    equal(helper, bar!!.progress, 0.4f, "the bar's fill")
    equal(helper, bar.color, net.minecraft.world.BossEvent.BossBarColor.PURPLE, "the bar's color")
    equal(helper, bar.overlay, net.minecraft.world.BossEvent.BossBarOverlay.NOTCHED_10, "the bar's style")
    helper.assertTrue("Level five" in bar.name.string, "the bar says the title: ${bar.name.string}")
    helper.assertTrue(player.packets().any { it is ClientboundBossEventPacket }, "the player's client hears of the bar")
    player.setExperienceLevels(5)
    helper.succeedWhen {
      helper.assertTrue(goals(player).isEmpty(), "a reached goal goes")
      helper.assertTrue(Players.features.goals.bar(player.uuid, id) == null, "and its bar with it")
      helper.assertTrue(player.chat.any { "Level five" in it }, "the player hears it's done: ${player.chat}")
      player.discard()
    }
  }

  @GameTest(environment = ENVIRONMENT, maxTicks = 100)
  fun aStatisticGoalCountsFromWhenItWasSet(helper: GameTestHelper) {
    val player = survivor(helper)
    player.awardStat(Stats.BLOCK_MINED.get(Blocks.STONE), 7)
    val goal = goal(player) {
      put("kind", "stat")
      put("title", "Stone")
      put("statistic", "mine_block")
      put("item", "stone")
      put("target", 10)
    }
    equal(helper, goal["progress"]!!.jsonPrimitive.int, 0, "what was mined before doesn't count")
    player.awardStat(Stats.BLOCK_MINED.get(Blocks.STONE), 3)
    helper.succeedWhen {
      equal(helper, goals(player).single().progress, 3L, "progress after mining three")
      player.discard()
    }
  }

  @GameTest(environment = ENVIRONMENT)
  fun statisticsKeepBukkitsNamesAndScale(helper: GameTestHelper) {
    val player = survivor(helper)
    val walk = goal(player) {
      put("kind", "stat")
      put("title", "Walk")
      put("statistic", "walk_one_cm")
      put("target", 100)
    }
    // Centimeters underneath, blocks for the model.
    equal(helper, walk["target"]!!.jsonPrimitive.int, 100, "the target in blocks")
    equal(helper, goals(player).single().target, 10_000L, "the target in centimeters")
    goal(player) {
      put("kind", "stat")
      put("title", "Sneak")
      put("statistic", "sneak_time")
      put("target", 5)
    }
    equal(helper, refused { goal(player) { put("kind", "stat"); put("title", "x"); put("statistic", "nope"); put("target", 1) } }, "no statistic called nope", "an unknown statistic")
    equal(helper, refused { goal(player) { put("kind", "stat"); put("title", "x"); put("statistic", "mine_block"); put("item", "redstone"); put("target", 1) } }, "redstone is not a block", "an item that isn't a block")
    equal(helper, refused { goal(player) { put("kind", "level"); put("title", "x"); put("target", 3); put("color", "orange") } },
      "color must be one of pink, blue, red, green, yellow, purple, white", "Bukkit's colors")
    equal(helper, refused { goal(player) { put("kind", "level"); put("title", "x"); put("target", 3); put("style", "dotted") } },
      "style must be one of solid, segmented_6, segmented_10, segmented_12, segmented_20", "Bukkit's styles")
    player.discard()
    helper.succeed()
  }

  @GameTest(environment = ENVIRONMENT)
  fun pickupsCountButNotThePlayersOwnThrows(helper: GameTestHelper) {
    val player = survivor(helper)
    goal(player) {
      put("kind", "pickup")
      put("title", "Diamonds")
      put("item", "diamond")
      put("target", 16)
    }
    fun drop(count: Int, thrower: Player?) {
      val item = ItemEntity(helper.level, player.x, player.y, player.z, ItemStack(Items.DIAMOND, count))
      item.setNoPickUpDelay()
      thrower?.let(item::setThrower)
      helper.level.addFreshEntity(item)
      item.playerTouch(player)
    }
    drop(3, null)
    equal(helper, goals(player).single().progress, 3L, "found diamonds")
    drop(2, player)
    equal(helper, goals(player).single().progress, 3L, "the player's own throw back")
    player.discard()
    helper.succeed()
  }

  @GameTest(environment = ENVIRONMENT, maxTicks = 100)
  fun reachingAPlaceFillsTheBar(helper: GameTestHelper) {
    val player = survivor(helper)
    val goal = goal(player) {
      put("kind", "reach")
      put("title", "Far spot")
      put("x", player.blockX + 40)
      put("z", player.blockZ)
      put("radius", 5)
    }
    // Half a block short: the player stands in the middle of their block.
    equal(helper, goal["target"]!!.jsonPrimitive.int, 39, "the distance at the start")
    player.teleportTo(player.x + 38, player.y, player.z)
    helper.succeedWhen {
      helper.assertTrue(goals(player).isEmpty(), "the spot is reached")
      player.discard()
    }
  }

  @GameTest(environment = ENVIRONMENT)
  fun anAdvancementGoalEndsWithTheAdvancement(helper: GameTestHelper) {
    val player = survivor(helper)
    goal(player) {
      put("kind", "advancement")
      put("title", "Stone Age")
      put("advancement", "story/mine_stone")
    }
    equal(helper, refused { goal(player) { put("kind", "advancement"); put("title", "x"); put("advancement", "story/nope") } }, "no advancement called story/nope", "an unknown advancement")
    val holder = helper.level.server.advancements.get(Identifier.withDefaultNamespace("story/mine_stone"))!!
    holder.value().criteria().keys.forEach { player.advancements.award(holder, it) }
    helper.assertTrue(goals(player).isEmpty(), "the advancement ends the goal at once")
    player.discard()
    helper.succeed()
  }

  private fun stoneDraft(at: BlockPos) = buildJsonObject {
    put("title", "Pillar")
    put("x", at.x)
    put("y", at.y)
    put("z", at.z)
    putJsonObject("palette") { put("s", "stone") }
    putJsonArray("layers") {
      add(kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("s")) })
      add(kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("s")) })
    }
  }

  @GameTest(environment = ENVIRONMENT, maxTicks = 100)
  fun aDraftShowsToItsPlayerAloneAndLeadsThroughIt(helper: GameTestHelper) {
    val player = survivor(helper)
    val other = survivor(helper, Vec3(2.5, 2.0, 1.5))
    val at = helper.absolutePos(BlockPos(1, 2, 4))
    player.packets()
    other.packets()
    val shown = run(player, "show_draft", stoneDraft(at)).result.jsonObject
    equal(helper, shown["blocks"]!!.jsonPrimitive.int, 2, "blocks in the draft")
    equal(helper, shown["layer"]!!.jsonPrimitive.content, "1/2", "the layer shown")
    helper.assertTrue(player.packets().count { it is ClientboundAddEntityPacket } >= 1, "the player sees the ghost")
    equal(helper, other.packets().count { it is ClientboundAddEntityPacket }, 0, "what anyone else sees")
    val checked = run(player, "show_draft", buildJsonObject { put("action", "check") }).result.jsonObject
    equal(helper, checked["total"]!!.jsonPrimitive.int, 2, "the check counts every block")
    equal(helper, refused { run(player, "show_draft", stoneDraft(at).let { JsonObject(it + ("layers" to kotlinx.serialization.json.buildJsonArray { repeat(16) { add(kotlinx.serialization.json.buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("s")) }) } })) }) }?.substringBefore(" (")?.substringAfter("would be "),
      "1×16×1", "the size limit")
    helper.level.setBlockAndUpdate(at, Blocks.STONE.defaultBlockState())
    helper.level.setBlockAndUpdate(at.above(), Blocks.STONE.defaultBlockState())
    helper.succeedWhen {
      helper.assertTrue(Drafts.of(player) == null, "a built draft goes")
      helper.assertTrue(player.chat.any { "Pillar" in it }, "the player hears it's built: ${player.chat}")
      player.discard()
      other.discard()
    }
  }

  @GameTest(environment = ENVIRONMENT)
  fun cancelHidesTheDraft(helper: GameTestHelper) {
    val player = survivor(helper)
    run(player, "show_draft", stoneDraft(helper.absolutePos(BlockPos(1, 2, 4))))
    val game = AllayMod.allay!!.game as FabricGame
    equal(helper, game.shown.hide(FabricPlayer(player)), Handler.Hidden.DRAFT, "what /allay cancel hides")
    helper.assertTrue(Drafts.of(player) == null, "the draft is gone")
    player.discard()
    helper.succeed()
  }

  @GameTest(environment = ENVIRONMENT)
  fun redstoneShowsFaultsAndReceivedPower(helper: GameTestHelper) {
    val player = survivor(helper)
    val dust = helper.absolutePos(BlockPos(3, 2, 3))
    val lamp = helper.absolutePos(BlockPos(1, 2, 3))
    helper.level.setBlockAndUpdate(dust.below(), Blocks.STONE.defaultBlockState())
    helper.level.setBlockAndUpdate(dust, Blocks.REDSTONE_WIRE.defaultBlockState())
    helper.level.setBlockAndUpdate(lamp.below(), Blocks.REDSTONE_BLOCK.defaultBlockState())
    helper.level.setBlockAndUpdate(lamp, Blocks.REDSTONE_LAMP.defaultBlockState())
    val result = run(player, "inspect_redstone", buildJsonObject { put("around", "player") }).result.jsonObject
    val components = result["components"]!!.jsonArray.map { it.jsonObject }
    val lit = components.single { it["id"]!!.jsonPrimitive.content == "redstone_lamp" }
    equal(helper, lit["received"]!!.jsonPrimitive.int, 15, "a lamp on a block of redstone")
    val issues = result["issues"]!!.jsonArray.map { it.jsonObject["issue"]!!.jsonPrimitive.content }
    helper.assertTrue("dust_dot" in issues, "a lone dot of dust is a fault: $issues")
    player.discard()
    helper.succeed()
  }

  @GameTest(environment = ENVIRONMENT)
  fun draftsAndRedstoneStayOutOfSomeoneElsesClaim(helper: GameTestHelper) {
    val player = survivor(helper)
    val claim = helper.bounds.inflate(1.0)
    Claim.areas += claim
    try {
      equal(helper, refused { run(player, "show_draft", stoneDraft(helper.absolutePos(BlockPos(1, 2, 4)))) },
        "that's someone else's claim where the player may not build; drafts go only where they could build", "a draft in a claim")
      equal(helper, refused { run(player, "inspect_redstone", buildJsonObject { put("around", "player") }) },
        "this is someone else's claim where the player may not build: Allay reads redstone only where the player could build it", "redstone in a claim")
    } finally {
      Claim.areas -= claim
    }
    player.discard()
    helper.succeed()
  }

  @GameTest(environment = ENVIRONMENT)
  fun placeholdersResolveForTheReader(helper: GameTestHelper) {
    val player = survivor(helper)
    helper.assertTrue(Placeholders.available, "Text Placeholder API is on the test server")
    val resolve = Placeholders.about(AllayMod.platform!!, player)!!
    equal(helper, resolve("player:name"), player.gameProfile.name, "Text Placeholder API's id")
    equal(helper, resolve("player_name"), player.gameProfile.name, "a PlaceholderAPI-style name")
    equal(helper, resolve("nothing:here"), null, "an unknown placeholder")
    goal(player) {
      put("kind", "level")
      put("title", "Ten")
      put("target", 10)
    }
    val context = eu.pb4.placeholders.api.ServerPlaceholderContext.of(player)
    fun allay(name: String) = eu.pb4.placeholders.api.Placeholders.parseServerPlaceholder(Identifier.fromNamespaceAndPath("allay", name), null, context).component().string
    equal(helper, allay("goals"), "1", "%allay:goals%")
    equal(helper, allay("chat_mode"), "no", "%allay:chat_mode%")
    equal(helper, allay("thinking"), "no", "%allay:thinking%")
    helper.assertTrue(allay("language").isNotEmpty(), "%allay:language%")
    player.discard()
    helper.succeed()
  }

  /** A claims mod for the tests: nobody builds in [areas]. */
  object Claim : ProtectionProvider {
    val areas = CopyOnWriteArrayList<AABB>()

    init {
      CommonProtection.register(Identifier.fromNamespaceAndPath("allay-gametest", "claims"), this)
    }

    private fun inside(pos: BlockPos) = areas.any { it.contains(Vec3.atCenterOf(pos)) }
    override fun isProtected(level: Level, pos: BlockPos) = inside(pos)
    override fun isAreaProtected(level: Level, area: AABB) = areas.any { it.intersects(area) }
    override fun canPlaceBlock(level: Level, pos: BlockPos, profile: NameAndId, player: Player?) = !inside(pos)
    override fun canBreakBlock(level: Level, pos: BlockPos, profile: NameAndId, player: Player?) = !inside(pos)
  }
}
