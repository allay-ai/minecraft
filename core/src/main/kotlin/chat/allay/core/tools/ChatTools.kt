package chat.allay.core.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The chat as the asker writes in it (since 6.38): `send_message` sends a line for them when they asked Allay to, as a
 * say button would once clicked. Texts are English, for the model.
 */
object ChatTools {
  /** Lines one answer may send: a reply, perhaps a second to someone else, never a flood. */
  const val PER_RUN = 3

  private val send = object : ClientTool<ToolScope> {
    override val name = "send_message"

    override fun run(context: ToolScope, args: JsonObject): ToolOutput {
      val player = context.requireAsker()
      // Only the asker's own chat: an agent talks through say, and a public answer belongs to everyone reading it.
      if (context.task != null) throw ToolError("an automation agent can't write to the chat")
      val text = args.string("text")?.replace('\n', ' ')?.take(256) ?: throw ToolError("pass the text to send")
      if (context.sent.incrementAndGet() > PER_RUN) throw ToolError("$PER_RUN lines an answer at most; offer a say button for more")
      val chat = context.services.chat
      val lang = context.lang
      val to = args.string("to")
      if (to == null) {
        // The client would run a line starting with / as a command.
        if (text.startsWith("/")) throw ToolError("a chat line can't start with /")
        chat.say(player, text)
        return ToolOutput(buildJsonObject { put("sent", true); put("channel", "public") }, lang.text("summary.sent_chat"))
      }
      val target = chat.whisper(player, to, text) ?: throw ToolError("nobody called $to is online to write to")
      return ToolOutput(buildJsonObject { put("sent", true); put("channel", "private"); put("to", target.name) }, lang.text("summary.sent_to", "name" to target.name))
    }
  }

  val all = listOf(send)
}
