package chat.allay.core

import chat.allay.core.tools.TrailBar
import kotlin.test.Test
import kotlin.test.assertEquals

/** [TrailBar.arrow] in the game's terms: yaw 0 looks south (+z), 90 west (−x), 180 north, −90 east. */
class TrailBarTest {
  @Test fun `a spot ahead is up, behind is down`() {
    assertEquals("↑", TrailBar.arrow(0f, 0.0, 10.0))
    assertEquals("↓", TrailBar.arrow(0f, 0.0, -10.0))
    assertEquals("↑", TrailBar.arrow(180f, 0.0, -10.0))
  }

  @Test fun `looking south, west is on the right and east on the left`() {
    assertEquals("→", TrailBar.arrow(0f, -10.0, 0.0))
    assertEquals("←", TrailBar.arrow(0f, 10.0, 0.0))
    assertEquals("↗", TrailBar.arrow(0f, -10.0, 10.0))
    assertEquals("↙", TrailBar.arrow(0f, 10.0, -10.0))
  }

  @Test fun `any yaw the game reports works, past a full turn too`() {
    assertEquals("↑", TrailBar.arrow(-90f, 10.0, 0.0))
    assertEquals("↑", TrailBar.arrow(270f, 10.0, 0.0))
    assertEquals("↑", TrailBar.arrow(720f, 0.0, 10.0))
    assertEquals("↓", TrailBar.arrow(-540f, 0.0, 10.0))
  }

  @Test fun `each arrow covers 45 degrees around its own`() {
    assertEquals("↑", TrailBar.arrow(22f, 0.0, 10.0))
    assertEquals("↖", TrailBar.arrow(23f, 0.0, 10.0))
  }
}
