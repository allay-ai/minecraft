package chat.allay.core.tools

/**
 * Which of the server's structures a name means, the same on every platform. Players and the model say “woodland
 * mansion” or “nether fortress”, while the game's ids are `mansion` and `fortress`, so those names map over, spaces
 * count as underscores, and a name with variants (`ruined_portal`) finds them all.
 */
object Structures {
  /** What people call a structure, to the game's id or the start of its variants' ids. */
  private val ALIASES = mapOf(
    "woodland_mansion" to "mansion", "ocean_monument" to "monument", "nether_fortress" to "fortress", "witch_hut" to "swamp_hut",
    "swamp_hut" to "swamp_hut", "trial_chamber" to "trial_chambers", "ancient_cities" to "ancient_city", "end_city" to "end_city",
    "outpost" to "pillager_outpost", "bastion" to "bastion_remnant", "shipwrecks" to "shipwreck", "villages" to "village",
    "pyramid" to "desert_pyramid", "desert_temple" to "desert_pyramid", "jungle_temple" to "jungle_pyramid", "portal" to "ruined_portal", "geode" to "amethyst_geode",
  )

  /** At most this many ids are searched at once: each one is a search on the server thread. */
  const val MAX = 8

  /** The ids among [keys] that [name] means, best first; empty when none. */
  fun match(name: String, keys: Collection<String>): List<String> {
    val wanted = name.lowercase().removePrefix("minecraft:").trim().replace(Regex("[\\s-]+"), "_")
    val id = ALIASES[wanted] ?: ALIASES[wanted.removeSuffix("s")] ?: wanted
    val exact = keys.filter { it == id || it.startsWith("${id}_") }
    if (exact.isNotEmpty()) return exact.take(MAX)
    // Any id holding every word: “desert temple” finds nothing above, but `desert_pyramid` holds “desert”.
    val words = id.split('_').filter { it.length >= 3 }
    return keys.filter { key -> words.isNotEmpty() && words.all { it in key } }.take(MAX)
  }

  /** The refusal for a name nothing matched, with the server's close ids so the model can ask again. */
  fun missing(name: String, keys: Collection<String>): String {
    val close = Names.close(name, keys)
    return "no structure called $name" + if (close.isEmpty()) "; this server's structures include ${kinds(keys).joinToString()}" else "; close ones: ${close.joinToString()}"
  }

  /** The ids with their variants folded: five `village_<biome>` read as `village_*`, which [match] takes as `village`. */
  private fun kinds(keys: Collection<String>): List<String> {
    val groups = keys.groupBy { key -> key.substringBefore('_') }
    return groups.flatMap { (base, ids) -> if (ids.size > 2) listOf("${base}_*") else ids }.sorted()
  }
}
