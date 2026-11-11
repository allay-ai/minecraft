package chat.allay.core.tools

import chat.allay.core.ChatMode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The chat as the asker writes in it (since 6.38): `send_message` sends a line for them when they asked Allay to, as a
 * say button would once clicked. Texts are English, for the model.
 */
object ChatTools {
  /** Messages one answer may send: a reply, perhaps a second to someone else, never a flood. */
  const val PER_RUN = 3

  /** The longest text one call takes: four of the game's lines, as the backend's schema says (6.34.1). */
  const val MAX_TEXT = 1024

  private val send = object : ClientTool<ToolScope> {
    override val name = "send_message"

    override fun run(context: ToolScope, args: JsonObject): ToolOutput {
      val player = context.requireAsker()
      // Only the asker's own chat: an agent talks through say, and a public answer belongs to everyone reading it.
      if (context.task != null) throw ToolError("an automation agent can't write to the chat")
      val text = args.string("text")?.take(MAX_TEXT)?.takeIf { ChatMode.clean(it).isNotEmpty() } ?: throw ToolError("pass the text to send")
      val to = args.string("to")
      // The chat would run a line starting with / as a command.
      if (to == null && ChatMode.clean(text).startsWith("/")) throw ToolError("a chat line can't start with /")
      // Only what went out counts: a call the chat refused leaves the next one its chance.
      if (context.sent.get() >= PER_RUN) throw ToolError("$PER_RUN messages an answer at most; offer a say button for more")
      val chat = context.services.chat
      val lang = context.lang
      return when (val sent = if (to == null) chat.say(player, text) else chat.whisper(player, to, text)) {
        is ChatMode.Sent.Out -> {
          context.sent.incrementAndGet()
          val target = sent.to
          val output = buildJsonObject {
            put("sent", true)
            put("channel", if (target == null) "public" else "private")
            target?.let { put("to", it.name) }
            if (sent.lines > 1) put("lines", sent.lines)
          }
          ToolOutput(output, if (target == null) lang.text("summary.sent_chat") else lang.text("summary.sent_to", "name" to target.name))
        }
        ChatMode.Sent.Nobody -> throw ToolError("nobody called $to is online to write to")
        is ChatMode.Sent.Forbidden -> throw ToolError(
          when (sent.check.verdict) {
            CommandCatalog.Verdict.UNKNOWN -> "this server has no /${sent.command} for private messages; offer a say button instead"
            CommandCatalog.Verdict.FORBIDDEN -> "${player.name} may not use /${sent.command} here, so nothing went out"
            else -> "/${sent.command} wouldn't take this message (${sent.check.detail ?: "a syntax error"}); nothing went out"
          },
        )
        is ChatMode.Sent.Refused -> {
          if (sent.after > 0) context.sent.incrementAndGet()
          val part = if (sent.after > 0) "${sent.after} of its ${sent.lines} lines went out, then " else ""
          throw ToolError("${part}the chat refused it: a mute, a chat filter, or an anti-spam stopped it, and told the player why. Say so; don't send it again")
        }
      }
    }
  }

  val all = listOf(send)
}
