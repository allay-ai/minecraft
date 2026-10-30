package chat.allay.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WindowTest {
  private val back = Window.Button("Back", "", Window.WIDE, "/allay")
  private val pay = Window.Button("Pay", "", Window.WIDE, "/allay link")

  @Test fun `a window without buttons gets its exit as the button the game needs`() {
    val ready = Window("Status", listOf("Paid"), emptyList(), 1, Window.BODY, exit = back).ready()
    assertEquals(listOf(back), ready.buttons)
    assertNull(ready.exit)
  }

  @Test fun `a window with buttons keeps its exit at the bottom`() {
    val ready = Window("Status", listOf("Unpaid"), listOf(pay), 1, Window.BODY, exit = back).ready()
    assertEquals(listOf(pay), ready.buttons)
    assertEquals(back, ready.exit)
  }
}
