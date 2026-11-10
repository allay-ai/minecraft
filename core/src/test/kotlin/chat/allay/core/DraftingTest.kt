package chat.allay.core

import chat.allay.core.tools.DraftCell
import chat.allay.core.tools.DraftSide
import chat.allay.core.tools.Drafting
import kotlin.test.Test
import kotlin.test.assertEquals

/** A draft is written as if the player faced north; these hold the turn to where they really look. */
class DraftingTest {
  @Test fun `right is a quarter turn clockwise from forward`() {
    assertEquals(DraftSide.EAST, DraftSide.NORTH.right)
    assertEquals(DraftSide.SOUTH, DraftSide.EAST.right)
    assertEquals(DraftSide.WEST, DraftSide.SOUTH.right)
    assertEquals(DraftSide.NORTH, DraftSide.WEST.right)
  }

  @Test fun `block states turn by the quarters from north`() {
    assertEquals(listOf(0, 1, 2, 3), DraftSide.entries.map { it.turns })
  }

  @Test fun `the player's yaw picks the nearest side`() {
    assertEquals(DraftSide.SOUTH, DraftSide.of(0f))
    assertEquals(DraftSide.WEST, DraftSide.of(90f))
    assertEquals(DraftSide.NORTH, DraftSide.of(180f))
    assertEquals(DraftSide.NORTH, DraftSide.of(-180f))
    assertEquals(DraftSide.EAST, DraftSide.of(-90f))
    assertEquals(DraftSide.SOUTH, DraftSide.of(-30f))
  }

  @Test fun `cells land in the world the way the draft faces`() {
    // Facing east, forward is +x and right is +z.
    assertEquals(Triple(13, 66, 22), Drafting.place(Triple(10, 64, 20), DraftSide.EAST, DraftCell(2, 2, 3)))
    assertEquals(Triple(12, 64, 17), Drafting.place(Triple(10, 64, 20), DraftSide.NORTH, DraftCell(2, 0, 3)))
  }

  @Test fun `states read from the game's text`() {
    assertEquals(mapOf("delay" to "2", "facing" to "north", "locked" to "false", "powered" to "false"), Drafting.states("minecraft:repeater[delay=2,facing=north,locked=false,powered=false]"))
    assertEquals(emptyMap(), Drafting.states("minecraft:stone"))
  }
}
