package chat.allay.core.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.atan2
import kotlin.math.round

/** Pure helpers behind the tools, the same on every platform. */
object Facts {
  /** Something living near the player. Players carry no name here on purpose. */
  data class Seen(val type: String, val distance: Double, val hostile: Boolean, val player: Boolean)

  /**
   * Groups nearby entities by type. Other players collapse into one count: the agent may only
   * learn about the person who asked, so no names, types, or distances of other players leak.
   */
  fun entities(seen: List<Seen>, limit: Int = 15): JsonObject {
    val others = seen.count { it.player }
    val groups = seen.filterNot { it.player }.groupBy { it.type }
      .map { (type, list) -> Triple(type, list, list.minOf { it.distance }) }
      .sortedBy { it.third }
      .take(limit)
    return buildJsonObject {
      put("entities", buildJsonArray {
        groups.forEach { (type, list, nearest) ->
          add(buildJsonObject {
            put("type", type)
            put("count", list.size)
            put("nearest", round1(nearest))
            if (list.first().hostile) put("hostile", true)
          })
        }
      })
      put("other_players", others)
    }
  }

  /** A compass direction for a horizontal offset; Minecraft's north is negative Z. */
  fun direction(dx: Double, dz: Double): String {
    val degrees = (Math.toDegrees(atan2(dx, -dz)) + 360) % 360
    return listOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")[((degrees + 22.5) / 45).toInt() % 8]
  }

  fun dayPhase(time: Long): String = when ((time % 24_000 + 24_000) % 24_000) {
    in 0 until 12_000 -> "day"
    in 12_000 until 13_800 -> "sunset"
    in 13_800 until 22_200 -> "night"
    else -> "sunrise"
  }

  private val MOON = listOf("full", "waning_gibbous", "third_quarter", "waning_crescent", "new", "waxing_crescent", "first_quarter", "waxing_gibbous")

  fun moonPhase(fullTime: Long): String = MOON[((fullTime / 24_000) % 8).toInt()]

  fun round1(value: Double) = round(value * 10) / 10

  /**
   * A spot as every tool reports it: absolute x, y, z, so the model can hand them to point_to or a button as they are,
   * plus the horizontal distance and compass direction from where the player stands.
   */
  fun JsonObjectBuilder.place(x: Int, y: Int?, z: Int, fromX: Double, fromZ: Double) {
    put("x", x)
    y?.let { put("y", it) }
    put("z", z)
    val dx = x + 0.5 - fromX
    val dz = z + 0.5 - fromZ
    put("distance", Math.round(Math.hypot(dx, dz)))
    put("direction", direction(dx, dz))
  }

  /** A plugin's or mod's own one-liner, cut to keep the tool result small; descriptions can run to paragraphs. */
  fun shortDescription(text: String): String? {
    val line = text.replace(Regex("\\s+"), " ").trim()
    return when {
      line.isEmpty() -> null
      line.length <= 120 -> line
      else -> line.take(119).trimEnd() + "…"
    }
  }

  /** `minecraft:diamond_ore` reads as `diamond_ore`: the namespace costs tokens and says nothing. */
  fun short(key: String) = key.removePrefix("minecraft:")
}
