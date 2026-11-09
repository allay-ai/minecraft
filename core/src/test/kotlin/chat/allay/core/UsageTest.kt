package chat.allay.core

import java.util.UUID
import java.util.logging.Logger
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [Tally] (what bStats gets each period) and [UsageBook] (all-time numbers). */
class UsageTest {
  private val log = Logger.getAnonymousLogger()

  @Test fun `answer times fall into their buckets`() {
    assertEquals("<5s", Tally.bucket(4_999))
    assertEquals("5–15s", Tally.bucket(5_000))
    assertEquals("15–30s", Tally.bucket(29_999))
    assertEquals("30–60s", Tally.bucket(30_000))
    assertEquals(">60s", Tally.bucket(60_000))
  }

  @Test fun `a period takes its counts and leaves zero`() {
    val tally = Tally()
    tally.ran("ask", 0, 0, 0.0, 3_000)
    tally.ran("ask", 0, 0, 0.0, 70_000)
    tally.ran("askx", 0, 0, 0.0, null)
    tally.ran(null, 0, 0, 0.0, null)
    assertEquals(3, tally.questions())
    assertEquals(mapOf("ask" to 2, "askx" to 1), tally.commands())
    assertEquals(mapOf("<5s" to 1, ">60s" to 1), tally.answerTimes())
    assertEquals(0, tally.questions())
    assertEquals(emptyMap(), tally.commands())
  }

  @Test fun `thousands and cents carry what's left`() {
    val tally = Tally()
    tally.ran(null, 1_500, 999, 0.015, null)
    assertEquals(1, tally.inputThousands())
    assertEquals(0, tally.outputThousands())
    assertEquals(1, tally.cents())
    tally.ran(null, 600, 1, 0.006, null)
    // 500 + 600 tokens, 999 + 1, and half a cent plus 0.6 of one.
    assertEquals(1, tally.inputThousands())
    assertEquals(1, tally.outputThousands())
    assertEquals(1, tally.cents())
    assertEquals(0, tally.cents())
  }

  @Test fun `languages and how they were picked`() {
    val tally = Tally()
    tally.picked("ru", "ip")
    tally.picked("ru", "choice")
    tally.picked("en", "default")
    assertEquals(mapOf("ru" to 2, "en" to 1), tally.languages())
    assertEquals(mapOf("ip" to 1, "choice" to 1, "default" to 1), tally.sources())
    assertEquals(emptyMap(), tally.languages())
  }

  @Test fun `the book keeps totals and players, and the median`() {
    val folder = createTempDirectory("usage").toFile()
    val book = UsageBook(folder, log)
    val steve = UUID.randomUUID()
    book.ran(steve, "ask", 1000, 200, 0.01, 2_000)
    book.ran(steve, "askall", 500, 100, 0.02, 10_000)
    book.ran(steve, "ask", 0, 0, 0.0, 4_000)
    book.ran(null, "askx", 10, 10, 0.0, null)
    book.ran(steve, null, 300, 30, 0.005, null)

    val again = UsageBook(folder, log)
    val player = again.player(steve)!!
    assertEquals(mapOf("ask" to 2L, "askall" to 1L), player.questions)
    assertEquals(1800, player.input_tokens)
    assertEquals(330, player.output_tokens)
    assertEquals(0.035, player.cost, 1e-9)
    assertEquals(16.0 / 3, player.averageSeconds!!, 1e-9)
    assertEquals(4.0, player.medianSeconds)
    assertEquals(4, again.total().asked)

    again.forget(steve)
    assertNull(UsageBook(folder, log).player(steve))
    assertEquals(4, UsageBook(folder, log).total().asked)
  }

  @Test fun `an even count's median is between the middle two`() {
    val totals = UsageBook.Totals().plus("ask", 0, 0, 0.0, 2_000).plus("ask", 0, 0, 0.0, 5_000)
    assertEquals(3.5, totals.medianSeconds)
    assertNull(UsageBook.Totals().medianSeconds)
  }
}
