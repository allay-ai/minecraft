package chat.allay.core

import chat.allay.core.tools.Death
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [Death.scrub]: the history never names a player the victim may not learn about. */
class DeathTest {
  @Test fun `a hidden killer becomes a player`() {
    assertEquals("Steve was slain by a player", Death.scrub("Steve was slain by Alex", listOf("Alex")))
  }

  @Test fun `only whole names go, so a name inside another stays`() {
    assertEquals("Steve was shot by Alex_2", Death.scrub("Steve was shot by Alex_2", listOf("Alex")))
    assertEquals("Steve fell while fighting a player using [Bow]", Death.scrub("Steve fell while fighting Alex_2 using [Bow]", listOf("Alex", "Alex_2")))
  }

  @Test fun `named players and an empty message stay as they are`() {
    assertEquals("Steve drowned", Death.scrub("Steve drowned", listOf("Alex")))
    assertNull(Death.scrub(null, listOf("Alex")))
  }
}
