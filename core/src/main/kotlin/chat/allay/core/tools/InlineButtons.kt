package chat.allay.core.tools

/**
 * Buttons the model writes into its answer, where they belong in the text: `<button action="ask" question="…">Yes</button>`
 * (since 6.9; the `add_buttons` tool before). [parse] splits an answer into text and tags; the platform turns each tag
 * into a [Button] and draws it in place, or draws its label as plain text when it can't.
 */
object InlineButtons {
  /** More would crowd the answer. */
  const val MAX = 8
  const val MAX_LABEL = 24

  sealed interface Part {
    data class Text(val text: String) : Part

    /** A tag: its attributes, unescaped, and the label between the tags. */
    data class Tag(val label: String, val attributes: Map<String, String>) : Part
  }

  private val TAG = Regex("""<button((?:\s+[a-zA-Z_]+\s*=\s*"[^"]*")*)\s*>([^<\n]{0,64})</button>""")
  private val ATTRIBUTE = Regex("""([a-zA-Z_]+)\s*=\s*"([^"]*)"""")

  /**
   * The answer as text and tags in order. A tag past [MAX] stays as its label; a broken tag (no closing, a newline in
   * the label) stays as written, since the model can't be asked again.
   */
  fun parse(answer: String): List<Part> {
    val parts = mutableListOf<Part>()
    var at = 0
    var count = 0
    TAG.findAll(answer).forEach { match ->
      if (match.range.first > at) parts += Part.Text(answer.substring(at, match.range.first))
      val label = unescape(match.groupValues[2]).trim().take(MAX_LABEL)
      parts += if (++count > MAX) Part.Text(label) else Part.Tag(label, ATTRIBUTE.findAll(match.groupValues[1]).associate { it.groupValues[1].lowercase() to unescape(it.groupValues[2]) })
      at = match.range.last + 1
    }
    if (at < answer.length) parts += Part.Text(answer.substring(at))
    return parts
  }

  /** The answer with every tag replaced by its label: what the console and the log read. */
  fun plain(answer: String): String = parse(answer).joinToString("") { if (it is Part.Tag) it.label else (it as Part.Text).text }

  private fun unescape(text: String) = text.replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
}
