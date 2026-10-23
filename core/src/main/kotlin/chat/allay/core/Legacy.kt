package chat.allay.core

/**
 * Texts with the game's `§` color codes, written with `&` in `lang/` and `config.yml`. Every platform draws them: Bukkit
 * as they are, the mods through the game's own legacy parser. The rules match Bukkit's `ChatColor`, so a text looks the
 * same on every platform.
 */
object Legacy {
  private const val CODES = "0123456789AaBbCcDdEeFfKkLlMmNnOoRrXx"
  private const val COLORS = "0123456789abcdef"
  private const val FORMATS = "klmno"
  private val STRIP = Regex("(?i)§[0-9A-FK-ORX]")

  /** Turns `&` codes into `§` ones, as Bukkit's `translateAlternateColorCodes('&', …)`. */
  fun color(text: String): String {
    val chars = text.toCharArray()
    for (i in 0 until chars.size - 1) {
      if (chars[i] == '&' && CODES.indexOf(chars[i + 1]) > -1) {
        chars[i] = '§'
        chars[i + 1] = chars[i + 1].lowercaseChar()
      }
    }
    return String(chars)
  }

  /** Stands in for a player's own words in a template; see [withText]. */
  const val SLOT = "\u0000"

  /** Colors the template, then puts the player's words in place of [SLOT], so an `&` they typed stays as typed. */
  fun withText(template: String, text: String) = color(template).replace(SLOT, text)

  /** The text without its codes, for the log. */
  fun strip(text: String) = STRIP.replace(text, "")

  /**
   * The color and formats in force at the end of [text], as Bukkit's `getLastColors`: so a piece drawn after it, like a
   * button in an answer, keeps the answer's color.
   */
  fun lastColors(text: String): String {
    var result = ""
    var index = text.length - 1
    while (index > -1) {
      if (text[index] == '§' && index < text.length - 1) {
        // A hex color is §x followed by six §-digit pairs.
        if (index > 11 && text[index - 12] == '§' && text[index - 11].lowercaseChar() == 'x') {
          return text.substring(index - 12, index + 2) + result
        }
        val code = text[index + 1].lowercaseChar()
        when {
          code in COLORS || code == 'r' -> return "§$code$result"
          code in FORMATS -> result = "§$code$result"
        }
      }
      index--
    }
    return result
  }

  /** A stretch of text in one style: [color] is `0xRRGGBB` or null, [link] an address the stretch opens. */
  data class Run(
    val text: String,
    val color: Int? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underlined: Boolean = false,
    val strikethrough: Boolean = false,
    val obfuscated: Boolean = false,
    val link: String? = null,
  )

  /** The sixteen colors by their code, as the game draws them. */
  private val PALETTE = intArrayOf(
    0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
    0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF,
  )

  /** The code of one of the sixteen colors, or null for any other. */
  fun code(rgb: Int): Char? = PALETTE.indexOf(rgb).takeIf { it >= 0 }?.let { COLORS[it] }

  /** An address as BungeeCord's `fromLegacyText` finds one in a word: `allay.chat`, `https://allay.chat/docs`. */
  private val URL = Regex("^(?:(https?)://)?([-\\w_.]{2,}\\.[a-z]{2,4})(/\\S*)?$")

  /**
   * [text] split into styled stretches, as Bukkit's `fromLegacyText` reads it: a color resets the formats, `§r` resets
   * everything, `§x` and six digits is a hex color. With [links], words that look like addresses open in the browser.
   * The mods draw lines from these, so a text looks the same as on Bukkit.
   */
  fun runs(text: String, links: Boolean = false): List<Run> {
    val result = mutableListOf<Run>()
    var style = Run("")
    val piece = StringBuilder()
    fun flush() {
      if (piece.isEmpty()) return
      val stretch = piece.toString()
      piece.clear()
      if (!links) {
        result += style.copy(text = stretch)
        return
      }
      // Words and the spaces between them, so an address becomes a stretch of its own.
      Regex("\\S+|\\s+").findAll(stretch).forEach { word ->
        val match = URL.find(word.value)
        val link = match?.let { if (it.groupValues[1].isEmpty()) "http://${word.value}" else word.value }
        val last = result.lastOrNull()
        if (link == null && last != null && last.link == null && last.copy(text = "") == style.copy(text = "")) result[result.lastIndex] = last.copy(text = last.text + word.value)
        else result += style.copy(text = word.value, link = link)
      }
    }
    var index = 0
    while (index < text.length) {
      val char = text[index]
      if (char != '§' || index == text.length - 1) {
        piece.append(char)
        index++
        continue
      }
      val code = text[index + 1].lowercaseChar()
      if (code == 'x' && (0 until 6).all { text.getOrNull(index + 2 + it * 2) == '§' && text.getOrNull(index + 3 + it * 2)?.let { d -> d.lowercaseChar() in COLORS } == true }) {
        flush()
        val hex = (0 until 6).map { text[index + 3 + it * 2] }.joinToString("")
        style = Run("", color = hex.toInt(16))
        index += 14
        continue
      }
      val format: ((Run) -> Run)? = when (code) {
        'l' -> { run -> run.copy(bold = true) }
        'o' -> { run -> run.copy(italic = true) }
        'n' -> { run -> run.copy(underlined = true) }
        'm' -> { run -> run.copy(strikethrough = true) }
        'k' -> { run -> run.copy(obfuscated = true) }
        else -> null
      }
      when {
        code in COLORS -> {
          flush()
          style = Run("", color = PALETTE[COLORS.indexOf(code)])
        }
        code == 'r' -> {
          flush()
          style = Run("")
        }
        format != null -> {
          flush()
          style = format(style)
        }
        // Not a code: the game would show it, so the stretch keeps it.
        else -> piece.append(char).append(text[index + 1])
      }
      index += 2
    }
    flush()
    return result
  }
}
