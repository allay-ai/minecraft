package chat.allay.core.building

import chat.allay.core.YamlConfig

/** How big a build a player may build here, how fast it goes up, and how long it waits for them. */
data class BuildLimits(
  val maxBlocks: Int,
  val maxSize: Int,
  /** Blocks a second; 0 is as fast as each tick's budget allows. */
  val blocksPerSecond: Int,
  val pauseDistance: Int,
  val parkHours: Int,
)

/**
 * `building.tiers` in config.yml: the first tier, in the file's order, whose `allay.build.<tier>` a player holds, and
 * `default` for everyone else. `allay.build.unlimited` lifts the size limits; operators get the biggest tier there is.
 */
class BuildTiers(val tiers: Map<String, BuildLimits>) {
  fun of(operator: Boolean, holds: (String) -> Boolean): BuildLimits {
    val chosen = when {
      operator -> tiers.values.maxByOrNull { it.maxBlocks }
      else -> tiers.entries.firstOrNull { (tier, _) -> tier != DEFAULT && holds(tier) }?.value ?: tiers[DEFAULT]
    } ?: FALLBACK
    return if (holds(UNLIMITED)) chosen.copy(maxBlocks = Int.MAX_VALUE, maxSize = Int.MAX_VALUE) else chosen
  }

  companion object {
    const val DEFAULT = "default"
    const val UNLIMITED = "unlimited"
    /** What a server gets without a `building` section: a good house, a small castle. */
    val FALLBACK = BuildLimits(maxBlocks = 50_000, maxSize = 128, blocksPerSecond = 5, pauseDistance = 64, parkHours = 24)

    fun parse(config: YamlConfig): BuildTiers {
      val section = config.section("building.tiers") ?: return BuildTiers(mapOf(DEFAULT to FALLBACK))
      val tiers = LinkedHashMap<String, BuildLimits>()
      for (key in section.keys) {
        val tier = section.section(key) ?: continue
        tiers[key.lowercase()] = BuildLimits(
          maxBlocks = tier.int("max_blocks", FALLBACK.maxBlocks).coerceIn(100, 2_000_000),
          maxSize = tier.int("max_size", FALLBACK.maxSize).coerceIn(8, 1024),
          blocksPerSecond = tier.int("blocks_per_second", FALLBACK.blocksPerSecond).coerceIn(0, 1000),
          pauseDistance = tier.int("pause_distance", FALLBACK.pauseDistance).coerceIn(16, 256),
          parkHours = tier.int("park_hours", FALLBACK.parkHours).coerceIn(1, 168),
        )
      }
      if (DEFAULT !in tiers) tiers[DEFAULT] = FALLBACK
      return BuildTiers(tiers)
    }
  }
}
