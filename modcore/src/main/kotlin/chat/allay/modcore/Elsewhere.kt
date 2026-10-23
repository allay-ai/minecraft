package chat.allay.modcore

import chat.allay.core.Lang
import chat.allay.core.Legacy
import chat.allay.core.Texts as Languages

import net.minecraft.network.chat.Component

/**
 * Allay in a player's game on a server without Allay. It can't do anything there: the world, the chests, and the
 * answers belong to that server. Then `/ask`, `/askall`, `/askx`, and `/allay` only say so in the player's language;
 * on a server with Allay they go to it, and in singleplayer the game's own server runs Allay. Each loader's client
 * registers the commands and tells this what the server sent.
 */
object Elsewhere {
  val NAMES = listOf("ask", "askall", "askx", "allay")

  /** What the server we're on registered itself, as its command tree last said. */
  @Volatile private var server: Set<String> = emptySet()

  @JvmStatic fun serverCommands(names: Collection<String>) {
    server = names.toSet()
  }

  /**
   * Ours to answer: a server without Allay, and not the game's own server, where Allay runs. A server sends only the
   * commands a player may use, so any of Allay's four in its tree (`/allay` is everyone's) means Allay runs there.
   */
  fun mine(singleplayer: Boolean): Boolean = !singleplayer && NAMES.none { it in server }

  /** What the commands say there, in the game's language [locale] (`en_us`). */
  fun refusal(locale: String): Component = Texts.legacy(Legacy.color(languages(locale).text("messages.not_on_this_server")), links = true)

  /** The player's language from the jar's texts, English when Allay doesn't speak it. */
  private fun languages(locale: String): Lang {
    val code = locale.substringBefore('_')
    val texts = listOf("en", code).distinct().mapNotNull { language ->
      Elsewhere::class.java.classLoader.getResourceAsStream("lang/$language.yml")?.reader(Charsets.UTF_8)?.use { language to Languages.read(it) }
    }.toMap()
    return Languages(texts, "en").lang(code)
  }
}
