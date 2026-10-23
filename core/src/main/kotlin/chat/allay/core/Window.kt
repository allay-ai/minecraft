package chat.allay.core

/**
 * A window the game draws over the screen (a dialog, since 1.21.6): a title, paragraphs, text fields, and buttons in
 * [columns], with [exit] at the bottom. Texts come colored with `§` codes; each platform draws the window with its own API.
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
}
