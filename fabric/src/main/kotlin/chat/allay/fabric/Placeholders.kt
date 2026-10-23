package chat.allay.fabric

import chat.allay.core.Handler
import chat.allay.core.Locales
import chat.allay.core.PlayerSettings
import chat.allay.fabric.tools.Goals

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

/**
 * Text Placeholder API both ways, when it's there. `{papi:id}` in a text asks for `%id%` for the player the message is
 * about: a placeholder of the template, never `%…%` replaced over the whole line, so a percent sign or a player's
 * words stay as they are. The id is Text Placeholder API's, like `player:name`; a PlaceholderAPI-style one without a
 * colon reads its first `_` as the colon, so `player_name` asks for `player:name`. And `%allay:…%` tells other mods
 * (TAB, scoreboards, Styled Chat) about Allay.
 */
object Placeholders {
  val available: Boolean by lazy { FabricLoader.getInstance().isModLoaded("placeholder-api") }

  /** Where `%allay:…%` reads from; null while Allay is off, since Text Placeholder API keeps its placeholders until the game quits. */
  class Sources(val settings: PlayerSettings, val goals: Goals, val locales: Locales, val handler: () -> Handler)

  @Volatile private var sources: Sources? = null
  private var registered = false

  /** What fills `{papi:…}` for [player]; null for the console and command blocks, whose tags show nothing. */
  fun about(platform: FabricPlatform, player: ServerPlayer?): ((String) -> String?)? {
    player ?: return null
    // Only touch Text Placeholder API's classes when it's there; they aren't on the classpath otherwise.
    return { name -> if (available) Api.resolve(player, id(name)) else null }
  }

  /** Text Placeholder API's id for a name in `{papi:…}`: as written with a colon, else the first `_` becomes one. */
  fun id(name: String): String = if (':' in name) name else name.replaceFirst('_', ':')

  /** Registers `%allay:…%`. Call only when [available]. Returns a handle that turns them off again. */
  fun register(settings: PlayerSettings, goals: Goals, locales: Locales, handler: () -> Handler): AutoCloseable {
    val mine = Sources(settings, goals, locales, handler)
    sources = mine
    if (!registered) {
      Api.register()
      registered = true
    }
    return AutoCloseable { if (sources === mine) sources = null }
  }

  /**
   * `%allay:chat_mode%` and `%allay:thinking%` (`yes` or `no`, PlaceholderAPI's words: chat mode is on, Allay is
   * answering right now), `%allay:goals%` (how many goals the player has), and `%allay:language%` (the language Allay
   * talks to them in). Other mods may ask from their own threads, so everything here reads thread-safe state.
   */
  fun value(name: String, player: UUID?, online: ServerPlayer?): String? {
    val sources = sources ?: return ""
    val id = player ?: return ""
    return when (name) {
      "chat_mode" -> bool(sources.settings.of(id).chat_mode)
      "thinking" -> bool(sources.handler().busy(id))
      "goals" -> "${sources.goals.count(id)}"
      "language" -> online?.let { sources.locales.code(it.audience) } ?: ""
      else -> null
    }
  }

  private fun bool(value: Boolean) = if (value) "yes" else "no"

  // Only touched when the API is loaded.
  private object Api {
    fun resolve(player: ServerPlayer, id: String): String? {
      val identifier = Identifier.tryParse(id) ?: return null
      val context = eu.pb4.placeholders.api.ServerPlaceholderContext.of(player)
      val result = eu.pb4.placeholders.api.Placeholders.getServerPlaceholder(identifier)?.onPlaceholderRequest(context, null)
        ?: eu.pb4.placeholders.api.Placeholders.getCommonPlaceholder(identifier)?.onPlaceholderRequest(context, null)
        ?: return null
      return if (result.isValid) Texts.legacyOf(result.component()) else null
    }

    fun register() = listOf("chat_mode", "thinking", "goals", "language").forEach { name ->
      eu.pb4.placeholders.api.Placeholders.registerServer<Unit>(Identifier.fromNamespaceAndPath("allay", name)) { context, _ ->
        val online = if (context.hasPlayer()) context.player() as? ServerPlayer else null
        val id = online?.uuid ?: if (context.hasGameProfile()) context.gameProfile()?.id else null
        eu.pb4.placeholders.api.PlaceholderResult.value(value(name, id, online).orEmpty())
      }
    }
  }
}
