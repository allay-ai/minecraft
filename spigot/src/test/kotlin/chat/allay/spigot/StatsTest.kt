package chat.allay.spigot

import kotlin.test.Test
import kotlin.test.assertEquals

/** What bStats learns from the URLs in config.yml: a name, never the URL. */
class StatsTest {
  @Test fun `a byok key's provider by its host`() {
    assertEquals("openrouter", Stats.provider("https://openrouter.ai/api/v1"))
    assertEquals("openai", Stats.provider("https://api.openai.com/v1"))
    assertEquals("other", Stats.provider("http://localhost:11434/v1"))
    assertEquals("other", Stats.provider("https://openrouter.ai.example.com/v1"))
    assertEquals("other", Stats.provider("not a url"))
  }

  @Test fun `allay chat or a backend of your own`() {
    assertEquals("allay.chat", Stats.backend("https://allay.chat"))
    assertEquals("allay.chat", Stats.backend("https://ALLAY.chat/"))
    assertEquals("self-hosted", Stats.backend("http://allay-chat:3000"))
    assertEquals("self-hosted", Stats.backend("https://allay.chat.example.com"))
  }
}
