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
}
