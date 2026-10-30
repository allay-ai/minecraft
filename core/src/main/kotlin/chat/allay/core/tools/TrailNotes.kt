package chat.allay.core.tools

import chat.allay.core.Clicks
import chat.allay.core.Lang
import chat.allay.core.Legacy
import chat.allay.core.PlayerAudience
import chat.allay.core.send

/** What a way trail tells the player as it ends, the same on every platform, so no ending goes without a word. */
object TrailNotes {
  /** Within this many blocks of height, the spot is where they stand. */
  private const val LEVEL = 4

  /** The player reached the spot across the ground; [above] blocks up (down when negative) it may still lie. */
  fun arrived(lang: Lang, to: PlayerAudience, above: Int) = when {
    above > LEVEL -> lang.send(to, "messages.arrived_above", "blocks" to above)
    above < -LEVEL -> lang.send(to, "messages.arrived_below", "blocks" to -above)
    else -> lang.send(to, "messages.arrived")
  }

  /**
   * The trail went out with [left] blocks to go, or the player left its dimension (null). A button under it shows the
   * rest of the way without another question, which would cost the server.
   */
  fun ended(lang: Lang, to: PlayerAudience, left: Long?, clicks: Clicks?, x: Int, y: Int, z: Int, dimension: String) {
    val text = if (left == null) lang.text("messages.trail_other_world") else lang.text("messages.trail_ended", "blocks" to left)
    again(to, text, clicks, Button.Point(lang.text("buttons.continue_way"), x, y, z, dimension))
  }

  /** A trail to [name] ran out of time while they were still on the locator bar. */
  fun followEnded(lang: Lang, to: PlayerAudience, name: String, clicks: Clicks?) =
    again(to, lang.text("messages.trail_follow_ended", "name" to name), clicks, Button.Follow(lang.text("buttons.continue_way"), name))

  private fun again(to: PlayerAudience, text: String, clicks: Clicks?, button: Button) {
    if (text.isEmpty()) return
    if (clicks == null) to.send(Legacy.color(text)) else clicks.note(to, text, listOf(button))
  }
}
