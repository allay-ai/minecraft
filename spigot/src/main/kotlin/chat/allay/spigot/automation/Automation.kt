package chat.allay.spigot.automation

import chat.allay.spigot.of
import chat.allay.spigot.code
import chat.allay.spigot.audience

import chat.allay.core.*
import chat.allay.core.tasks.Kind
import chat.allay.core.tasks.Reservations
import chat.allay.core.tasks.Task
import chat.allay.core.tasks.TaskBook
import chat.allay.core.tools.Button
import chat.allay.core.tools.ToolError
import chat.allay.core.Clicks
import chat.allay.core.Locales
import chat.allay.core.QuotaGate
import chat.allay.spigot.SenderAsker
import chat.allay.spigot.send
import chat.allay.spigot.tools.ToolContext

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.util.concurrent.ConcurrentHashMap

/**
 * Automation agents on Bukkit. Each one runs in wakes: [post] puts what woke it into its inbox, and when no wake is
 * out, the next one starts as a backend run in mode "task". Between wakes nothing is open and nothing costs. An agent
 * pauses while its player is away from where it started, and stops when they leave, on its time limit, on
 * `/allay cancel`, or when something gets in the way of its job.
 */
class Automation(
  private val plugin: JavaPlugin,
  private val locales: Locales,
  private val config: () -> Config,
  private val service: () -> Service<ToolContext>,
  private val clicks: () -> Clicks,
  private val quota: () -> QuotaGate,
  private val scope: CoroutineScope,
) : Listener {
  /** What the plugin keeps about one agent beside its [Task]: where it started, what it picked, and its job. */
  class Agent(val task: Task, val anchor: Location) {
    @Volatile var paused = false
    /** Showed buttons or a picking mode during this wake: it waits for the player. */
    @Volatile var asked = false
    /** The last line it said to the player during this wake, without color codes. */
    @Volatile var said: String? = null
    /** The wake out at the backend now; ending the agent cancels it, so nothing more runs or costs. */
    @Volatile var run: Run? = null
    var job: Job? = null
    /** The last chest plan, applied by `chests` with action apply. */
    var plan: Chests.Planned? = null
  }

  /** Something an agent left running in the world: a furnace load, a brew. */
  interface Job {
    fun cancel()
  }

  val book = TaskBook({ config().automation.maxTasks })
  val reservations = Reservations()
  val picking = Picking(plugin, locales, this)
  val undo = Undo()

  private val agents = ConcurrentHashMap<String, Agent>()
  private var ticker: BukkitTask? = null

  private fun key(task: Task) = "${task.owner}:${task.id}"

  fun agent(task: Task): Agent = agents[key(task)] ?: throw ToolError("this automation is over")

  fun agents(player: Player): List<Agent> = book.of(player.uniqueId).mapNotNull { agents[key(it)] }

  /** The label players read: “Automation agent: chests”, in their language. */
  fun title(player: Player, task: Task) = task.title(locales.of(player).text("automation.title"))

  fun settings() = config().automation

  fun start() {
    ticker = plugin.server.scheduler.runTaskTimer(plugin, Runnable(::tick), 20L, 20L)
    Jobs.start(plugin)
  }

  fun stop() {
    ticker?.cancel()
    book.all().forEach { end(it, null) }
  }

  /**
   * For the tools: [task] is an agent of [kind], and its player may still run that kind (an admin may have taken the
   * permission away meanwhile). A chests agent can't smelt, so one permission never opens another.
   */
  fun require(player: Player, task: Task, kind: Kind) {
    if (task.kind != kind) throw ToolError("this is a ${task.kind.wire} agent; ${kind.wire} needs its own, started with start_task")
    allowed(player, kind)?.let { throw ToolError(it) }
  }

  /** Whether [player] may run automations of [kind] here: the switches in config.yml, then the permissions. */
  fun allowed(player: Player, kind: Kind): String? = when {
    !config().automation.on(kind) -> "automation of ${kind.wire} is switched off on this server"
    !player.hasPermission(Permissions.AUTOMATION) || !player.hasPermission(Permissions.automation(kind)) -> "the player has no permission for automation of ${kind.wire}"
    else -> null
  }

  /** Starts an agent for [player] with [brief]; [parent] is the agent that starts it, or null for the main chat. */
  fun launch(player: Player, kind: Kind, name: String, brief: String, parent: Task?): Task {
    allowed(player, kind)?.let { throw ToolError(it) }
    val task = book.start(player.uniqueId, kind, name, brief.take(2000), (parent?.depth ?: 0) + 1, parent?.id)
    agents[key(task)] = Agent(task, player.location.clone())
    say(player, task, locales.of(player).text("automation.started"))
    plugin.logger.info("⚙️ Automation agent ${task.name} (${task.kind.wire}) started for ${player.name}")
    post(task, brief.take(2000))
    return task
  }

  /** Wakes [task] with [message] now, or right after the wake that's out. Safe from any thread. */
  fun post(task: Task, message: String) {
    if (task.done) return
    task.inbox += message
    onMain { wake(task) }
  }

  /** A button under one of the agent's questions. */
  fun reply(player: Player, id: String, label: String, reply: String) {
    val task = book.find(player.uniqueId, id) ?: return locales.of(player).send(player, "automation.gone")
    // The click shows at once, so the player knows it went through while the agent thinks.
    line(player, task, "automation.replied", "label" to label)
    // The agent's notes may not say what it asked, so the click carries the question along.
    post(task, "[button] $reply" + (task.question?.let { " (the player's answer to your question “${it.take(200)}”)" } ?: ""))
  }

  /**
   * Stops [task]: its wake, job, picks, and holds go; [reason] in the player's language, or null for a quiet end. [byParent]
   * when the agent that started it stopped it, which needs no news of it.
   */
  fun end(task: Task, reason: String?, byParent: Boolean = false) {
    if (task.done) return
    val agent = agents.remove(key(task))
    book.end(task)
    agent?.run?.cancel()
    agent?.job?.cancel()
    reservations.release(task.id)
    val player = plugin.server.getPlayer(task.owner)
    if (player != null) {
      picking.stop(player, task)
      if (reason != null) line(player, task, "automation.stopped", "reason" to reason)
    }
    if (!byParent) tellParent(task, "stopped" + (reason?.let { ": $it" } ?: " (the player left or the server stopped)"))
    // An agent's sub-agents go with it.
    book.of(task.owner).filter { it.parent == task.id }.forEach { end(it, reason) }
  }

  /** Stops [task] because someone took what it held or touched its blocks; the player hears what happened. */
  fun interfered(task: Task, reason: String) {
    val player = plugin.server.getPlayer(task.owner) ?: return end(task, null)
    end(task, locales.of(player).text("automation.interfered", "reason" to reason))
  }

  /** The agent's `finish`: its last line, then it's gone. */
  fun finish(player: Player, task: Task, summary: String) {
    line(player, task, "automation.finished", "text" to summary)
    plugin.logger.info("✅ Automation agent ${task.name} finished for ${player.name}")
    val agent = agents.remove(key(task))
    book.end(task)
    agent?.job?.cancel()
    reservations.release(task.id)
    picking.stop(player, task)
    tellParent(task, "finished: $summary")
    // Its sub-agents carry on: `finish` lets an agent go while they run only with handoff, when they own the rest.
  }

  /**
   * Wakes the agent that started [task] with how it ended, so the parent goes on with its next step instead of waiting
   * for a sub-agent that is gone. An ended parent hears nothing.
   */
  private fun tellParent(task: Task, outcome: String) {
    val parent = task.parent?.let { book.find(task.owner, it) } ?: return
    post(parent, task.outcome(outcome))
  }

  /** A line from the agent to its player, with [buttons] under it. */
  fun say(player: Player, task: Task, text: String, buttons: List<Button> = emptyList()) {
    line(player, task, "automation.say", "text" to text)
    if (buttons.isNotEmpty()) clicks().show(player.audience, buttons)
  }

  private fun line(player: Player, task: Task, key: String, vararg values: Pair<String, Any?>) {
    val lang = locales.of(player)
    val text = values.toMap()["text"]?.toString()
    val template = lang.text(key, "title" to title(player, task), *values.filter { it.first != "text" }.toTypedArray(), "text" to Legacy.SLOT)
    // The agent's own words keep their colors, like Allay's answers; nothing a player typed gets here unescaped.
    player.sendMessage(Legacy.color(if (text != null) template.replace(Legacy.SLOT, text) else template.replace(Legacy.SLOT, "")))
  }

  /** `/allay cancel` with no answer running: stops the player's agents. True when there were any. */
  fun cancel(player: Player): Boolean {
    val tasks = book.of(player.uniqueId)
    tasks.forEach { end(it, locales.of(player).text("automation.cancelled")) }
    return tasks.isNotEmpty()
  }

  @EventHandler fun onQuit(event: PlayerQuitEvent) {
    book.of(event.player.uniqueId).forEach { end(it, null) }
    picking.stop(event.player, null)
  }

  // Server thread only.
  private fun wake(task: Task) {
    val agent = agents[key(task)] ?: return
    if (task.done || task.running || task.inbox.isEmpty() || agent.paused) return
    val player = plugin.server.getPlayer(task.owner) ?: return end(task, null)
    val settings = config().automation
    if (task.wakes >= settings.maxWakes) return end(task, locales.of(player).text("automation.too_many_steps"))
    if (!quota().allows(player.audience)) return end(task, locales.of(player).text("automation.quota"))
    val message = task.drain()
    task.wakes++
    task.running = true
    // Whatever woke it, an old timer has nothing left to say.
    task.wakeAt = null
    task.begin(message)
    agent.asked = false
    agent.said = null
    val lang = locales.of(player)
    val run = Run(SenderAsker(player, locales.code(player)), Run.Mode.TASK, lang, config().ui.verbSeconds, task = task)
    run.model = quota().model(player.audience)
    agent.run = run
    val quiet = object : Service.Listener {
      override fun toolStarted(callId: String, name: String, args: JsonObject?) {}
      override fun toolFinished(callId: String, summary: String?, failed: Boolean) {}
    }
    scope.launch {
      var failure: Exception? = null
      try {
        service().chat(run, message, quiet)
      } catch (error: Exception) {
        if (error !is CancellationException) failure = error
      } finally {
        quota().spent(player.uniqueId, run.cost)
        onMain { afterWake(task, player, message, failure) }
      }
    }
  }

  private fun afterWake(task: Task, player: Player, message: String, failure: Exception?) {
    task.running = false
    if (task.done) return
    val agent = agents[key(task)] ?: return
    agent.run = null
    // A finish the plugin took stands, even when the stream broke after it.
    task.finished?.let { return finish(player, task, it) }
    Retry.after(failure)?.takeIf { task.retries < Retry.TIMES }?.let { seconds ->
      // Nothing ran: the same message goes again later, so a backend restart or a busy minute doesn't kill the agent.
      task.retries++
      task.inbox.addFirst(message)
      plugin.logger.info("⏳ Automation agent ${task.name}: the backend couldn't take the wake ($failure); trying again in $seconds s")
      plugin.server.scheduler.runTaskLater(plugin, Runnable { wake(task) }, seconds * 20L)
      return
    }
    task.retries = 0
    if (failure != null) {
      plugin.logger.warning("❌ Automation agent ${task.name} failed: $failure")
      return end(task, ErrorHandler.message(locales.of(player), failure))
    }
    val busy = agent.job?.let(Jobs::active) == true || book.children(task).isNotEmpty()
    task.settle(agent.said, agent.asked, busy)
    if (task.inbox.isNotEmpty()) wake(task)
  }

  private fun tick() {
    val now = System.currentTimeMillis()
    val settings = config().automation
    for (task in book.all()) {
      val agent = agents[key(task)] ?: continue
      val player = plugin.server.getPlayer(task.owner) ?: continue
      if (now - task.startedAt > settings.maxMinutes * 60_000L) {
        end(task, locales.of(player).text("automation.timed_out", "minutes" to settings.maxMinutes))
        continue
      }
      val away = player.world != agent.anchor.world || player.location.distance(agent.anchor) > settings.pauseDistance
      if (away != agent.paused) {
        agent.paused = away
        line(player, task, if (away) "automation.paused" else "automation.resumed")
        if (!away) wake(task)
      }
      val due = task.wakeAt
      if (due != null && due <= now && !task.running) {
        task.wakeAt = null
        post(task, "[timer] the wait is over")
      }
    }
    picking.tick(now)
  }

  /** Runs [block] on the next tick. */
  fun later(block: () -> Unit) {
    if (plugin.isEnabled) plugin.server.scheduler.runTask(plugin, Runnable(block))
  }

  /** A text in [task]'s player's language. */
  fun text(task: Task, key: String, vararg values: Pair<String, Any?>): String =
    plugin.server.getPlayer(task.owner)?.let { locales.of(it).text(key, *values) } ?: key

  private fun onMain(block: () -> Unit) {
    if (!plugin.isEnabled) return
    if (plugin.server.isPrimaryThread) block() else plugin.server.scheduler.runTask(plugin, Runnable(block))
  }
}
