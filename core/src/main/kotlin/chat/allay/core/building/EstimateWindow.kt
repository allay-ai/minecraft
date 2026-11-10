package chat.allay.core.building

import chat.allay.core.Lang
import chat.allay.core.Window

/**
 * The estimate of a previewed build as a window: what's short first, then what's there, the tools' uses, the water
 * bucket, what nobody can get, and what's free. Both platforms show the same; [name] says an item as the player reads it.
 */
object EstimateWindow {
  /** One material: how many the build needs and how many the player has, up to that. */
  data class Row(val item: String, val need: Int, val have: Int)

  /** The most rows a window lists; the rest go in one line. */
  private const val ROWS = 40

  /** In [creative] nothing is short: the window lists what the build is made of. */
  fun of(lang: Lang, title: String, rows: List<Row>, estimate: Materials.Estimate, unobtainable: Map<String, Int>, creative: Boolean = false, name: (String) -> String): Window {
    val short = if (creative) 0 else rows.sumOf { (it.need - it.have).coerceAtLeast(0) }
    val lines = mutableListOf(when {
      creative -> lang.text("previews.estimate.creative")
      short == 0 -> lang.text("previews.estimate.enough")
      else -> lang.text("previews.estimate.short", "count" to short)
    })
    val sorted = if (creative) rows.sortedByDescending { it.need } else rows.sortedWith(compareByDescending<Row> { it.need - it.have }.thenByDescending { it.need })
    sorted.take(ROWS).forEach { row ->
      val key = when {
        creative -> "previews.estimate.line_creative"
        row.have >= row.need -> "previews.estimate.line_ok"
        else -> "previews.estimate.line_short"
      }
      lines += lang.text(key, "item" to name(row.item), "have" to row.have, "need" to row.need)
    }
    if (sorted.size > ROWS) lines += lang.text("previews.estimate.more", "count" to sorted.size - ROWS)
    estimate.tools.forEach { (tool, uses) -> lines += lang.text("previews.estimate.$tool", "count" to uses) }
    if (estimate.water) lines += lang.text("previews.estimate.water")
    if (unobtainable.isNotEmpty()) {
      lines += lang.text("previews.estimate.unobtainable", "blocks" to unobtainable.entries.joinToString(", ") { (block, n) -> "${name(block)} ×$n" })
    }
    if (estimate.free.isNotEmpty()) lines += lang.text("previews.estimate.free", "count" to estimate.free.values.sum())
    // One paragraph a line: the dialog keeps them apart and wraps each.
    return Window(lang.text("previews.estimate.title", "title" to title), lines, emptyList(), 1, Window.BODY,
      exit = Window.button(lang, "windows.close", "windows.close_hover", "/allay close"))
  }
}
