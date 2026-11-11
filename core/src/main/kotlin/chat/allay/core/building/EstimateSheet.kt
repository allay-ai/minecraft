package chat.allay.core.building

import chat.allay.core.Lang

/**
 * The estimate of a previewed build as a chest window, what both platforms draw: a top row that sums it up (what's
 * short in all, the hoe's and shovel's uses, the water bucket, what nobody can get, the free heads, pages), and under
 * it each material as its item, what's short first, with the numbers in its tooltip. The platform knows the items.
 */
object EstimateSheet {
  /** One material: how many the build needs, how many the player has up to that, and how many more lie in the ender chest. */
  data class Row(val item: String, val need: Int, val have: Int, val ender: Int = 0) {
    val short get() = (need - have).coerceAtLeast(0)
  }

  /** The window's slots: the top row's pictures, and the materials below them, a page at a time. */
  const val SUMMARY = 0
  const val HOE = 2
  const val SHOVEL = 3
  const val WATER = 4
  const val UNOBTAINABLE = 5
  const val FREE = 6
  const val PREVIOUS = 7
  const val NEXT = 8
  const val FIRST = 9
  const val PAGE = 45

  /** What's short first, the most short first; in [creative] the most used first, since nothing is short. */
  fun sorted(rows: List<Row>, creative: Boolean): List<Row> =
    if (creative) rows.sortedByDescending { it.need } else rows.sortedWith(compareByDescending<Row> { it.short }.thenByDescending { it.need })

  fun pages(rows: List<Row>) = maxOf(1, (rows.size + PAGE - 1) / PAGE)

  /** The summary's name and lore: whether everything is here, and what the ender chest holds besides. */
  fun summary(lang: Lang, rows: List<Row>, creative: Boolean): Pair<String, List<String>> {
    val short = rows.sumOf { it.short }
    val ender = rows.sumOf { minOf(it.ender, it.short) }
    val name = when {
      creative -> lang.text("previews.estimate.creative")
      short == 0 -> lang.text("previews.estimate.enough")
      else -> lang.text("previews.estimate.short", "count" to short)
    }
    return name to if (!creative && ender > 0) listOf(lang.text("previews.estimate.ender", "count" to ender)) else emptyList()
  }

  /** A material's tooltip under its name: needed, had, short or enough, and the ender chest's share. */
  fun lore(lang: Lang, row: Row, creative: Boolean): List<String> {
    if (creative) return listOf(lang.text("previews.estimate.need", "count" to row.need))
    val lines = mutableListOf(lang.text("previews.estimate.need", "count" to row.need), lang.text("previews.estimate.have", "count" to row.have))
    lines += if (row.short > 0) lang.text("previews.estimate.missing", "count" to row.short) else lang.text("previews.estimate.enough_item")
    if (row.short > 0 && row.ender > 0) lines += lang.text("previews.estimate.ender_item", "count" to minOf(row.ender, row.short))
    return lines
  }

  /** How many a material's picture shows: what's short, or in creative or when it's all there, what it takes. */
  fun amount(row: Row, creative: Boolean) = (if (!creative && row.short > 0) row.short else row.need).coerceIn(1, 64)

  fun tool(lang: Lang, tool: String, uses: Int) = lang.text("previews.estimate.$tool", "count" to uses)

  fun water(lang: Lang) = lang.text("previews.estimate.water")

  /** What nobody can get, each with its count; [name] says a block as the player reads it. */
  fun unobtainable(lang: Lang, blocks: Map<String, Int>, name: (String) -> String) =
    lang.text("previews.estimate.unobtainable", "blocks" to blocks.entries.joinToString(", ") { (block, n) -> "${name(block)} ×$n" })

  fun free(lang: Lang, count: Int) = lang.text("previews.estimate.free", "count" to count)

  fun page(lang: Lang, page: Int, pages: Int) = lang.text("previews.estimate.page", "page" to page + 1, "pages" to pages)
}
