package chat.allay.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** [Config.Tools.trailSeconds]: a way trail to a spot lasts long enough to walk there, within the config's bounds. */
class TrailSecondsTest {
  private fun tools(yaml: String = "") = Config(YamlConfig(YamlConfig.parse("server:\n  url: x\n$yaml")), "test").tools

  @Test fun `a near spot gets the default`() {
    assertEquals(30, tools().trailSeconds(12.0))
  }

  @Test fun `a far spot gets time to walk there`() {
    // A base 300 blocks off went dark after 30 seconds, halfway there (6.22.0).
    assertEquals(150, tools().trailSeconds(300.0))
    assertEquals(51, tools().trailSeconds(101.0))
  }

  @Test fun `never past max_seconds`() {
    assertEquals(600, tools().trailSeconds(5000.0))
    assertEquals(120, tools("tools:\n  point_to:\n    max_seconds: 120\n").trailSeconds(5000.0))
  }
}
