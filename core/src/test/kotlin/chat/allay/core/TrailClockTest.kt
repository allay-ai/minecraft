package chat.allay.core

import chat.allay.core.tools.TrailClock
import kotlin.test.Test
import kotlin.test.assertEquals

/** [TrailClock]: how long a way trail lasts, in pulses, two a second. */
class TrailClockTest {
  private fun tools(yaml: String = "") = Config(YamlConfig(YamlConfig.parse("server:\n  url: x\n$yaml")), "test").tools

  /** Pulses until the clock stops, with the player [distance] blocks off at each. */
  private fun TrailClock.lasts(distance: (Int) -> Double): Int = generateSequence(0) { it + 1 }.first { !tick(distance(it)) }

  @Test fun `a named length holds, walking or not`() {
    assertEquals(30, TrailClock.fixed(15).lasts { 500.0 })
    assertEquals(30, TrailClock.fixed(15).lasts { 500.0 - it })
  }

  @Test fun `a trail nobody timed lasts while the player gets closer`() {
    // A base 1200 blocks off at a walk, 2 blocks a pulse: 10 minutes, the cap, never the idle limit.
    assertEquals(1200, tools().trailClock(null).lasts { 2400.0 - 2.0 * it })
  }

  @Test fun `and goes out a minute after they stop`() {
    // 20 pulses of walking, then standing still: 120 pulses of patience after the last step.
    assertEquals(20 + 120, tools().trailClock(null).lasts { if (it < 20) 300.0 - 3.0 * it else 243.0 })
  }

  @Test fun `a step around isn't progress`() {
    // Pacing back and forth by a block never counts.
    assertEquals(1 + 120, tools().trailClock(null).lasts { 300.0 + (it % 2) })
  }

  @Test fun `a detour doesn't end it while the way goes on`() {
    // Away for 20 seconds, around a ravine, then back and on toward the spot.
    val far = { it: Int -> if (it < 40) 300.0 + it else 340.0 - 3.0 * (it - 40) }
    assertEquals(1200, tools().trailClock(null).lasts(far))
  }

  @Test fun `idle_seconds and max_seconds come from the config`() {
    assertEquals(1 + 40, tools("tools:\n  point_to:\n    idle_seconds: 20\n").trailClock(null).lasts { 100.0 })
    assertEquals(240, tools("tools:\n  point_to:\n    max_seconds: 120\n").trailClock(null).lasts { 5000.0 - 3.0 * it })
  }
}
