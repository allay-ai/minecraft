package chat.allay.core

import chat.allay.core.tools.TrailLine
import chat.allay.core.tools.TrailLine.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [TrailLine]: the way trail's line stays in view and over the ground. */
class TrailLineTest {
  private val air = { _: Int, _: Int, _: Int -> false }
  private val from = Point(0.5, 65.1, 0.5)

  @Test fun `a near spot gets a line that ends there`() {
    val points = TrailLine.points(from, Point(0.5, 65.1, 6.5), air)
    assertEquals(12, points.size)
    assertEquals(6.5, points.last().z, 1e-9)
  }

  @Test fun `a far spot far below doesn't dive into the ground`() {
    // A base 80 blocks down and 200 away: over twelve blocks the line drops three at most.
    val points = TrailLine.points(from, Point(0.5, -15.0, 200.5), air)
    assertEquals(24, points.size)
    assertTrue(from.y - points.last().y <= 3.0 + 1e-9, "the line drops ${from.y - points.last().y}")
  }

  @Test fun `up a hill the line climbs over the ground`() {
    // Ground up to y 64 here, then a slope that rises a block for every two blocks from z 4 on.
    val hill = { _: Int, y: Int, z: Int -> y <= 64 + maxOf(0, (z - 4) / 2) }
    val points = TrailLine.points(from, Point(0.5, 65.1, 300.5), hill)
    points.forEach { assertTrue(!hill(0, kotlin.math.floor(it.y).toInt(), kotlin.math.floor(it.z).toInt()), "a point in the ground at $it") }
  }

  @Test fun `a wall higher than three blocks doesn't lift it into the sky`() {
    val wall = { _: Int, y: Int, z: Int -> z in 5..6 && y < 90 }
    val points = TrailLine.points(from, Point(0.5, 65.1, 300.5), wall)
    assertTrue(points.all { it.y < 66.0 }, "the line rose to ${points.maxOf { it.y }}")
  }
}
