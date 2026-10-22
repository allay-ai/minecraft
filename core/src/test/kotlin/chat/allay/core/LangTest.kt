package chat.allay.core

import java.io.File
import java.io.InputStreamReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LangTest {
  private fun bundled(code: String): Map<String, Any> =
    InputStreamReader(javaClass.getResourceAsStream("/lang/$code.yml")!!, Charsets.UTF_8).use(Texts::read)

  @Test fun `placeholders, plurals, and fallbacks`() {
    val texts = Texts(
      mapOf(
        "en" to mapOf("a" to "{n} {n|stack|stacks} of {item}", "only_en" to "English"),
        "ru" to mapOf("a" to "{n} {n|стак|стака|стаков} {item}", "off" to ""),
      ),
      "ru",
    )
    val ru = texts.lang("ru")
    assertEquals("21 стак алмаз", ru.text("a", "n" to 21, "item" to "алмаз"))
    assertEquals("English", ru.text("only_en"))
    assertEquals("missing", ru.text("missing"))
    assertEquals("", ru.text("off"))
    assertEquals("1 stack of {item}", texts.lang("en").text("a", "n" to 1))
    // A player's text with braces is never read as a placeholder.
    assertEquals("2 стака {n}", ru.text("a", "n" to 2, "item" to "{n}"))
    // An unknown language reads the default.
    assertEquals("ru", texts.lang("fr").code)
  }

  @Test fun `papi placeholders come from the template only`() {
    val texts = Texts(mapOf("en" to mapOf("q" to "{papi:prefix}{player} asks: {text}", "n" to "{n} {n|stack|stacks}")), "en")
    val papi = texts.lang("en").about { name -> if (name == "prefix") "[VIP] " else "?" }
    assertEquals("[VIP] Steve asks: hi", papi.text("q", "player" to "Steve", "text" to "hi"))
    // A player's words or the model's answer never ask another plugin for anything.
    assertEquals("[VIP] Steve asks: {papi:prefix}", papi.text("q", "player" to "Steve", "text" to "{papi:prefix}"))
    // Without PlaceholderAPI the tag shows nothing; texts without it read as before.
    assertEquals("Steve asks: hi", texts.lang("en").text("q", "player" to "Steve", "text" to "hi"))
    assertEquals("2 stacks", papi.text("n", "n" to 2))
  }

  @Test fun `languages come from the country first, then the game`() {
    val resolver = LocaleResolver(Config.Locales("en", listOf("ip", "client"), mapOf("ru" to listOf("RU", "KZ"), "pt" to listOf("BR"))), setOf("en", "ru", "pt", "de"))
    assertEquals("ru", resolver.resolve("kz", "en_us"))
    assertEquals("de", resolver.resolve("US", "de_de"))
    assertEquals("pt", resolver.resolve(null, "pt_BR"))
    assertEquals(null, resolver.resolve("US", "ja_jp"))
  }

  @Test fun `every bundled language has every English key`() {
    val english = bundled("en")
    for (code in Texts.BUNDLED) {
      val texts = bundled(code)
      assertEquals(english.keys, texts.keys, "lang/$code.yml keys differ from en.yml: missing ${english.keys - texts.keys}, extra ${texts.keys - english.keys}")
      for ((key, value) in english) {
        val names = PLACEHOLDER.findAll(value.toString()).map { it.groupValues[1] }.toSet() - "reason"
        val theirs = PLACEHOLDER.findAll(texts.getValue(key).toString()).map { it.groupValues[1] }.toSet()
        assertTrue(theirs.containsAll(names), "lang/$code.yml $key lacks ${names - theirs}")
      }
    }
  }

  @Test fun `every key the code asks for is in en_yml`() {
    val english = bundled("en")
    val used = listOf(File("src/main/kotlin"), File("../spigot/src/main/kotlin")).flatMap { it.walk().toList() }.asSequence()
      .filter { it.extension == "kt" }
      .flatMap { KEY.findAll(it.readText()).map { match -> match.groupValues[1] } }.toSet()
    assertTrue(used.size > 100, "the scan found only ${used.size} keys")
    val missing = used.filterNot { key -> key in english || english.keys.any { it.startsWith("$key.") } }
    assertEquals(emptyList(), missing, "keys used in code but missing from en.yml")
  }

  private companion object {
    val PLACEHOLDER = Regex("\\{(\\w+)")
    val KEY = Regex("\"((?:status|tool_lines|summary|messages|memory|buttons|goals|menus|errors|chat_mode|public|billing|locale)\\.[a-z_.]+[a-z_])\"")
  }
}
