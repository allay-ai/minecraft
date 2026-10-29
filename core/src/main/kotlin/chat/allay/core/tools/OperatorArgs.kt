package chat.allay.core.tools

/** Argument rules for the operator tools, apart from Bukkit so tests can check them. */
object OperatorArgs {
  const val MAX_ITEMS = 576
  const val MAX_EFFECT_SECONDS = 3_600
  const val MAX_EFFECT_LEVEL = 10
  const val MAX_ENCHANTMENT_LEVEL = 255
  const val MAX_WEATHER_SECONDS = 86_400

  private val TIMES = mapOf("day" to 1_000L, "noon" to 6_000L, "night" to 13_000L, "midnight" to 18_000L)
  val GAMEMODES = setOf("survival", "creative", "adventure", "spectator")
  val WEATHER = setOf("clear", "rain", "thunder")

  fun count(value: Int?): Int {
    val count = value ?: 1
    if (count !in 1..MAX_ITEMS) throw ToolError("count must be 1–$MAX_ITEMS")
    return count
  }

  fun effectSeconds(value: Int?): Int {
    val seconds = value ?: 180
    if (seconds !in 1..MAX_EFFECT_SECONDS) throw ToolError("seconds must be 1–$MAX_EFFECT_SECONDS")
    return seconds
  }

  fun effectLevel(value: Int?): Int {
    val level = value ?: 1
    if (level !in 1..MAX_EFFECT_LEVEL) throw ToolError("level must be 1–$MAX_EFFECT_LEVEL")
    return level
  }

  fun enchantmentLevel(value: Int?): Int {
    if (value == null || value !in 1..MAX_ENCHANTMENT_LEVEL) throw ToolError("enchantment levels must be 1–$MAX_ENCHANTMENT_LEVEL")
    return value
  }

  /** `day`, `noon`, `night`, `midnight`, or ticks 0–23999. */
  fun time(value: String?): Long {
    val text = value?.lowercase() ?: throw ToolError("time is required")
    TIMES[text]?.let { return it }
    val ticks = text.toLongOrNull() ?: throw ToolError("time must be day, noon, night, midnight, or 0–23999")
    if (ticks !in 0..23_999) throw ToolError("time must be 0–23999")
    return ticks
  }

  /** Effects that hurt: `heal` takes them off too, or poison and wither would undo it in seconds. */
  val HARMFUL = setOf(
    "poison", "wither", "hunger", "slowness", "mining_fatigue", "weakness", "blindness", "nausea", "darkness", "levitation",
    "instant_damage", "unluck", "infested", "oozing", "weaving", "wind_charged",
  )

  /** Items a potion type goes on, as `give_item`'s `potion` takes it. */
  val POTION_ITEMS = setOf("potion", "splash_potion", "lingering_potion", "tipped_arrow")

  /**
   * Why a player wouldn't stand safely somewhere, from what's at their feet, head, and below, or null when they would:
   * [fall] is how many free blocks lie under their feet before ground.
   */
  fun danger(feetSolid: Boolean, headSolid: Boolean, lava: Boolean, water: Boolean, fall: Int): String? = when {
    feetSolid || headSolid -> "inside blocks: the player would suffocate"
    lava -> "lava"
    water -> "in water"
    fall > 3 -> "a fall of $fall blocks"
    else -> null
  }

  fun gamemode(value: String?): String {
    val mode = value?.lowercase()
    if (mode !in GAMEMODES) throw ToolError("mode must be one of ${GAMEMODES.joinToString()}")
    return mode!!
  }

  fun weather(value: String?): String {
    val weather = value?.lowercase()
    if (weather !in WEATHER) throw ToolError("weather must be one of ${WEATHER.joinToString()}")
    return weather!!
  }

  fun weatherSeconds(value: Int?): Int {
    val seconds = value ?: 600
    if (seconds !in 1..MAX_WEATHER_SECONDS) throw ToolError("seconds must be 1–$MAX_WEATHER_SECONDS")
    return seconds
  }

  /** `minecraft:Diamond Sword` reads as `diamond_sword`. */
  fun id(value: String?): String? = value?.trim()?.lowercase()?.removePrefix("minecraft:")?.replace(' ', '_')?.ifEmpty { null }
}
