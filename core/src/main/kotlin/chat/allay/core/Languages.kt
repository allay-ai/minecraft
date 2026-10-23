package chat.allay.core

/** `/allay language` and the settings window: the player picks the language Allay speaks to them, or leaves it to Allay. */
class Languages(private val locales: Locales, private val settings: PlayerSettings) {
  /** Each bundled language by its own name; a file a server added shows its code. */
  fun name(code: String): String = NAMES[code] ?: code

  /** The languages to choose from, the bundled ones first. */
  fun codes(): List<String> = locales.codes.sortedWith(compareBy({ it !in Texts.BUNDLED }, { Texts.BUNDLED.indexOf(it) }, { it }))

  /** The player's own choice, or null when Allay picks. */
  fun chosen(player: PlayerAudience): String? = settings.of(player.uuid).language?.takeIf { it in locales.codes }

  /** Sets the player's language, or with null hands the choice back to Allay; they hear it in the language they get. */
  fun choose(player: PlayerAudience, code: String?) {
    settings.update(player.uuid) { it.copy(language = code) }
    locales.chose(player)
    val lang = locales.of(player)
    if (code == null) lang.send(player, "messages.language_auto", "language" to name(lang.code))
    else lang.send(player, "messages.language_set", "language" to name(code))
  }

  /** `/allay language <code|auto>`; false when there's no such language. */
  fun choose(player: PlayerAudience, typed: String): Boolean {
    val wanted = LocaleResolver.normalize(typed)
    if (wanted == "auto") return true.also { choose(player, null) }
    val code = locales.codes.firstOrNull { it == wanted } ?: locales.codes.firstOrNull { it == wanted.substringBefore('_') } ?: return false
    choose(player, code)
    return true
  }

  private companion object {
    val NAMES = mapOf("en" to "English", "ru" to "Русский", "es" to "Español", "pt" to "Português", "de" to "Deutsch")
  }
}
