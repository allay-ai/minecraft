package chat.allay.core.tools

import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * The points of a way trail's line, the same on every platform: half a block apart, from low in front of the player
 * toward the spot, twelve blocks at most. It starts [CLEAR] blocks ahead and well below the eyes ([start]), so nothing
 * flies into the camera, not on flat ground nor in a boat on still water. Within reach the line ends at the spot.
 * Beyond it, the line keeps level enough to stay in view, never steeper than one in four, and up a hill it climbs over
 * the ground rather than into it, as the player will.
 */
object TrailLine {
  const val STEP = 0.5
  private const val STEPS = 24
  /** How far ahead of the player the first point shows, in blocks. */
  const val CLEAR = 2.0
  /** How high over the player's feet the line runs: about their knees. */
  private const val HEIGHT = 0.5
  /** How far under the eyes it stays at least, for a pose that brings them low: gliding, swimming, crawling. */
  private const val UNDER_EYES = 1.0
  private const val SLOPE = 0.25
  /** How far the line may rise over ground in one step; past that it's a wall or a cave's roof, and it stays put. */
  private const val CLIMB = 3.0

  data class Point(val x: Double, val y: Double, val z: Double)

  /** Where the line starts for a player whose feet are at [x], [feet], [z] and whose eyes are at [eyes]. */
  fun start(x: Double, feet: Double, eyes: Double, z: Double) = Point(x, minOf(feet + HEIGHT, eyes - UNDER_EYES), z)

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
    return (1..steps).mapNotNull { n ->
      val x = from.x + sx * n
      val y = from.y + sy * n
      val z = from.z + sz * n
      // Near the spot the line goes straight there, through a wall if it must: the column marks it.
      if (!near) {
        var up = lift
        while (up <= lift + CLIMB && solid(floor(x).toInt(), floor(y + up).toInt(), floor(z).toInt())) up += STEP
        if (up <= lift + CLIMB) lift = up
      }
      Point(x, y + lift, z).takeIf { n * STEP >= CLEAR }
    }
  }
}
