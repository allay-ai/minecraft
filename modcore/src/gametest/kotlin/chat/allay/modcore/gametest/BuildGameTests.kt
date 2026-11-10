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
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.protocol.BundlePacket
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.phys.Vec3
import java.security.MessageDigest

/**
 * The `build` tool on a real server: a catalog build shows as ghosts to its player alone, turns, swaps a material,
 * counts what it needs, and goes. The download is the villa from the test's resources, under a catalog link.
 */
class BuildGameTests {
  private val villa = BuildGameTests::class.java.getResourceAsStream("/schematics/modern-villa.schem")!!.readBytes()

  private fun result(player: Players.Survivor, args: JsonObject) = run(player, "build", args).result.jsonObject

  /** Display entities the server told [player] about since the last look. */
  private fun displays(player: Players.Survivor): Int = player.packets().flatMap { if (it is BundlePacket<*>) it.subPackets().toList() else listOf(it) }
    .count { it is ClientboundAddEntityPacket && (it.type == EntityTypes.BLOCK_DISPLAY || it.type == EntityTypes.ITEM_DISPLAY) }

  @GameTest
  fun aCatalogBuildPreviewsForItsPlayerAlone(helper: GameTestHelper) {
    val kept = Previews.blueprints
    Previews.blueprints = Blueprints(download = { villa })
    val asker = survivor(helper)
    val other = survivor(helper, Vec3(5.5, 2.0, 5.5))
    try {
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
      helper.assertTrue(displays(asker) > 0, "the asker sees the build's ghosts")
      helper.assertTrue(displays(other) == 0, "nobody else does")

      val turned = result(asker, buildJsonObject { put("action", "rotate"); put("turns", 1) })
      helper.assertTrue(turned["facing"]!!.jsonPrimitive.content != shown["facing"]!!.jsonPrimitive.content, "a quarter turn faces another way: $turned")

      val swapped = result(asker, buildJsonObject { put("action", "swap"); put("from", "sandstone"); put("to", "stone_bricks") })
      helper.assertTrue("swapped" in swapped, "sandstone becomes stone bricks: $swapped")

      val estimate = result(asker, buildJsonObject { put("action", "estimate") })
      val needs = estimate["needs"]!!.jsonArray.map { it.jsonObject }
      helper.assertTrue(needs.isNotEmpty() && needs.first()["need"]!!.jsonPrimitive.int > 0, "the build needs something: $estimate")
      helper.assertTrue(needs.none { it["item"]!!.jsonPrimitive.content == "sandstone" }, "no sandstone once swapped: $needs")

      val cleared = result(asker, buildJsonObject { put("action", "clear") })
      helper.assertTrue("cleared" in cleared && Previews.of(asker) == null, "the preview is gone")
      helper.succeed()
    } finally {
      Previews.clear(asker)
      Previews.blueprints = kept
      asker.discard()
      other.discard()
    }
  }
}
