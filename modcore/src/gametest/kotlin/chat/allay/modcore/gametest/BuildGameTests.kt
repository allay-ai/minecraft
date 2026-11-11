package chat.allay.modcore.gametest

import chat.allay.core.building.Blueprints
import chat.allay.modcore.gametest.Players.run
import chat.allay.modcore.gametest.Players.survivor
import chat.allay.modcore.tools.Previews

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.protocol.BundlePacket
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
import net.minecraft.world.phys.Vec3
import java.security.MessageDigest

/**
 * The `build` tool on a real server: a catalog build shows as ghosts to its player alone, turns, swaps a material,
 * counts what it needs, and goes. The download is the villa from the test's resources, under a catalog link.
 */
class BuildGameTests {
  private val villa = BuildGameTests::class.java.getResourceAsStream("/schematics/modern-villa.schem")!!.readBytes()

  private fun result(player: Players.Survivor, args: JsonObject) = run(player, "build", args).result.jsonObject

  /** The spots the server told [player]'s client a block stands at, section by section, since the last look. */
  private fun shownSpots(player: Players.Survivor): List<BlockPos> = player.packets().flatMap { if (it is BundlePacket<*>) it.subPackets().toList() else listOf(it) }
    .filterIsInstance<ClientboundSectionBlocksUpdatePacket>().flatMap { packet -> buildList { packet.runUpdates { pos, _ -> add(pos.immutable()) } } }

  private fun shownBlocks(player: Players.Survivor): Int = shownSpots(player).size

  /**
   * The blocks [player]'s client was told stand where the world has another: a preview's, never a real change, which
   * other tests running beside this one send every player near them.
   */
  private fun fakeSpots(player: Players.Survivor, level: net.minecraft.server.level.ServerLevel): List<BlockPos> = player.packets()
    .flatMap { if (it is BundlePacket<*>) it.subPackets().toList() else listOf(it) }
    .filterIsInstance<ClientboundSectionBlocksUpdatePacket>()
    .flatMap { packet -> buildList { packet.runUpdates { pos, state -> if (level.getBlockState(pos) != state) add(pos.immutable()) } } }

  private fun fakeBlocks(player: Players.Survivor, level: net.minecraft.server.level.ServerLevel): Int = fakeSpots(player, level).size

  @GameTest
  fun aCatalogBuildPreviewsForItsPlayerAlone(helper: GameTestHelper) {
    Players.builds()
    val kept = Previews.blueprints
    Previews.blueprints = Blueprints(download = { villa })
    val asker = survivor(helper)
    val other = survivor(helper, Vec3(5.5, 2.0, 5.5))
    asker.packets()
    other.packets()
    val sha = MessageDigest.getInstance("SHA-256").digest(villa).joinToString("") { "%02x".format(it) }
    val shown = result(asker, buildJsonObject {
      put("action", "preview")
      putJsonObject("schematic") {
        put("id", "planetminecraft/modern-villa-1201476")
        put("url", "https://raw.githubusercontent.com/allay-ai/schematics/${"0".repeat(40)}/catalog/planetminecraft/modern-villa-1201476/build.schem")
        put("sha256", sha)
        put("bytes", villa.size)
        put("title", "Modern villa")
      }
    })
    helper.assertTrue(shown["size"]!!.jsonPrimitive.content == "37×20×33", "the villa's size: $shown")
    // The preview goes as the client has its chunks: these players say they got each batch, as a game does.
    var seen = 0
    var others = 0
    var turned: JsonObject? = null
    var swapped: JsonObject? = null
    var estimate: JsonObject? = null
    var cleared: JsonObject? = null
    var restored = 0
    val before = HashSet<BlockPos>()
    // A tick's packets leave at its end, so each step reads what the one before sent on the next tick.
    var step = 0
    helper.succeedWhen {
      when (step) {
        0 -> {
          asker.connection.chunkSender.onChunkBatchReceivedByClient(64f)
          other.connection.chunkSender.onChunkBatchReceivedByClient(64f)
          fakeSpots(asker, helper.level).let { seen += it.size; before += it }
          others += fakeBlocks(other, helper.level)
          helper.assertTrue(seen > 0, "the asker sees the build's blocks")
          turned = result(asker, buildJsonObject { put("action", "rotate"); put("turns", 1) })
          step = 1
          helper.assertTrue(false, "turned; the world's blocks where it stood come next")
        }
        1 -> {
          // Turned, the build stands elsewhere: every spot it stood at gets the world's own block back, none keeps it.
          val after = shownSpots(asker).toHashSet()
          val left = before.filter { it !in after }
          helper.assertTrue(left.isEmpty(), "${left.size} of ${before.size} spots kept the build's block after the turn, the first at ${left.firstOrNull()}")
          swapped = result(asker, buildJsonObject { put("action", "swap"); put("from", "sandstone"); put("to", "stone_bricks") })
          estimate = result(asker, buildJsonObject { put("action", "estimate") })
          step = 2
          helper.assertTrue(false, "turned, swapped, and counted; the clear comes next")
        }
        2 -> {
          shownBlocks(asker)
          others += fakeBlocks(other, helper.level)
          cleared = result(asker, buildJsonObject { put("action", "clear") })
          step = 3
          helper.assertTrue(false, "cleared; the world's blocks come next")
        }
      }
      restored += shownBlocks(asker)
      helper.assertTrue(others == 0, "nobody else sees the build")
      helper.assertTrue(turned!!["facing"]!!.jsonPrimitive.content != shown["facing"]!!.jsonPrimitive.content, "a quarter turn faces another way: $turned")
      helper.assertTrue("swapped" in swapped!!, "sandstone becomes stone bricks: $swapped")
      val needs = estimate!!["needs"]!!.jsonArray.map { it.jsonObject }
      helper.assertTrue(needs.isNotEmpty() && needs.first()["need"]!!.jsonPrimitive.int > 0, "the build needs something: $estimate")
      helper.assertTrue(needs.none { it["item"]!!.jsonPrimitive.content == "sandstone" }, "no sandstone once swapped: $needs")
      helper.assertTrue("cleared" in cleared!! && Previews.of(asker) == null, "the preview is gone")
      helper.assertTrue(restored > 0, "the world's own blocks go back to the asker")
      Previews.blueprints = kept
      asker.discard()
      other.discard()
    }
  }
}
