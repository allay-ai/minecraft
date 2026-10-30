package chat.allay.core

import chat.allay.core.tasks.Reservations
import chat.allay.core.tools.Booking
import kotlin.test.Test
import kotlin.test.assertEquals

class BookingTest {
  @Test fun `a refused call gives back only what it reserved`() {
    val holds = Reservations()
    // A running job holds iron for its refills.
    holds.reserve("task", "chest", "raw_iron", 40)
    holds.reserve("other", "chest", "raw_iron", 5)
    val booking = Booking(holds, "task")
    holds.reserve("task", "chest", "raw_iron", 10)
    holds.reserve("task", "inventory", "coal", 3)
    booking.undo()
    assertEquals(mapOf("chest" to mapOf("raw_iron" to 40)), holds.of("task"))
    assertEquals(5, holds.held("chest", "raw_iron", except = "task"))
  }

  @Test fun `what the call already took stays taken`() {
    val holds = Reservations()
    holds.reserve("task", "chest", "coal", 8)
    val booking = Booking(holds, "task")
    holds.reserve("task", "chest", "coal", 4)
    // The call takes 6 of the 12 held, then fails.
    holds.take("task", "chest", "coal", 6)
    booking.undo()
    assertEquals(mapOf("chest" to mapOf("coal" to 6)), holds.of("task"))
  }
}
