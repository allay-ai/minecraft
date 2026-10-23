package chat.allay.core

import chat.allay.core.tools.LocatorBar
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [LocatorBar.shows] against the game's rules in 26.3 (`WaypointTransmitter.doesSourceIgnoreReceiver`). */
class LocatorBarTest {
  private val range = LocatorBar.DEFAULT_RANGE

  @Test fun `players on the bar by default, however far`() {
    assertTrue(LocatorBar.shows(viewerSpectator = false, targetSpectator = false, transmit = range, receive = range, distance = 5_000.0))
  }

  @Test fun `crouching, a pumpkin, or invisibility take a player off the bar`() {
    // Each multiplies the transmit range by 0, so a spectator doesn't see them either.
    assertFalse(LocatorBar.shows(viewerSpectator = false, targetSpectator = false, transmit = 0.0, receive = range, distance = 3.0))
    assertFalse(LocatorBar.shows(viewerSpectator = true, targetSpectator = false, transmit = 0.0, receive = range, distance = 3.0))
  }

  @Test fun `the shorter of both ranges limits the bar`() {
    assertTrue(LocatorBar.shows(viewerSpectator = false, targetSpectator = false, transmit = 100.0, receive = range, distance = 100.0))
    assertFalse(LocatorBar.shows(viewerSpectator = false, targetSpectator = false, transmit = 100.0, receive = range, distance = 100.5))
    assertFalse(LocatorBar.shows(viewerSpectator = false, targetSpectator = false, transmit = range, receive = 50.0, distance = 60.0))
  }

  @Test fun `spectators see everyone who transmits, nobody sees spectators`() {
    assertTrue(LocatorBar.shows(viewerSpectator = true, targetSpectator = false, transmit = 10.0, receive = 10.0, distance = 900.0))
    assertTrue(LocatorBar.shows(viewerSpectator = true, targetSpectator = true, transmit = range, receive = range, distance = 9.0))
    assertFalse(LocatorBar.shows(viewerSpectator = false, targetSpectator = true, transmit = range, receive = range, distance = 9.0))
  }
}
