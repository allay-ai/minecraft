package chat.allay.core.tools

import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * The points of a way trail's line, the same on every platform: half a block apart, from the player's chest toward
 * the spot, twelve blocks at most. Within reach the line ends at the spot. Beyond it, the line keeps level enough to
 * stay in view, never steeper than one in four, and up a hill it climbs over the ground rather than into it, as the
 * player will.
 */
object TrailLine {
  const val STEP = 0.5
  private const val STEPS = 24
  private const val SLOPE = 0.25
  /** How far the line may rise over ground in one step; past that it's a wall or a cave's roof, and it stays put. */
  private const val CLIMB = 3.0

  data class Point(val x: Double, val y: Double, val z: Double)

  /** The line from [from] toward [to]; [solid] says whether the block at x, y, z hides what's in it. */
  fun points(from: Point, to: Point, solid: (Int, Int, Int) -> Boolean): List<Point> {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val dz = to.z - from.z
    val length = sqrt(dx * dx + dy * dy + dz * dz)
    if (length < 1) return emptyList()
    val steps = minOf(STEPS, (length / STEP).toInt())
    val flat = hypot(dx, dz)
    val near = steps * STEP >= length - STEP || flat < 1
    val (sx, sy, sz) = if (near) Triple(dx / length * STEP, dy / length * STEP, dz / length * STEP) else {
      val rise = (dy / flat).coerceIn(-SLOPE, SLOPE)
      val norm = sqrt(1 + rise * rise)
      Triple(dx / flat / norm * STEP, rise / norm * STEP, dz / flat / norm * STEP)
    }
    var lift = 0.0
    return (1..steps).map { n ->
      val x = from.x + sx * n
      val y = from.y + sy * n
      val z = from.z + sz * n
      // Near the spot the line goes straight there, through a wall if it must: the column marks it.
      if (!near) {
        var up = lift
        while (up <= lift + CLIMB && solid(floor(x).toInt(), floor(y + up).toInt(), floor(z).toInt())) up += STEP
        if (up <= lift + CLIMB) lift = up
      }
      Point(x, y + lift, z)
    }
  }
}
