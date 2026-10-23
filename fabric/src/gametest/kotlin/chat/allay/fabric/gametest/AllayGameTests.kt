package chat.allay.fabric.gametest

import chat.allay.core.tools.ToolError
import chat.allay.fabric.AllayMod
import chat.allay.fabric.gametest.Players.run
import chat.allay.fabric.gametest.Players.survivor

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3

/** Allay on a real server with real players: the platform, the locator bar, marks, and books. */
class AllayGameTests {
  private fun equal(helper: GameTestHelper, actual: Any?, expected: Any?, what: String) =
    helper.assertTrue(actual == expected, "$what: expected $expected, got $actual")

  @GameTest
  fun allayRunsOnTheTestServer(helper: GameTestHelper) {
    val allay = AllayMod.allay
    helper.assertTrue(allay != null, "Allay is on")
    equal(helper, allay!!.game.loader, "fabric", "the loader the backend hears")
    helper.succeed()
  }

  @GameTest
  fun nearbyPlayersAreTheLocatorBarsAndNoMore(helper: GameTestHelper) {
    val asker = survivor(helper)
    val other = survivor(helper, Vec3(1.5, 2.0, 3.5))
    val result = run(asker, "get_nearby_players").result.jsonObject
    val players = result["players"]!!.jsonArray
    equal(helper, players.size, 1, "players on the bar")
    val seen = players[0].jsonObject
    // The other player never agreed here, so the model reads a label instead of their name.
    helper.assertTrue(seen["name"]!!.jsonPrimitive.content != other.gameProfile.name, "a stranger's name stays hidden: $seen")
    equal(helper, seen.keys, setOf("name", "distance", "direction"), "what the bar tells")
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
    helper.assertTrue(chat.allay.fabric.tools.Marks.clear(asker), "the marks go on cancel")
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
