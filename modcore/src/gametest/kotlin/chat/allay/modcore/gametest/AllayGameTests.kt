package chat.allay.modcore.gametest

import chat.allay.core.tools.ToolError
import chat.allay.modcore.Mod
import chat.allay.modcore.gametest.Players.run
import chat.allay.modcore.gametest.Players.survivor

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3

/** Allay on a real server with real players: the platform, the locator bar, marks, and books. */
class AllayGameTests {
  private fun equal(helper: GameTestHelper, actual: Any?, expected: Any?, what: String) =
    helper.assertTrue(actual == expected, "$what: expected $expected, got $actual")

  @GameTest
  fun allayRunsOnTheTestServer(helper: GameTestHelper) {
    val allay = Mod.allay
    helper.assertTrue(allay != null, "Allay is on")
    helper.assertTrue(Mod.loader.id in setOf("fabric", "neoforge"), "a loader the backend knows: ${Mod.loader.id}")
    equal(helper, allay!!.game.loader, Mod.loader.id, "the loader the backend hears")
    helper.succeed()
  }

  @GameTest
  fun theModOffersExactlyTheToolsOfTheProtocol(helper: GameTestHelper) {
    equal(helper, chat.allay.modcore.tools.Tools.all().map { it.name }.toSet(), chat.allay.core.tools.CLIENT_TOOLS, "the tools")
    equal(helper, chat.allay.modcore.tools.Tools.all().filter { it.operator }.map { it.name }.toSet(), chat.allay.core.tools.OPERATOR_TOOLS, "the operator tools")
    helper.succeed()
  }

  @GameTest
  fun theChatHistoryHearsWhispersAndWhatAllaySends(helper: GameTestHelper) {
    val history = Mod.allay!!.history
    val asker = survivor(helper)
    val other = survivor(helper, Vec3(2.5, 2.0, 1.5))
    fun lines(from: ServerPlayer) = history.waiting.filter { it.from.uuid == from.uuid.toString() }
    // The game's own /msg, as the player types it: modcore's mixin hears it.
    helper.level.server.commands.performPrefixedCommand(other.createCommandSourceStack(), "msg ${asker.gameProfile.name} got 12 diamonds?")
    // Allay sends a reply for the asker through the same command, and a line to the chat: each kept once.
    val reply = run(asker, "send_message", buildJsonObject { put("text", "sorry, none left"); put("to", other.gameProfile.name) })
    equal(helper, reply.result.jsonObject["channel"]?.jsonPrimitive?.content, "private", "where the reply went")
    run(asker, "send_message", buildJsonObject { put("text", "anyone selling diamonds?") })
    helper.assertTrue(runCatching { run(asker, "send_message", buildJsonObject { put("text", "hi"); put("to", "nobody-here") }) }.exceptionOrNull() is ToolError, "nobody to write to")
    // The backend is out of reach in the tests, so the lines wait; a round that failed puts them back.
    helper.succeedWhen {
      val theirs = lines(other).map { Triple(it.channel, it.to?.name, it.text) }
      helper.assertTrue(theirs == listOf(Triple("private", asker.gameProfile.name, "got 12 diamonds?")), "the other's whisper: $theirs")
      val mine = lines(asker).map { Triple(it.channel, it.to?.name, it.text) }
      helper.assertTrue(mine == listOf(Triple("private", other.gameProfile.name, "sorry, none left"), Triple("public", null, "anyone selling diamonds?")), "what Allay sent: $mine")
      asker.discard()
      other.discard()
    }
  }

  @GameTest
  fun nearbyPlayersAreTheLocatorBarsAndNoMore(helper: GameTestHelper) {
    val asker = survivor(helper)
    val other = survivor(helper, Vec3(1.5, 2.0, 3.5))
    val result = run(asker, "get_nearby_players").result.jsonObject
    // Players of other tests may stand on the bar too; the one two blocks away is ours.
    val players = result["players"]!!.jsonArray.map { it.jsonObject }
    val seen = players.firstOrNull { it["distance"]!!.jsonPrimitive.int == 2 }
    helper.assertTrue(seen != null, "the other player is on the bar: $players")
    // The other player never agreed here, so the model reads a label instead of their name.
    helper.assertTrue(players.none { it["name"]!!.jsonPrimitive.content == other.gameProfile.name }, "a stranger's name stays hidden: $players")
    equal(helper, seen!!.keys, setOf("name", "distance", "direction"), "what the bar tells")
    asker.discard()
    other.discard()
    helper.succeed()
  }

  @GameTest
  fun marksShowToTheAskerAlone(helper: GameTestHelper) {
    val asker = survivor(helper)
    val other = survivor(helper, Vec3(2.5, 2.0, 1.5))
    val chest = helper.absolutePos(BlockPos(1, 2, 3))
    helper.level.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState())
    asker.packets()
    other.packets()
    val output = run(asker, "show_marks", buildJsonObject {
      putJsonArray("marks") {
        addJsonObject {
          put("x", chest.x)
          put("y", chest.y)
          put("z", chest.z)
          put("label", "Chest")
        }
      }
    })
    equal(helper, output.result.jsonObject["shown"]!!.jsonPrimitive.int, 1, "marks shown")
    equal(helper, asker.packets().count { it is ClientboundAddEntityPacket }, 2, "the asker's box and label")
    equal(helper, other.packets().count { it is ClientboundAddEntityPacket }, 0, "what anyone else sees")
    helper.assertTrue(chat.allay.modcore.tools.Marks.clear(asker), "the marks go on cancel")
    asker.discard()
    other.discard()
    helper.succeed()
  }

  @GameTest
  fun marksNeverLightOres(helper: GameTestHelper) {
    val asker = survivor(helper)
    val ore = helper.absolutePos(BlockPos(1, 2, 3))
    helper.level.setBlockAndUpdate(ore, Blocks.DIAMOND_ORE.defaultBlockState())
    val refused = runCatching {
      run(asker, "show_marks", buildJsonObject {
        putJsonArray("marks") {
          addJsonObject {
            put("x", ore.x)
            put("y", ore.y)
            put("z", ore.z)
          }
        }
      })
    }.exceptionOrNull()
    helper.assertTrue(refused is ToolError && "never highlighted" in refused.message.orEmpty(), "an ore never glows: $refused")
    asker.discard()
    helper.succeed()
  }

  @GameTest
  fun aBookComesWithItsTwoButtons(helper: GameTestHelper) {
    val asker = survivor(helper)
    val context = Players.context(asker)
    val output = run(context, "write_book", buildJsonObject {
      put("title", "Redstone")
      put("text", "Dust carries power fifteen blocks. ".repeat(40))
    })
    helper.assertTrue(output.result.jsonObject["pages"]!!.jsonPrimitive.int >= 2, "a long text fills pages")
    equal(helper, context.buttons.size, 2, "the read and take buttons")
    asker.discard()
    helper.succeed()
  }
}
