package chat.allay.core.tasks

import chat.allay.core.*
import chat.allay.core.tools.Button
import chat.allay.core.tools.ToolError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * Automation agents on any platform. Each one runs in wakes: [post] puts what woke it into its inbox, and when no wake
 * is out, the next one starts as a backend run in mode "task". Between wakes nothing is open and nothing costs. An
 * agent pauses while its player is away from where it started, and stops when they leave, on its time limit, on
 * `/allay cancel`, or when something gets in the way of its job. What an agent does in the world is the platform's.
 */
class Agents(
  private val platform: Platform,
  private val locales: Locales,
  private val config: () -> Config,
  private val service: () -> Service<*>,
  private val clicks: () -> Clicks,
  private val quota: () -> QuotaGate,
  private val scope: CoroutineScope,
  private val world: World,
) {
  /** What the platform keeps going for its agents in the world. */
  interface World {
    /** Forgets what the player picked for [task], or for any of their agents when null. */
    fun stopPicking(player: PlayerAudience, task: Task?)

    /** Once a second, on the server thread: picking that ran out of time. */
    fun tick(now: Long)

    /** Whether [job] still runs in the world: a furnace load, a brew. */
    fun active(job: Job): Boolean
  }

  /** Something an agent left running in the world: a furnace load, a brew. */
  interface Job {
    fun cancel()
  }

  /** What Allay keeps about one agent beside its [Task]: where it started, what it picked, and its job. */
  class Agent(val task: Task, val anchor: Protocol.Position) {
    @Volatile var paused = false
    /** Showed buttons or a picking mode during this wake: it waits for the player. */
    @Volatile var asked = false
    /** The last line it said to the player during this wake, without color codes. */
    @Volatile var said: String? = null
    /** The wake out at the backend now; ending the agent cancels it, so nothing more runs or costs. */
    @Volatile var run: Run? = null
    var job: Job? = null
    /** The last chest plan, in the platform's own shape, applied by `chests` with action apply. */
    var plan: Any? = null
    /** The backend is asked whether the owner still agrees to the notice; the wake waits for the answer. */
    @Volatile var checking = false
  }

  /** The players' agreements to the notice, set by [Allay] once it has them; without them, wakes ask nobody. */
  @Volatile var consents: (() -> Consents)? = null

  val book = TaskBook({ config().automation.maxTasks })
  val reservations = Reservations()

  private val agents = ConcurrentHashMap<String, Agent>()
  private var ticker: AutoCloseable? = null
  /** Allay is shutting down: nothing wakes any more. */
  @Volatile private var stopping = false

  private fun key(task: Task) = "${task.owner}:${task.id}"

  fun agent(task: Task): Agent = agents[key(task)] ?: throw ToolError("this automation is over")

  fun agents(player: UUID): List<Agent> = book.of(player).mapNotNull { agents[key(it)] }

  /** The label players read: “Automation agent: chests”, in their language. */
  fun title(player: Audience, task: Task) = task.title(locales.of(player).text("automation.title"))

  fun start() {
    stopping = false
    ticker = platform.scheduler.every(20L, 20L, block = ::tick)
  }

  /** Shutdown, a disable, or a reload: owners still online hear why their agents stopped; nothing is kept for later. */
  fun stop() {
    ticker?.close()
    stopping = true
    book.all().forEach { task -> end(task, platform.player(task.owner)?.let { locales.of(it).text("automation.server_stopping") }) }
  }

  /**
   * For the tools: [task] is an agent of [kind], and its player may still run that kind (an admin may have taken the
   * permission away meanwhile). A chests agent can't smelt, so one permission never opens another.
   */
  fun require(player: Audience, task: Task, kind: Kind) {
    if (task.kind != kind) throw ToolError("this is a ${task.kind.wire} agent; ${kind.wire} needs its own, started with start_task")
    allowed(player, kind)?.let { throw ToolError(it) }
  }

  /** Whether [player] may run automations of [kind] here: the switches in config.yml, then the permissions. */
  fun allowed(player: Audience, kind: Kind): String? = when {
    !config().automation.on(kind) -> "automation of ${kind.wire} is switched off on this server"
    !player.can(Permissions.AUTOMATION) || !player.can(Permissions.automation(kind)) -> "the player has no permission for automation of ${kind.wire}"
    else -> null
  }

  /** Starts an agent for [player] with [brief]; [parent] is the agent that starts it, or null for the main chat. */
  fun launch(player: PlayerAudience, kind: Kind, name: String, brief: String, parent: Task?): Task {
    allowed(player, kind)?.let { throw ToolError(it) }
    val task = book.start(player.uuid, kind, name, brief.take(2000), (parent?.depth ?: 0) + 1, parent?.id)
    agents[key(task)] = Agent(task, player.position)
    say(player, task, locales.of(player).text("automation.started"))
    platform.log.info("⚙️ Automation agent ${task.name} (${task.kind.wire}) started for ${player.name}")
    post(task, brief.take(2000))
    return task
  }

  /** Wakes [task] with [message] now, or right after the wake that's out. Safe from any thread. */
  fun post(task: Task, message: String) {
    if (task.done || stopping) return
    task.inbox += message
    onMain { wake(task) }
  }

  /** A button under one of the agent's questions. */
  fun reply(player: PlayerAudience, id: String, label: String, reply: String) {
    val task = book.find(player.uuid, id) ?: return locales.of(player).send(player, "automation.gone")
    // The click shows at once, so the player knows it went through while the agent thinks.
    line(player, task, "automation.replied", "label" to label)
    // The agent's notes may not say what it asked, so the click carries the question along.
    post(task, "[button] ${Task.oneLine(reply)}" + (task.question?.let { " (the player's answer to your question “${it.take(200)}”)" } ?: ""))
  }

  /**
   * Stops [task]: its wake, job, picks, and holds go; [reason] in the player's language, or null for a quiet end. [byParent]
   * when the agent that started it stopped it, which needs no news of it.
   */
  fun end(task: Task, reason: String?, byParent: Boolean = false) =
    close(task, reason, "stopped" + (reason?.let { ": $it" } ?: " (the player left or the server stopped)"), byParent)

  /**
   * [end] with what the player reads ([reason], in their language, or null for nothing) apart from what the agent that
   * started it wakes with ([note]).
   */
  private fun close(task: Task, reason: String?, note: String, byParent: Boolean = false) {
    if (task.done) return
    val agent = agents.remove(key(task))
    book.end(task)
    agent?.run?.cancel()
    agent?.job?.cancel()
    reservations.release(task.id)
    val player = platform.player(task.owner)
    if (player != null) {
      world.stopPicking(player, task)
      if (reason != null) line(player, task, "automation.stopped", "reason" to reason)
    }
    if (!byParent) tellParent(task, note)
    // An agent's sub-agents go with it.
    book.of(task.owner).filter { it.parent == task.id }.forEach { close(it, reason, note) }
  }

  /** Stops [task] because someone took what it held or touched its blocks; the player hears what happened. */
  fun interfered(task: Task, reason: String) {
    val player = platform.player(task.owner) ?: return end(task, null)
    end(task, locales.of(player).text("automation.interfered", "reason" to reason))
  }

  /** The agent's `finish`: its last line, then it's gone. */
  fun finish(player: PlayerAudience, task: Task, summary: String) {
    line(player, task, "automation.finished", "text" to summary)
    platform.log.info("✅ Automation agent ${task.name} finished for ${player.name}")
    val agent = agents.remove(key(task))
    book.end(task)
    agent?.job?.cancel()
    reservations.release(task.id)
    world.stopPicking(player, task)
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

  /**
   * A line from the agent to its player, with [buttons] under it: its own `say`, or a tool's (chests done, with Undo).
   * Either way the player heard something this wake, so it isn't silent.
   */
  fun say(player: PlayerAudience, task: Task, text: String, buttons: List<Button> = emptyList()) {
    line(player, task, "automation.say", "text" to text)
    if (buttons.isNotEmpty()) clicks().show(player, buttons)
    agents[key(task)]?.said = Legacy.strip(Legacy.color(text))
  }

  private fun line(player: PlayerAudience, task: Task, key: String, vararg values: Pair<String, Any?>) {
    val lang = locales.of(player)
    val text = values.toMap()["text"]?.toString()
    val template = lang.text(key, "title" to title(player, task), *values.filter { it.first != "text" }.toTypedArray(), "text" to Legacy.SLOT)
    // The agent's own words keep their colors, like Allay's answers; nothing a player typed gets here unescaped.
    player.send(Legacy.color(template.replace(Legacy.SLOT, text ?: "")))
  }

  /** `/allay cancel` with no answer running: stops the player's agents. True when there were any. */
  fun cancel(player: PlayerAudience): Boolean {
    val tasks = book.of(player.uuid)
    tasks.forEach { end(it, locales.of(player).text("automation.cancelled")) }
    return tasks.isNotEmpty()
  }

  /** The player left: their agents end quietly, and their picks go. */
  fun quit(player: PlayerAudience) {
    book.of(player.uuid).forEach { end(it, null) }
    world.stopPicking(player, null)
  }

  // Server thread only.
  private fun wake(task: Task) {
    val agent = agents[key(task)] ?: return
    if (task.done || task.running || task.inbox.isEmpty() || agent.paused) return
    // The tick that ends the backoff wakes it with whatever arrived meanwhile.
    if (System.currentTimeMillis() < task.notBefore) return
    val player = platform.player(task.owner) ?: return end(task, null)
    // An admin may have switched the kind off or taken the permission away since the agent started.
    allowed(player, task.kind)?.let { refusal -> return close(task, locales.of(player).text("automation.not_allowed"), "stopped: $refusal") }
    if (!consented(task, agent, player)) return
    val settings = config().automation
    if (task.wakes >= settings.maxWakes) return end(task, locales.of(player).text("automation.too_many_steps"))
    // The gate has told the player already; the agent that started this one still hears why.
    if (!quota().allows(player)) return close(task, null, "stopped: ${locales.of(player).text("automation.quota")}")
    val message = task.drain()
    task.wakes++
    task.running = true
    // Whatever woke it, an old timer has nothing left to say.
    task.wakeAt = null
    task.begin(message)
    agent.asked = false
    agent.said = null
    val lang = locales.of(player)
    val run = Run(platform.asker(player, locales.code(player)), Run.Mode.TASK, lang, config().ui.verbSeconds, task = task)
    run.model = quota().model(player)
    agent.run = run
    val quiet = object : Service.Listener {
      override fun toolStarted(callId: String, name: String, args: JsonObject?) {}
      override fun toolFinished(callId: String, summary: String?, failed: Boolean, refused: Boolean) {}
    }
    scope.launch {
      var failure: Exception? = null
      try {
        service().chat(run, message, quiet)
      } catch (error: Exception) {
        if (error !is CancellationException) failure = error
      } finally {
        quota().spent(player.uuid, run.cost)
        onMain { afterWake(task, player, message, failure) }
      }
    }
  }

  /**
   * Whether [task]'s owner still agrees to the notice, as every question asks. Consents trusts an answer for an hour, so
   * a longer agent waits here for the backend's; an owner who withdrew or was erased meanwhile loses every agent.
   */
  private fun consented(task: Task, agent: Agent, player: PlayerAudience): Boolean {
    val consents = consents?.invoke() ?: return true
    if (consents.known(task.owner)) return true
    if (agent.checking) return false
    agent.checking = true
    scope.launch {
      val answer = runCatching { consents.check(task.owner) }
      onMain {
        agent.checking = false
        val state = answer.getOrNull()
        val error = answer.exceptionOrNull()
        when {
          state == Consents.State.GIVEN || state == Consents.State.UNSUPPORTED -> wake(task)
          state != null -> book.of(task.owner).forEach { close(it, null, "stopped: the player no longer agrees to Allay's notice") }
          error is CancellationException -> {}
          else -> {
            platform.log.warning("❌ Automation agent ${task.name} couldn't check the player's consent: ${(error as? ApiErrorException)?.apiError?.code ?: error?.javaClass?.simpleName}")
            end(task, ErrorHandler.message(locales.of(player), error ?: IllegalStateException()))
          }
        }
      }
    }
    return false
  }

  private fun afterWake(task: Task, player: PlayerAudience, message: String, failure: Exception?) {
    task.running = false
    if (task.done) return
    val agent = agents[key(task)] ?: return
    agent.run = null
    // A finish the plugin took stands, even when the stream broke after it.
    task.finished?.let { return finish(player, task, it) }
    Retry.after(failure)?.takeIf { task.retries < Retry.TIMES }?.let { asked ->
      // Nothing ran: the same message goes again later, so a backend restart or a busy minute doesn't kill the agent.
      task.inbox.addFirst(message)
      // Nor did it cost anything, so it doesn't count toward max_wakes.
      task.wakes--
      return retry(task, asked, "the backend couldn't take the wake ($failure)")
    }
    if (failure != null && Retry.broke(failure) && !task.resumed) {
      // Something ran, so the message doesn't go again as it was: the agent reads first, then goes on, once.
      task.resumed = true
      task.inbox.addFirst(task.resume(message))
      return retry(task, 0, "its wake broke off ($failure)")
    }
    task.retries = 0
    if (failure != null) {
      platform.log.warning("❌ Automation agent ${task.name} failed: $failure")
      return end(task, ErrorHandler.message(locales.of(player), failure))
    }
    task.resumed = false
    val busy = agent.job?.let(world::active) == true || book.children(task).isNotEmpty()
    // A wake that ends with no word and no finish, and nothing to wake it, holds a slot until the task times out: the
    // first time the agent hears so and goes on, the second time it ends with a line to the player.
    if (stuck(agent.said != null, agent.asked, task.waited, task.wakeAt != null, busy, task.question != null, task.inbox.isNotEmpty())) {
      if (!task.nudged) {
        task.nudged = true
        task.inbox += SILENT
        return wake(task)
      }
      return end(task, locales.of(player).text("automation.silent"))
    }
    if (agent.said != null || task.waited) task.nudged = false
    task.settle(agent.said, agent.asked, busy)
    if (task.inbox.isNotEmpty()) wake(task)
  }

  /** Wakes [task] again after a backoff that grows with each retry in a row; [tick] ends it. */
  private fun retry(task: Task, asked: Long, why: String) {
    task.retries++
    val seconds = Retry.backoff(task.retries, asked)
    task.notBefore = System.currentTimeMillis() + seconds * 1000L
    platform.log.info("⏳ Automation agent ${task.name}: $why; trying again in $seconds s")
  }

  private fun tick() {
    val now = System.currentTimeMillis()
    val settings = config().automation
    for (task in book.all()) {
      val agent = agents[key(task)] ?: continue
      val player = platform.player(task.owner) ?: continue
      if (now - task.startedAt > settings.maxMinutes * 60_000L) {
        end(task, locales.of(player).text("automation.timed_out", "minutes" to settings.maxMinutes))
        continue
      }
      val away = away(player.position, agent.anchor, settings.pauseDistance)
      if (away != agent.paused) {
        agent.paused = away
        line(player, task, if (away) "automation.paused" else "automation.resumed")
        if (!away) wake(task)
      }
      if (task.notBefore in 1..now) {
        task.notBefore = 0L
        wake(task)
      }
      val due = task.wakeAt
      if (due != null && due <= now && !task.running) {
        task.wakeAt = null
        post(task, "[timer] the wait is over")
      }
    }
    world.tick(now)
  }

  /** Runs [block] on the next tick. */
  fun later(block: () -> Unit) = platform.scheduler.main(block)

  /** A text in [task]'s player's language. */
  fun text(task: Task, key: String, vararg values: Pair<String, Any?>): String =
    platform.player(task.owner)?.let { locales.of(it).text(key, *values) } ?: key

  private fun onMain(block: () -> Unit) {
    if (!platform.enabled) return
    if (platform.scheduler.onMain) block() else platform.scheduler.main(block)
  }

  companion object {
    /** What an agent hears after a wake that ended without a word to the player. */
    const val SILENT = "[plugin] Your last wake ended without say or finish and with nothing to wake you (no wait seconds, no job, no sub-agent, no question to the player), so the player heard nothing. Tell them in a line what happened and what's missing, then end with wait (seconds when something will finish) or finish."

    /**
     * Whether a wake left nothing to wake the agent: no line or buttons to the player, no open question, no job or
     * sub-agent running, nothing in the inbox, and no wait with seconds. A bare `wait` counts too, since it would hold a
     * slot until `max_minutes`; a timer instead would wake the agent again and again with nothing new, each wake paid.
     */
    fun stuck(said: Boolean, asked: Boolean, waited: Boolean, timer: Boolean, busy: Boolean, question: Boolean, inbox: Boolean): Boolean =
      !said && !asked && !(waited && timer) && !busy && !question && !inbox

    /** In another world, or farther than [distance] blocks from where the agent started. */
    fun away(at: Protocol.Position, anchor: Protocol.Position, distance: Int): Boolean {
      if (at.world != anchor.world || at.dimension != anchor.dimension) return true
      val dx = (at.x - anchor.x).toDouble()
      val dy = (at.y - anchor.y).toDouble()
      val dz = (at.z - anchor.z).toDouble()
      return sqrt(dx * dx + dy * dy + dz * dz) > distance
    }
  }
}
