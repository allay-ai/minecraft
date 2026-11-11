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
  /** Where a line Allay sends for a player goes too, for `chat_history`. */
  private val history: () -> ChatHistory? = { null },
  /** What sends a private message as the player: `tools.chat_history.whisper_command`. */
  private val whisperCommand: () -> String = { "msg" },
) {
  /** `/ask` questions per player since they joined, and who already saw the tip this time. */
  private val asked = ConcurrentHashMap<UUID, Int>()
  private val tipped: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

  fun on(player: UUID) = settings.of(player).chat_mode

  /** Players whose chat line comes from a say button right now, so chat mode lets it through. */
  private val saying: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

  /** How a line Allay sent for a player went. */
  sealed interface Sent {
    /** It went out in [lines] of the game's lines; [to] got it privately. */
    data class Out(val lines: Int, val to: PlayerAudience? = null) : Sent

    /** Nobody by that name is online for the player to see. */
    data object Nobody : Sent

    /** The server's private message command won't run for the player: [command] with the game's [check]. */
    data class Forbidden(val command: String, val check: chat.allay.core.tools.CommandCatalog.Check) : Sent

    /**
     * The chat didn't let it through after [after] of its [lines]: a mute, a chat filter, an anti-spam, or nothing left
     * to send. The plugin that stopped it told the player why.
     */
    data class Refused(val after: Int, val lines: Int) : Sent
  }

  /**
   * A say button: [text] goes to the public chat as the player, through every chat plugin, as if they typed it, in as
   * many of the game's lines as it takes. On the server thread the game fires the chat event at once, so chat mode
   * sees the mark.
   */
  fun say(player: PlayerAudience, text: String): Sent {
    val lines = lines(clean(text), MAX_LINE)
    // The chat would run a line starting with / as a command.
    if (lines.isEmpty() || lines[0].startsWith("/")) return Sent.Refused(0, lines.size)
    val history = history()
    saying += player.uuid
    try {
      for ((k, line) in lines.withIndex()) {
        val out = if (history == null) true.also { player.chat(line) } else history.sent(player.uuid, hooked = true) { player.chat(line); true }
        if (!out) return Sent.Refused(k, lines.size)
      }
    } finally {
      saying -= player.uuid
    }
    return Sent.Out(lines.size)
  }

  /**
   * A private message from [player] to the player named [to], through the server's own command (`/msg`, or Essentials'
   * in its place) as the player typed it, so the server's chat plugins format, log, and mute it as usual: in as many of
   * the game's lines as it takes. Server thread.
   */
  fun whisper(player: PlayerAudience, to: String, text: String): Sent {
    val target = platform.player(to)?.takeIf { it.uuid != player.uuid && platform.visible(player, it) } ?: return Sent.Nobody
    val prefix = "${whisperCommand()} ${target.name} "
    // The game's line counts the command's / too.
    val lines = lines(clean(text), MAX_LINE - 1 - prefix.length)
    if (lines.isEmpty()) return Sent.Refused(0, 0)
    platform.check(player, prefix + lines[0])?.takeIf { it.verdict != chat.allay.core.tools.CommandCatalog.Verdict.OK }?.let { return Sent.Forbidden(prefix.trim().substringBefore(' '), it) }
    val history = history()
    for ((k, line) in lines.withIndex()) {
      val command = prefix + line
      // Kept only once the server ran the command: a server without one has nothing to keep.
      val out = history?.sent(player.uuid, platform.hearsTyped, { history.whisper(player, target, line) }) { platform.type(player, command) } ?: platform.type(player, command)
      if (!out) return Sent.Refused(k, lines.size)
    }
    return Sent.Out(lines.size, target)
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

  companion object {
    /** The most the game's chat box takes in one line, a command's / included. */
    const val MAX_LINE = 256

    private val CODE = Regex("&([0-9a-fk-or])", RegexOption.IGNORE_CASE)

    /** What the game's chat takes: one line, colors as & codes, no control characters. */
    fun clean(text: String): String =
      text.replace('§', '&').map { if (it == '\n' || it == '\r' || it == '\t') ' ' else it }.filter { it >= ' ' && it != '\u007f' }.joinToString("").trim()

    /**
     * [text] as the game's chat lines of at most [room] characters, split between words. A color or format set before
     * a split carries on into the next line, and no line starts with `/`, which the chat would run as a command.
     */
    fun lines(text: String, room: Int): List<String> {
      val out = mutableListOf<String>()
      var rest = text.trim()
      var carry = ""
      while (rest.isNotEmpty()) {
        if (carry.length + rest.length <= room) {
          out += carry + rest
          break
        }
        val space = room - carry.length
        // The last space that fits, unless the next line would then start with /.
        val found = (space downTo 1).firstOrNull { rest[it] == ' ' && rest.substring(it).trimStart().firstOrNull() != '/' }
        var cut = found ?: space
        // A word longer than a line: cut it, but never between & and its code, nor right before a /.
        if (found == null) while (cut > 1 && (rest[cut - 1] == '&' || rest.substring(cut).trimStart().firstOrNull() == '/')) cut--
        val head = rest.substring(0, cut).trimEnd()
        out += carry + head
        carry = codes(carry + head)
        rest = rest.substring(cut).trimStart()
      }
      return out
    }

    /** The & color and format codes in force at the end of [line], for the line after it. */
    private fun codes(line: String): String {
      var color = ""
      val formats = linkedSetOf<Char>()
      for (match in CODE.findAll(line)) {
        val code = match.groupValues[1].lowercase()[0]
        when (code) {
          'r' -> color = "".also { formats.clear() }
          in "0123456789abcdef" -> color = "&$code".also { formats.clear() }
          else -> formats += code
        }
      }
      return color + formats.joinToString("") { "&$it" }
    }
  }
}
