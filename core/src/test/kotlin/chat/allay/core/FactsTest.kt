package chat.allay.core

import chat.allay.core.tools.Facts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FactsTest {
  @Test fun `plugin and mod descriptions are one short line`() {
    assertEquals("Graves for your items", Facts.shortDescription("  Graves for\n your   items "))
    assertNull(Facts.shortDescription("   "))
    val long = Facts.shortDescription("word ".repeat(60))!!
    assertEquals(120, long.length)
    assertTrue(long.endsWith("…"))
  }
}
