package chat.allay.core.tools

/**
 * Item names as a model writes them: `Torches`, `minecraft:oak_log`, `#planks`, `iron ingots`. Turns them into the
 * game's ids where that's unambiguous, and names the ids that come close where it isn't, so a wrong name never passes
 * as “none here”.
 */
object Names {
  /** Lower case, without the namespace or a tag's `#`, underscores for spaces and dashes. */
  fun normalize(name: String): String = name.trim().lowercase().removePrefix("#").replace("minecraft:", "").trim().replace(Regex("[\\s-]+"), "_")

  /** [id] first, then what it may mean as a plural or a singular: torches → torch, potatoes → potato, berries → berry, wheat_seed → wheat_seeds. */
  fun forms(id: String): List<String> = buildList {
    add(id)
    if (id.endsWith("ies")) add(id.dropLast(3) + "y")
    if (id.endsWith("es")) add(id.dropLast(2))
    if (id.endsWith("s")) add(id.dropLast(1))
    else add(id + "s")
  }.filter { it.isNotEmpty() }.distinct()

  /** Words that mean one item, as players say them. */
  private val ALIASES = mapOf(
    "iron" to "iron_ingot", "gold" to "gold_ingot", "copper" to "copper_ingot", "netherite" to "netherite_ingot",
    "lapis" to "lapis_lazuli", "redstone_dust" to "redstone", "redstone_wire" to "redstone", "bonemeal" to "bone_meal",
    "sugarcane" to "sugar_cane", "seeds" to "wheat_seeds", "bottle" to "glass_bottle", "empty_bottle" to "glass_bottle",
    "eye_of_ender" to "ender_eye", "enderpearl" to "ender_pearl", "workbench" to "crafting_table", "cobble" to "cobblestone",
    "glistering_melon" to "glistering_melon_slice", "fermented_eye" to "fermented_spider_eye", "steak" to "cooked_beef",
    "slimeball" to "slime_ball", "wart" to "nether_wart", "netherwart" to "nether_wart",
    "sulphur" to "gunpowder",
  )

  /**
   * The id [name] means, among the ids [known] accepts: the name itself, a word of [ALIASES], or a plural's
   * singular. Throws a [ToolError] that names the closest of [all] when nothing fits.
   */
  fun resolve(name: String, known: (String) -> Boolean, all: () -> Iterable<String>): String {
    val wanted = normalize(name)
    val tried = (forms(wanted).take(1) + listOfNotNull(ALIASES[wanted]) + forms(wanted).drop(1) + forms(wanted).mapNotNull(ALIASES::get)).distinct()
    tried.firstOrNull { it.isNotEmpty() && known(it) }?.let { return it }
    throw ToolError(missing(name, all()))
  }

  /** An enchantment's id as the model may write it: “Sharpness V”, `unbreaking_3`, `minecraft:silk touch`. */
  fun enchantment(name: String): String =
    normalize(name).replace(Regex("_(\\d+|i{1,3}|iv|v)$"), "").replace(Regex("[^a-z0-9_]"), "").let { ENCHANTMENTS[it] ?: it }

  private val ENCHANTMENTS = mapOf("silk" to "silk_touch", "prot" to "protection", "loot" to "looting")

  /** “no item called X”, with the ids [name] most likely meant: those holding it (planks finds oak_planks), else the nearest spellings. */
  fun missing(name: String, all: Iterable<String>): String {
    val close = close(name, all)
    return "no item called $name" + if (close.isEmpty()) "" else "; use an exact id, like ${close.joinToString()}"
  }

  /** Up to [limit] ids of [all] close to [name]. */
  fun close(name: String, all: Iterable<String>, limit: Int = 8): List<String> {
    val forms = forms(normalize(name)).filter { it.length >= 3 }
    val ids = all.toList()
    val holding = ids.filter { id -> forms.any { it in id } }
      .sortedWith(compareBy<String> { id -> if (forms.any { id.endsWith("_$it") || id.startsWith("${it}_") }) 0 else 1 }.thenBy { it.length }.thenBy { it })
    if (holding.isNotEmpty()) return holding.take(limit)
    val word = forms.firstOrNull() ?: return emptyList()
    return ids.map { it to distance(word, it) }.filter { it.second <= maxOf(2, word.length / 4) }.sortedBy { it.second }.take(limit).map { it.first }
  }

  private fun distance(a: String, b: String): Int {
    var previous = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
      val row = IntArray(b.length + 1)
      row[0] = i
      for (j in 1..b.length) row[j] = minOf(previous[j] + 1, row[j - 1] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
      previous = row
    }
    return previous[b.length]
  }
}
