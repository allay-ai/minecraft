package chat.allay.core

import kotlin.test.Test
import kotlin.test.assertEquals

class LegacyRunsTest {
  @Test fun `a color resets the formats and reset clears everything`() {
    assertEquals(
      listOf(
        Legacy.Run("Hi ", color = 0x55FF55, bold = true),
        Legacy.Run("there", color = 0xFFAA00),
        Legacy.Run("!"),
      ),
      Legacy.runs("§a§lHi §6there§r!"),
    )
  }

  @Test fun `formats add up until a color`() {
    assertEquals(listOf(Legacy.Run("x", color = 0xAAAAAA, italic = true, underlined = true)), Legacy.runs("§7§o§nx"))
  }

  @Test fun `hex colors read as Bukkit writes them`() {
    assertEquals(listOf(Legacy.Run("hex", color = 0x12AB34)), Legacy.runs("§x§1§2§a§b§3§4hex"))
  }

  @Test fun `an unknown code and a lone section sign stay as text`() {
    assertEquals(listOf(Legacy.Run("§z and §")), Legacy.runs("§z and §"))
  }

  @Test fun `addresses become links only when asked`() {
    assertEquals(listOf(Legacy.Run("see allay.chat now", color = 0xAAAAAA)), Legacy.runs("§7see allay.chat now"))
    assertEquals(
      listOf(
        Legacy.Run("see ", color = 0xAAAAAA),
        Legacy.Run("allay.chat", color = 0xAAAAAA, link = "http://allay.chat"),
        Legacy.Run(" or ", color = 0xAAAAAA),
        Legacy.Run("https://allay.chat/docs", color = 0xAAAAAA, link = "https://allay.chat/docs"),
      ),
      Legacy.runs("§7see allay.chat or https://allay.chat/docs", links = true),
    )
  }
}
