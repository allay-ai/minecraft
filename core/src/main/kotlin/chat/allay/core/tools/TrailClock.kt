package chat.allay.core.tools

/**
 * How long a way trail lasts, counted in its pulses, two a second. A length the model named holds exactly. Without
 * one, the trail lasts while the player gets closer: it ends once they haven't for a while, so a detour, a fight, or a
 * mine on the way keeps it and a forgotten one goes out. Either way it ends on arrival, which the trail itself checks.
 */
class TrailClock private constructor(private val pulses: Int, private val patience: Int?) {
  private var pulse = 0
  private var best = Double.MAX_VALUE
  private var idle = 0

  /** Counts one pulse with the player [distance] blocks from where the trail leads; false once its time is up. */
  fun tick(distance: Double): Boolean {
    if (pulse++ >= pulses) return false
    val patience = patience ?: return true
    if (distance < best - PROGRESS) {
      best = distance
      idle = 0
    } else if (++idle > patience) return false
    return true
  }

  companion object {
    const val PULSES = 2
    /** Blocks closer that count as getting there: less is a step around, not the way. */
    private const val PROGRESS = 2.0

    /** Exactly [seconds]. */
    fun fixed(seconds: Int) = TrailClock(seconds * PULSES, null)

    /** While the player gets closer, ending [idleSeconds] after they last did, and after [maxSeconds] at most. */
    fun walking(idleSeconds: Int, maxSeconds: Int) = TrailClock(maxSeconds * PULSES, idleSeconds * PULSES)
  }
}
