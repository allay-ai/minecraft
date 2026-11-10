package chat.allay.core.tools

import chat.allay.core.Legacy
import chat.allay.core.PlayerAudience
import chat.allay.core.tasks.Agents
import chat.allay.core.tasks.Kind
import chat.allay.core.tasks.Need
import chat.allay.core.tasks.SourceScope
import chat.allay.core.tasks.Task

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Tools that start and steer automation agents. The main chat starts them and passes the player's words on; an agent
 * talks to its player, waits, and finishes with these; picking blocks is the platform's.
 */
object TaskTools {
  private fun tool(name: String, body: (ToolScope, PlayerAudience, Agents, JsonObject) -> ToolOutput) = object : ClientTool<ToolScope> {
    override val name = name
    override fun run(context: ToolScope, args: JsonObject) = body(context, context.requireAsker(), context.requireAgents(), args)
  }

  private val start = tool("start_task") { context, player, automation, args ->
    val kind = Kind.parse(args.string("kind"))
    val brief = args.string("brief") ?: throw ToolError("brief is required: everything the agent needs to know")
    // From an agent, the new one is its sub-agent; a stopped agent starts nothing.
    val parent = context.task?.let { context.requireTask() }
    val task = automation.launch(player, kind, args.string("name") ?: kind.wire, brief, parent)
    ToolOutput(buildJsonObject { put("id", task.id); put("name", task.name) }, context.lang.text("summary.task_started", "name" to task.name))
  }

  /** The running task [id] names: any of the player's for the chat, only its own sub-agents for an agent. */
  private fun target(context: ToolScope, player: PlayerAudience, automation: Agents, id: String?): Task {
    val parent = context.task?.let { context.requireTask() }
    val task = automation.book.find(player.uuid, id ?: "")?.takeIf { parent == null || it.parent == parent.id }
    return task ?: throw ToolError(if (parent == null) "no running automation with that id" else "no running sub-agent of yours with that id; it may have ended already")
  }

  private val message = tool("message_task") { context, player, automation, args ->
    val task = target(context, player, automation, args.string("id"))
    val text = args.string("text")?.take(500) ?: throw ToolError("text is required")
    val from = context.task?.let { "[parent «${it.name}»]" } ?: "[player]"
    automation.post(task, "$from ${Task.oneLine(text)}" + (task.question?.takeIf { context.task == null }?.let { " (you had asked: “${it.take(200)}”)" } ?: ""))
    ToolOutput(buildJsonObject { put("sent", true) }, context.lang.text("summary.task_messaged", "name" to task.name))
  }

  private val cancel = tool("cancel_task") { context, player, automation, args ->
    val task = target(context, player, automation, args.string("id"))
    // The agent that stops its own sub-agent knows already; it isn't woken with the news.
    automation.end(task, context.lang.text("automation.cancelled"), byParent = context.task != null)
    ToolOutput(buildJsonObject { put("cancelled", true) }, context.lang.text("summary.task_cancelled", "name" to task.name))
  }

