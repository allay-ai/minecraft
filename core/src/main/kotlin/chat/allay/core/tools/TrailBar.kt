package chat.allay.core.tools

import chat.allay.core.Animator
import chat.allay.core.Config
import chat.allay.core.Lang
import chat.allay.core.Legacy
import chat.allay.core.PlayerAudience
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * The way trail's line in the action bar: an arrow that turns with the player's view and the blocks left. The trail's
 * particles show only ahead of the player, so with the spot behind them this is all that tells them to turn around.
 * A running answer's status owns the bar; the line waits until it's gone.
 */
class TrailBar(private val player: PlayerAudience, private val lang: Lang) {
  /** The line for a spot [dx], [dz] away from a player looking along [yaw], [blocks] away; with [name], a player or the spot's label. */
  fun show(yaw: Float, dx: Double, dz: Double, blocks: Long, name: String? = null) {
    if (Animator.showing(player.uuid)) return
    val arrow = arrow(yaw, dx, dz)
    // A label comes from the model: no color codes of its own in the bar.
    val shown = name?.replace(Regex("[&§]"), "")?.trim()?.take(32)?.ifEmpty { null }
    val text = if (shown == null) lang.text("status.trail", "arrow" to arrow, "blocks" to blocks)
      else lang.text("status.trail_to", "arrow" to arrow, "name" to shown, "blocks" to blocks)
    if (text.isNotEmpty()) player.actionBar(Legacy.color(text))
  }

  /** While a followed player is off the locator bar: why the trail went dark, before it ends after a while. */
  fun hidden(name: String) {
    if (Animator.showing(player.uuid)) return
    val text = lang.text("status.trail_hidden", "name" to name)
    if (text.isNotEmpty()) player.actionBar(Legacy.color(text))
  }

  /** Takes the line away once the trail ends, unless the status holds the bar by then. */
  fun clear() {
    if (player.online && !Animator.showing(player.uuid)) player.actionBar("")
  }

  companion object {
    private val ARROWS = listOf("↑", "↗", "→", "↘", "↓", "↙", "←", "↖")

    /**
     * The arrow toward a spot [dx], [dz] away for a player looking along [yaw], the game's degrees (0 south, 90 west):
     * ↑ straight ahead, → turn right, ↓ behind. Eight of them, since the game's font tells no finer turns apart.
     */
    fun arrow(yaw: Float, dx: Double, dz: Double): String = ARROWS[side(yaw, dx, dz)]

    private val TURNS = listOf("ahead", "ahead_right", "right", "behind_right", "behind", "behind_left", "left", "ahead_left")

    /** The same as [arrow] in words for the model, so it can tell the player which way to turn: ahead, right, behind… */
    fun turn(yaw: Float, dx: Double, dz: Double): String = TURNS[side(yaw, dx, dz)]

    private fun side(yaw: Float, dx: Double, dz: Double): Int {
      val toward = Math.toDegrees(atan2(-dx, dz))
      val turn = ((toward - yaw) % 360 + 540) % 360 - 180
      return Math.floorMod((turn / 45).roundToInt(), 8)
    }

    /** A bar for [player]'s trail, or null when `tools.point_to.action_bar` is off. */
    fun of(config: Config, player: PlayerAudience, lang: Lang): TrailBar? = if (config.tools.trailBar) TrailBar(player, lang) else null
  }
}
