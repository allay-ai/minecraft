package chat.allay.core

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Chat mode: whatever a player writes in chat goes to Allay instead of the other players, with prefixes for
 * commands, public questions, and the ordinary chat. Also the tip about it under `/ask` answers.
 */
class ChatMode(
  private val platform: Platform,
  private val locales: Locales,
  private val settings: PlayerSettings,
  private val handler: () -> Handler,
) {
  /** `/ask` questions per player since they joined, and who already saw the tip this time. */
  private val asked = ConcurrentHashMap<UUID, Int>()
  private val tipped: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

  fun on(player: UUID) = settings.of(player).chat_mode

  /** Players whose chat line comes from a say button right now, so chat mode lets it through. */
  private val saying: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

  /**
   * A say button: [text] goes to the public chat as the player, through every chat plugin, as if they typed it. On the
   * server thread the game fires the chat event at once, so chat mode sees the mark.
   */
  fun say(player: PlayerAudience, text: String) {
    saying += player.uuid
    try {
      player.chat(text.replace('§', '&'))
    } finally {
      saying -= player.uuid
    }
  }

  /**
   * A line the player wrote in chat, before any other chat plugin sees it, on any thread: what the chat should show
   * instead, the line itself when chat mode leaves it alone, or null to drop it because it went to Allay.
   */
  fun message(player: PlayerAudience, message: String): String? {
    if (!on(player.uuid) || player.uuid in saying) return message
    val (route, text) = Chat.route(message)
    if (route == Chat.Route.CHAT) return text.ifEmpty { null }
    // Chat arrives off the server thread; questions start on it, like commands.
    platform.scheduler.main { if (player.online) dispatch(player, route, text) }
    return null
  }

  private fun dispatch(player: PlayerAudience, route: Chat.Route, text: String) {
    val lang = locales.of(player)
    when (route) {
      Chat.Route.ASK -> when {
        !player.can(Permissions.ASK) -> lang.send(player, "messages.no_permission")
        text.isEmpty() -> {}
        else -> {
          echo(player, text)
          handler().ask(player, text, Run.Mode.ASK)
        }
      }
      Chat.Route.ASKX -> when {
        !player.can(Permissions.ASKX) -> lang.send(player, "chat_mode.no_askx")
        text.isEmpty() -> lang.send(player, "messages.askx_usage")
        else -> {
          echo(player, "! $text")
          handler().ask(player, text, Run.Mode.ASKX)
        }
      }
      Chat.Route.PUBLIC -> when {
        !player.can(Permissions.ASKALL) -> lang.send(player, "chat_mode.no_askall")
        text.isEmpty() -> lang.send(player, "messages.askall_usage")
        // The question line everyone sees is the echo.
        else -> handler().ask(player, text, Run.Mode.ASK, public = true)
      }
      Chat.Route.CHAT -> {}
    }
  }

  /** The player's own line under Allay's star, so a question doesn't just vanish from the chat. */
  private fun echo(player: PlayerAudience, text: String) {
    player.send(Legacy.withText(locales.of(player).text("chat_mode.own", "text" to Legacy.SLOT), text))
  }

  fun joined(player: PlayerAudience) {
    if (!on(player.uuid)) return
    // After the join messages, so it's the last thing they read.
    platform.scheduler.later(40L) { if (player.online) locales.of(player).send(player, "chat_mode.reminder") }
  }

  fun quit(player: UUID) {
    asked.remove(player)
    tipped.remove(player)
  }

  /** `/allay chat`, its button, and `allay_settings`. [announce] explains the change in chat. */
  fun set(player: PlayerAudience, enabled: Boolean, announce: Boolean = true): Settings {
    val result = settings.update(player.uuid) { it.copy(chat_mode = enabled) }
    if (enabled) explain(player) else if (announce) locales.of(player).send(player, "chat_mode.disabled")
    return result
  }

  /** `/allay hints`, the “don't show” button, and `allay_settings`. */
  fun hints(player: PlayerAudience, enabled: Boolean, announce: Boolean = true): Settings {
    val result = settings.update(player.uuid) { it.copy(hints = enabled) }
    if (announce) locales.of(player).send(player, if (enabled) "chat_mode.hints_on" else "chat_mode.hints_off")
    return result
  }

  /** What chat mode does, with only the prefixes this player may use. */
  private fun explain(player: PlayerAudience) {
    val lang = locales.of(player)
    lang.send(player, "chat_mode.enabled")
    if (player.can(Permissions.ASKX)) lang.send(player, "chat_mode.prefix_askx")
    if (player.can(Permissions.ASKALL)) lang.send(player, "chat_mode.prefix_public")
    lang.send(player, "chat_mode.prefix_chat")
    lang.send(player, "chat_mode.how_to_off")
  }

  /** Counts a typed `/ask`; true when the tip about chat mode should go under this answer: the second question, once per visit. */
  fun counts(player: PlayerAudience): Boolean {
    val count = asked.merge(player.uuid, 1, Int::plus)!!
    val wants = settings.of(player.uuid).let { it.hints && !it.chat_mode } && player.can(Permissions.CHAT)
    return count >= 2 && wants && tipped.add(player.uuid)
  }
}
