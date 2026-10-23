package chat.allay.core

/**
 * A window the game draws over the screen (a dialog, since 1.21.6): a title, paragraphs, and buttons in [columns]. Texts
 * come colored with `§` codes; each platform draws the window with its own API.
 */
data class Window(val title: String, val paragraphs: List<String>, val buttons: List<Button>, val columns: Int, val width: Int) {
  /** A button that runs [command] as the player (with its `/`) or opens [url]; [width] in pixels. */
  data class Button(val label: String, val tooltip: String, val width: Int, val command: String? = null, val url: String? = null)
}
