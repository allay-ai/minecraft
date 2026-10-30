package chat.allay.core.tools

import chat.allay.core.Animator
import chat.allay.core.Config
import chat.allay.core.Lang
import chat.allay.core.Legacy
import chat.allay.core.PlayerAudience
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * What a way trail shows besides its particles: a boss bar that shrinks as the player gets there, and a line in the
 * action bar, each with an arrow that turns with the player's view, the blocks left, and how far up or down the spot
 * lies. The trail's particles show only ahead of the player, so with the spot behind them this tells them to turn
 * around. A running answer's status owns the action bar, and that line waits until it's gone; the boss bar stays.
 */
class TrailBar(private val player: PlayerAudience, private val lang: Lang, private val actionBar: Boolean, private val boss: Boss?) {
  /** A boss bar only the player sees, as the platform draws it; it shows on the first [show] and goes on [close]. */
  interface Boss {
    fun show(title: String, progress: Double)
    fun close()
  }

  /** The farthest the player has been from the spot since the trail began: the full bar. */
  private var farthest = 0.0
  private var progress = 1.0

  /**
   * The lines for a spot [dx], [dz] away from a player looking along [yaw], [blocks] away and [above] blocks up (down
   * when negative; null when unknown); with [name], a player or the spot's label.
   */
  fun show(yaw: Float, dx: Double, dz: Double, blocks: Long, name: String? = null, above: Int? = null) {
    val values = arrayOf("arrow" to arrow(yaw, dx, dz), "blocks" to blocks, "height" to height(above))
    // A label comes from the model: no color codes of its own in the bars.
    val shown = name?.replace(Regex("[&§]"), "")?.trim()?.take(32)?.ifEmpty { null }
    if (boss != null) {
      farthest = maxOf(farthest, blocks.toDouble(), 1.0)
      progress = (blocks / farthest).coerceIn(0.0, 1.0)
      val title = if (shown == null) lang.text("status.trail_boss", *values) else lang.text("status.trail_boss_to", *values, "name" to shown)
      boss.show(Legacy.color(title), progress)
    }
    if (!actionBar || Animator.showing(player.uuid)) return
    val text = if (shown == null) lang.text("status.trail", *values) else lang.text("status.trail_to", *values, "name" to shown)
    if (text.isNotEmpty()) player.actionBar(Legacy.color(text))
  }

  /** While a followed player is off the locator bar: why the trail went dark, before it ends after a while. */
  fun hidden(name: String) {
    val text = lang.text("status.trail_hidden", "name" to name)
    boss?.show(Legacy.color(text), progress)
    if (!actionBar || Animator.showing(player.uuid)) return
    if (text.isNotEmpty()) player.actionBar(Legacy.color(text))
  }

  /** Takes the bars away once the trail ends; the action bar only unless the status holds it by then. */
  fun clear() {
    boss?.close()
    if (actionBar && player.online && !Animator.showing(player.uuid)) player.actionBar("")
  }

  private fun height(above: Int?): String = when {
    above == null || abs(above) <= LEVEL -> ""
    above > 0 -> lang.text("status.trail_above", "blocks" to above)
    else -> lang.text("status.trail_below", "blocks" to -above)
  }

  companion object {
    /** Within this many blocks of height the spot is level with the player, as [TrailNotes] says on arrival. */
    private const val LEVEL = 4

    /** Boss bar colors, as the game names them; `point_to` takes them as `bar_color`. */
    val COLORS = listOf("pink", "blue", "red", "green", "yellow", "purple", "white")

    /** The color the model named, or null for the config's; anything else is the model's mistake to fix. */
    fun color(name: String?): String? = name?.trim()?.lowercase()?.ifEmpty { null }?.also {
      if (it !in COLORS) throw ToolError("bar_color must be one of ${COLORS.joinToString()}")
    }

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

    /**
     * The bars for [player]'s trail as `tools.point_to.bar` asks, the boss bar in [color] or the config's, made by
     * [boss]; null when the config wants neither.
     */
    fun of(config: Config, player: PlayerAudience, lang: Lang, color: String? = null, boss: (String) -> Boss): TrailBar? {
      val tools = config.tools
      if (!tools.trailBossBar && !tools.trailActionBar) return null
      return TrailBar(player, lang, tools.trailActionBar, if (tools.trailBossBar) boss(color ?: tools.trailColor) else null)
    }
  }
}
