package chat.allay.core

/**
 * A window the game draws over the screen (a dialog, since 1.21.6): a title, paragraphs, text fields, and buttons in
 * [columns], with [exit] at the bottom. Texts may carry `&` codes; each platform draws [ready] with its own API.
 */
data class Window(
  val title: String,
  val paragraphs: List<String>,
  val buttons: List<Button>,
  val columns: Int,
  val width: Int,
  val inputs: List<Input> = emptyList(),
  val exit: Button? = null,
  /** Whether addresses in the paragraphs, like allay.chat, become links; off where names like `provider.mode` would. */
  val links: Boolean = false,
) {
  /**
   * A button that runs [command] as the player (with its `/`) or opens [url]; [width] in pixels. A command may name
   * the window's fields as `$(key)`, and the game puts what the player typed there.
   */
  data class Button(val label: String, val tooltip: String, val width: Int, val command: String? = null, val url: String? = null) {
    val template get() = command?.contains("$(") == true
  }

  /** A text field the buttons' commands read as `$(key)`. */
  data class Input(val key: String, val label: String, val maxLength: Int, val width: Int, val initial: String = "")

  /**
   * The window as the game should draw it, colored. Gray reads poorly over the blurred game, so a window's paragraphs
   * are white, and what chat shows in white turns yellow. The game's dialog needs at least one button, so a window
   * without any gets [exit] as its button.
   */
  fun ready(): Window {
    val colored = copy(title = Legacy.color(title), paragraphs = paragraphs.map { brighter(Legacy.color(it)) })
    return if (buttons.isEmpty() && exit != null) colored.copy(buttons = listOf(exit), exit = null) else colored
  }

  companion object {
    /** Widths in pixels: the text, and a button or field across it. */
    const val BODY = 400
    const val WIDE = 300

    /** A button from [lang]'s texts: [label] and [hover] are keys. */
    fun button(lang: Lang, label: String, hover: String, command: String? = null, url: String? = null, width: Int = WIDE, vararg values: Pair<String, Any>) =
      Button(Legacy.color(lang.text(label, *values)), Legacy.color(lang.text(hover, *values)), width, command, url)

    private fun brighter(text: String) = text.replace("§f", "§e").replace("§7", "§f").replace("§8", "§7")
  }
}
