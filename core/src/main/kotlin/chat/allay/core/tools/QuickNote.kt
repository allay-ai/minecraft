package chat.allay.core.tools

/**
 * `/allay memory <text>` without the assistant. Two or three whole numbers in the text are a place, so the memory gets
 * one; the dimension is the one the text names (“в аду”, “the end”), or none when it names none or several.
 */
object QuickNote {
  class Spot(val x: Int, val y: Int?, val z: Int, val dimension: String?)

  // A minus may come as a hyphen, a minus sign, or a dash, as phones and chats type it.
  private val NUMBER = Regex("""(?<![\p{L}\d.,])[-−–]?\d{1,8}(?![\p{L}\d]|[.,]\d)""")
  private val NAMES = mapOf(
    "the_nether" to Regex("""(?iU)\b(ад|ада|аду|аде|адом|незер\w*|нижн\w*|nether\w*|hell)\b"""),
    "the_end" to Regex("""(?iU)\b(энд\w*|енд\w*|the end)\b"""),
    "overworld" to Regex("""(?iU)\b(обычн\w*|верхн\w*|overworld|oberwelt)\b"""),
  )

  /** The place in [text], or null when it holds no coordinates. */
  fun spot(text: String): Spot? {
    val numbers = NUMBER.findAll(text).map { it.value.replace('−', '-').replace('–', '-').toInt() }.toList()
    val dimension = NAMES.filterValues { it.containsMatchIn(text) }.keys.singleOrNull()
    return when (numbers.size) {
      3 -> Spot(numbers[0], numbers[1], numbers[2], dimension)
      2 -> Spot(numbers[0], null, numbers[1], dimension)
      else -> null
    }
  }
}