  private val say = tool("say") { context, player, automation, args ->
    val task = context.requireTask()
    val text = args.string("text")?.take(500) ?: throw ToolError("text is required")
    val buttons = (args["buttons"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.take(4)?.map { button ->
      Button.Reply(button.string("label")?.take(24)?.let(Legacy::strip) ?: throw ToolError("each button needs a label"), task.id, button.string("reply")?.take(200) ?: throw ToolError("each button needs a reply"))
    }.orEmpty()
    val agent = automation.agent(task)
    automation.say(player, task, text, buttons)
    // Buttons ask for sure; a plain line may be news, which the end of the wake tells apart.
    if (buttons.isNotEmpty()) agent.asked = true
    ToolOutput(buildJsonObject { put("said", true) }, "")
  }

  private val wait = tool("wait") { context, _, _, args ->
    val task = context.requireTask()
    val max = context.services.config().automation.maxWaitSeconds
    // At least 10 seconds: a timer that fires every few seconds would pay for a wake each time.
    val seconds = args.int("seconds")?.coerceIn(MIN_WAIT_SECONDS, max)
    task.waited = true
    task.wakeAt = seconds?.let { System.currentTimeMillis() + it * 1000L }
    ToolOutput(buildJsonObject { put("waiting", true); seconds?.let { put("seconds", it) }; put("note", "end this wake now with a one-line note") }, "")
  }

  private val finish = tool("finish") { context, _, automation, args ->
    val task = context.requireTask()
    val summary = args.string("summary")?.take(500) ?: throw ToolError("summary is required")
    // A parent that still needs a sub-agent's result must not leave: its report would reach nobody.
    val children = automation.book.children(task)
    if (children.isNotEmpty() && args.bool("handoff") != true) {
      throw ToolError(
        "your sub-agent ${children.joinToString { "«${it.name}» (${it.id})" }} still runs. Wait: you wake with its [sub-agent] report " +
          "and go on from it. Only when it does the rest of the brief by itself, finish with handoff true.",
      )
    }
    task.finished = summary
    ToolOutput(buildJsonObject { put("finished", true); put("note", "end this wake now with a one-line note") }, "")
  }

  /**
   * `wait_for_items`: the agent can't go on until its player brings things only they can get (mined, found, traded).
   * It parks: it holds no slot, its time stands still, and it goes on by itself once the sources hold them.
   */
  private val waitForItems = tool("wait_for_items") { context, player, automation, args ->
    val task = context.requireTask()
    val asked = (args["items"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.take(MAX_NEEDS)
      ?: throw ToolError("items is required: [{\"item\": \"oak_log\", \"count\": 10}], everything the job needs in all")
    if (asked.isEmpty()) throw ToolError("items is empty: name what the player has to bring")
    val needs = asked.map { entry ->
      val name = entry.string("item")?.trim()?.ifEmpty { null } ?: throw ToolError("each item needs item: its id, like oak_log")
      val count = entry.int("count")?.takeIf { it in 1..MAX_COUNT } ?: throw ToolError("$name needs a count of 1–$MAX_COUNT: how many the job needs in all, not how many are missing")
      Need(automation.resolve(name) ?: throw ToolError("$name isn't an item this server has; search_items finds its id"), count)
    }.groupBy { it.item }.map { (item, same) -> Need(item, same.sumOf { it.count }) }
    if (!automation.park(task, player, needs, args.string("note"))) {
      throw ToolError("the sources hold all of it already: go on with the job")
    }
    val have = automation.count(task, player, needs)
    val hours = context.services.config().automation.parkHours
    ToolOutput(buildJsonObject {
      put("waiting", true)
      putJsonArray("missing") {
        needs.forEach { need ->
          val short = need.count - (have[need.item] ?: 0)
          if (short > 0) add(buildJsonObject { put("item", need.item); put("missing", short) })
        }
      }
      put("gives_up_in_hours", hours)
      put("note", "end this wake now with a one-line note: you wake once it's all there, or when the player writes")
    }, context.lang.text("summary.waiting_for_items"))
  }

  /** `sources`: where the agent may take things from, as its player says: the ender chest only when allowed, all chests or the picked ones. */
  private val sources = tool("sources") { context, player, automation, args ->
    val task = context.requireTask()
    val ender = args.bool("ender_chest") ?: task.scope.ender_chest
    val chests = when (val wanted = (args["chests"] as? JsonPrimitive)?.content?.trim()?.lowercase()) {
      null -> task.scope.chests
      "all" -> null
      "picked" -> automation.picked(task, player).ifEmpty { throw ToolError("the player picked no chests for you yet: start picking with pick_blocks, then call sources again") }
      else -> throw ToolError("chests is \"all\" or \"picked\", not $wanted")
    }
    task.scope = SourceScope(ender, chests)
    automation.changed(task)
    ToolOutput(buildJsonObject {
      put("inventory", true)
      put("ender_chest", ender)
      if (chests == null) put("chests", "all around that the player may open") else put("chests", buildJsonArray { chests.forEach { add(it) } })
    }, context.lang.text("summary.sources_set"))
  }

  val all = listOf(start, message, cancel, say, wait, finish, waitForItems, sources)

  /** Kinds of items one wait may name. */
  private const val MAX_NEEDS = 16
  private const val MAX_COUNT = 100_000

  /** The shortest `wait` with seconds, as the backend's schema says too. */
  const val MIN_WAIT_SECONDS = 10
}
