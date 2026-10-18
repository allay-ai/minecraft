package chat.allay.spigot.automation

import chat.allay.core.tasks.Kind
import chat.allay.core.tools.*
import chat.allay.spigot.tools.Tool
import chat.allay.spigot.tools.ToolContext

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bukkit.ChatColor
import org.bukkit.entity.Player

/**
 * Tools that start and steer automation agents. The main chat starts them and passes the player's words on; an agent
 * talks to its player, waits, picks blocks, and finishes with these.
 */
object TaskTools {
  private fun tool(name: String, body: (ToolContext, Player, JsonObject) -> ToolOutput) = object : Tool {
    override val name = name
    override fun run(context: ToolContext, args: JsonObject) = body(context, context.requirePlayer(), args)
  }

  private val start = tool("start_task") { context, player, args ->
    val kind = Kind.parse(args.string("kind"))
    val brief = args.string("brief") ?: throw ToolError("brief is required: everything the agent needs to know")
    val task = context.features.automation.launch(player, kind, args.string("name") ?: kind.wire, brief, context.task)
    ToolOutput(buildJsonObject { put("id", task.id); put("name", task.name) }, context.lang.text("summary.task_started", "name" to task.name))
  }

  private val message = tool("message_task") { context, player, args ->
    val automation = context.features.automation
    val task = automation.book.find(player.uniqueId, args.string("id") ?: "") ?: throw ToolError("no running automation with that id")
    val text = args.string("text")?.take(500) ?: throw ToolError("text is required")
    automation.post(task, "[player] $text" + (task.question?.let { " (you had asked: “${it.take(200)}”)" } ?: ""))
    ToolOutput(buildJsonObject { put("sent", true) }, context.lang.text("summary.task_messaged", "name" to task.name))
  }

  private val cancel = tool("cancel_task") { context, player, args ->
    val automation = context.features.automation
    val task = automation.book.find(player.uniqueId, args.string("id") ?: "") ?: throw ToolError("no running automation with that id")
    automation.end(task, context.lang.text("automation.cancelled"))
    ToolOutput(buildJsonObject { put("cancelled", true) }, context.lang.text("summary.task_cancelled", "name" to task.name))
  }

  private val say = tool("say") { context, player, args ->
    val task = context.requireTask()
    val automation = context.features.automation
    val text = args.string("text")?.take(500) ?: throw ToolError("text is required")
    val buttons = (args["buttons"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.take(4)?.map { button ->
      Button.Reply(button.string("label")?.take(24)?.let(ChatColor::stripColor) ?: throw ToolError("each button needs a label"), task.id, button.string("reply")?.take(200) ?: throw ToolError("each button needs a reply"))
    }.orEmpty()
    automation.say(player, task, text, buttons)
    automation.agent(task).asked = true
    task.question = ChatColor.stripColor(Chat.strip(text))
    ToolOutput(buildJsonObject { put("said", true) }, "")
  }

  private val wait = tool("wait") { context, _, args ->
    val task = context.requireTask()
    val max = context.features.automation.settings().maxWaitSeconds
    val seconds = args.int("seconds")?.coerceIn(1, max)
    task.waited = true
    task.wakeAt = seconds?.let { System.currentTimeMillis() + it * 1000L }
    ToolOutput(buildJsonObject { put("waiting", true); seconds?.let { put("seconds", it) }; put("note", "end this wake now with a one-line note") }, "")
  }

  private val finish = tool("finish") { context, _, args ->
    val task = context.requireTask()
    task.finished = args.string("summary")?.take(500) ?: throw ToolError("summary is required")
    ToolOutput(buildJsonObject { put("finished", true); put("note", "end this wake now with a one-line note") }, "")
  }

  private val pick = tool("pick_blocks") { context, player, args ->
    val task = context.requireTask()
    val automation = context.features.automation
    val kind = Picking.Kind.parse(args.string("kind"))
    if (args.bool("looking") == true) {
      val block = player.getTargetBlockExact(6)?.takeIf { kind.accepts(it.type) } ?: throw ToolError("the player doesn't look at ${kind.wire.removeSuffix("s")} within 6 blocks")
      val root = if (kind == Picking.Kind.CHESTS) Blocks.root(block) else block
      val settings = automation.settings()
      val unfit = if (kind == Picking.Kind.CHESTS) Blocks.unsuitable(player, root) else Blocks.unusable(player, root, settings.radius, settings.lineOfSight)
      unfit?.let { throw ToolError("the ${root.type.key.key} at ${root.x} ${root.y} ${root.z} can't be used: ${it.model}") }
      return@tool ToolOutput(buildJsonObject { put("x", root.x); put("y", root.y); put("z", root.z); put("type", root.type.key.key) }, context.lang.text("summary.picked", "count" to 1))
    }
    automation.picking.start(player, task, kind, looking = false)
    automation.agent(task).asked = true
    ToolOutput(buildJsonObject { put("picking", true); put("note", "wait now; you wake with what the player picked") }, context.lang.text("summary.picking"))
  }

  val all = listOf(start, message, cancel, say, wait, finish, pick)

  /** Plain text out of a line with color codes, for the main chat's prompt. */
  private object Chat {
    fun strip(text: String) = text.replace(Regex("&[0-9a-fk-orA-FK-OR]"), "")
  }
}
