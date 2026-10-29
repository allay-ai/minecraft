package chat.allay.core.tools

import chat.allay.core.Audience
import chat.allay.core.Lang
import chat.allay.core.send

/** What a way trail tells the player as it ends, the same on every platform, so no ending goes without a word. */
object TrailNotes {
  /** Within this many blocks of height, the spot is where they stand. */
  private const val LEVEL = 4

  /** The player reached the spot across the ground; [above] blocks up (down when negative) it may still lie. */
  fun arrived(lang: Lang, to: Audience, above: Int) = when {
    above > LEVEL -> lang.send(to, "messages.arrived_above", "blocks" to above)
    above < -LEVEL -> lang.send(to, "messages.arrived_below", "blocks" to -above)
    else -> lang.send(to, "messages.arrived")
  }

  /** The trail ran out with [left] blocks to go, or the player left its dimension (null). */
  fun ended(lang: Lang, to: Audience, left: Long?) =
    if (left == null) lang.send(to, "messages.trail_other_world") else lang.send(to, "messages.trail_ended", "blocks" to left)
}
