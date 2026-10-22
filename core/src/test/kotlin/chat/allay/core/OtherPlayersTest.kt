package chat.allay.core

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OtherPlayersTest {
  private val alex = UUID.fromString("00000000-0000-0000-0000-00000000000a")
  private val bob = UUID.fromString("00000000-0000-0000-0000-00000000000b")
  private val carol = UUID.fromString("00000000-0000-0000-0000-00000000000c")

  @Test fun `players who didn't agree get stable labels, and the labels lead back to them`() {
    val others = OtherPlayers()
    assertEquals("Player 1", others.label(alex, "Alex", agreed = false))
    assertEquals("Carol", others.label(carol, "Carol", agreed = true))
    assertEquals("Player 2", others.label(bob, "Bob", agreed = false))
    assertEquals("Player 1", others.label(alex, "Alex", agreed = false))
    assertEquals(alex, others.player("player 1"))
    assertEquals(bob, others.player(" Player 2 "))
    assertNull(others.player("Carol"))
    // A new run starts counting again.
    assertEquals("Player 1", OtherPlayers().label(bob, "Bob", agreed = false))
  }
}
