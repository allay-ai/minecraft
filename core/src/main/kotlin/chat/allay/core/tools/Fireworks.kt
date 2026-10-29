package chat.allay.core.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * A firework as the player describes it (“a high rocket with a big red and gold burst that crackles”), checked
 * against the game's grid, and what crafting it takes, by the game's own firework recipes. Operators get one built
 * outright (`give_item`); the crafting agent crafts it from the player's things. The same on every platform.
 */
object Fireworks {
  /** The shapes a star takes, with the item that gives each; a small ball needs none. */
  val SHAPES = linkedMapOf("small_ball" to null, "large_ball" to "fire_charge", "star" to "gold_nugget", "creeper" to "#skulls", "burst" to "feather")

  /** Any mob head gives the creeper face, as the game's `#skulls` tag says. */
  val SKULLS = setOf("skeleton_skull", "wither_skeleton_skull", "zombie_head", "player_head", "creeper_head", "dragon_head", "piglin_head")

  /** One explosion: its shape, its colors and the ones it fades to (dye colors), a trail, and a crackle. */
  data class Star(val shape: String, val colors: List<String>, val fade: List<String>, val trail: Boolean, val twinkle: Boolean)

  /** A rocket's flight (1–3 gunpowder) and its stars; a star alone is one [Star]. */
  data class Spec(val flight: Int, val stars: List<Star>)

  /** What one batch takes: ingredients by id (a set of ids where any one does: a mob head), and grids over 4 items. */
  data class Needs(val items: Map<Set<String>, Int>, val crafts: Int, val makes: Int, val table: Boolean)

  /** Rockets one craft makes. */
  const val ROCKETS_PER_CRAFT = 3

  /** Reads `firework` from a tool's arguments: `{ flight, stars: [{ shape, colors, fade, trail, twinkle }] }`. */
  fun parse(json: JsonObject?, star: Boolean): Spec {
    json ?: throw ToolError("firework is required: { flight, stars: [{ shape, colors, fade, trail, twinkle }] }")
    val flight = (json["flight"] as? JsonPrimitive)?.intOrNull ?: 1
    if (!star && flight !in 1..3) throw ToolError("flight must be 1–3 (gunpowder in the rocket)")
    val stars = (json["stars"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty().map(::star)
    if (star && stars.size != 1) throw ToolError("a firework star is one explosion: pass exactly one in stars")
    if (!star && stars.size > 8 - flight) throw ToolError("a rocket with $flight gunpowder takes at most ${8 - flight} stars: paper, gunpowder, and stars share 9 slots")
    return Spec(flight, stars)
  }

  private fun star(json: JsonObject): Star {
    val shape = (json["shape"] as? JsonPrimitive)?.contentOrNull?.lowercase()?.replace(' ', '_') ?: "small_ball"
    if (shape !in SHAPES) throw ToolError("shape must be one of ${SHAPES.keys.joinToString()}")
    fun colors(key: String) = (json[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase()?.removeSuffix("_dye")?.replace(' ', '_') }.orEmpty()
      .onEach { if (it !in Dyes.COLORS) throw ToolError("$it is no dye color; colors are ${Dyes.COLORS.joinToString()}") }
    val colors = colors("colors")
    val fade = colors("fade")
    val trail = (json["trail"] as? JsonPrimitive)?.booleanOrNull == true
    val twinkle = (json["twinkle"] as? JsonPrimitive)?.booleanOrNull == true
    val extras = (if (SHAPES[shape] != null) 1 else 0) + (if (trail) 1 else 0) + (if (twinkle) 1 else 0)
    if (colors.isEmpty()) throw ToolError("a star needs at least one color")
    if (1 + colors.size + extras > 9) throw ToolError("a star takes at most ${8 - extras} colors with that shape and those effects: they share 9 slots with the gunpowder")
    if (fade.size > 8) throw ToolError("a star fades to at most 8 colors")
    return Star(shape, colors, fade, trail, twinkle)
  }

  /** What [count] rockets (or stars, with [star]) take, and whether any grid needs a crafting table. */
  fun needs(spec: Spec, count: Int, star: Boolean): Needs {
    val crafts = if (star) count else (count + ROCKETS_PER_CRAFT - 1) / ROCKETS_PER_CRAFT
    val items = mutableMapOf<Set<String>, Int>()
    fun add(id: String, n: Int) = items.merge(if (id == "#skulls") SKULLS else setOf(id), n * crafts, Int::plus)
    var table = false
    spec.stars.forEach { one ->
      add("gunpowder", 1)
      one.colors.forEach { add("${it}_dye", 1) }
      SHAPES[one.shape]?.let { add(it, 1) }
      if (one.trail) add("diamond", 1)
      if (one.twinkle) add("glowstone_dust", 1)
      one.fade.forEach { add("${it}_dye", 1) }
      val grid = 1 + one.colors.size + (if (SHAPES[one.shape] != null) 1 else 0) + (if (one.trail) 1 else 0) + (if (one.twinkle) 1 else 0)
      if (grid > 4 || 1 + one.fade.size > 4) table = true
    }
    if (!star) {
      add("paper", 1)
      add("gunpowder", spec.flight)
      if (1 + spec.flight + spec.stars.size > 4) table = true
    }
    return Needs(items, crafts, if (star) count else crafts * ROCKETS_PER_CRAFT, table)
  }
}
