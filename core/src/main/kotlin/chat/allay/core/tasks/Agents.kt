package chat.allay.core.tasks

import chat.allay.core.*
import chat.allay.core.tools.Button
import chat.allay.core.tools.ToolError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * Automation agents on any platform. Each one runs in wakes: [post] puts what woke it into its inbox, and when no wake
 * is out, the next one starts as a backend run in mode "task". Between wakes nothing is open and nothing costs. An
 * agent pauses while its player is away from where it started, waits for items its player brings ([Parked]), and
 * waits out a lost backend. It stops on its time limit, on `/allay cancel`, or when something gets in the way of its
 * job. When its player leaves or the server stops, it isn't stopped: its snapshot goes to the backend ([AgentStore])
 * and it comes back when they join again (since 6.35). What an agent does in the world is the platform's.
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

    /** [job] as data for the agent's snapshot; null when it can't come back after a restart. */
    fun snapshot(job: Job): JsonObject? = null

    /**
     * [job] from its snapshot, against the world as it is now: what the game did meanwhile counts, and news of it goes
     * to the agent by itself. Null when nothing of it is left to watch.
     */
    fun restore(task: Task, player: PlayerAudience, job: JsonObject): Job? = null

    /** Lets [job] go without undoing anything: its player left or the server stops, and the game carries on with it. */
    fun detach(job: Job) = job.cancel()

    /** A line on what [job] does now, for the agent's state card. */
    fun describe(job: Job): String? = null

    /** How many of each needed item the agent's sources hold now for [player], by [Need.item]. */
    fun count(task: Task, player: PlayerAudience, needs: List<Need>): Map<String, Int> = emptyMap()

    /** [item] as the catalog names it, or null for nothing this server has. */
    fun resolve(item: String): String? = item

    /** [item] as [player] reads it, in their language. */
    fun itemName(player: PlayerAudience, item: String): String = item

    /** The chests the player picked for [task] right now, as `world:x,y,z`. */
    fun picked(task: Task, player: PlayerAudience): List<String> = emptyList()

    /**
     * A builder's site: it takes over [player]'s preview as its job, which waits for the agent's go. Throws a
     * [ToolError] when there's nothing to build.
     */
    fun site(task: Task, player: PlayerAudience): Site = throw ToolError("building isn't available on this server")
  }

  /** Where a builder works and its job there; [limits] from its player's tier. */
  class Site(val anchor: Protocol.Position, val job: Job, val limits: TaskLimits)

  /** Something an agent left running in the world: a furnace load, a brew. */
  interface Job {
    fun cancel()
  }

  /** What Allay keeps about one agent beside its [Task]: where it started, what it picked, and its job. */
  class Agent(val task: Task, val anchor: Protocol.Position) {
    @Volatile var paused = false
    /** When the last tick counted its working time. */
    @Volatile var ticked = 0L
    /** When it last counted what its player brought, while it waits for items. */
    @Volatile var counted = 0L
    /** When its wakes started failing for a lost backend; 0 while the backend answers. */
    @Volatile var offlineSince = 0L
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

  /** Counts what each wake spends, set by [Allay] like [consents]. */
  @Volatile var usage: Usage? = null

  /** Where the agents wait for their players between runs, set by [Allay]; without it they end with the run, as before 6.35. */
  @Volatile var store: AgentStore? = null

  val book = TaskBook({ config().automation.maxTasks })
  val reservations = Reservations()

  private val agents = ConcurrentHashMap<String, Agent>()
  /** Kinds the backend refused, and when: start_task refuses them for a while instead of starting an agent that can't wake. */
  private val unknown = ConcurrentHashMap<Kind, Long>()
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

  /**
   * Shutdown, a disable, or a reload: every agent goes to the backend as it is and comes back when its player joins
   * again; owners still online hear so. Without a store they end, as before 6.35.
   */
  fun stop() {
    ticker?.close()
    val store = store
    if (store == null) {
      stopping = true
      book.all().forEach { task -> end(task, platform.player(task.owner)?.let { locales.of(it).text("automation.server_stopping") }) }
      return
    }
    book.all().map { it.owner }.distinct().forEach { owner ->
      platform.player(owner)?.let { player -> book.of(owner).forEach { line(player, it, "automation.server_pausing") } }
      suspend(owner)
    }
    stopping = true
    store.stop(SHUTDOWN_MS)
  }

  /**
   * For the tools: [task] is an agent of [kind], and its player may still run that kind (an admin may have taken the
   * permission away meanwhile). A chests agent can't smelt, so one permission never opens another.
   */
  fun require(player: Audience, task: Task, kind: Kind) {
    if (task.kind != kind) throw ToolError(mismatch(task.kind, kind))
    allowed(player, kind)?.let { throw ToolError(it) }
  }

  /**
   * Why an agent of [mine] can't do [asked]'s work, and what to do instead. Crafting and brewing agents kept taking
   * ingredients out of chests by hand, which their own tools already do.
   */
  private fun mismatch(mine: Kind, asked: Kind): String = when {
    asked == Kind.CHESTS && mine == Kind.CRAFTING -> "a crafting agent only reads chests: craft takes what it needs from the chests around by itself, so call craft; to move things for the player, start a chests sub-agent with start_task"
    asked == Kind.CHESTS && mine == Kind.BREWING -> "a brewing agent only reads chests: brew takes its ingredients, bottles, and fuel from the chests around by itself, so call brew; to move things for the player, start a chests sub-agent with start_task"
    asked == Kind.CHESTS && mine == Kind.FURNACES -> "a furnaces agent only reads chests: smelt takes what it cooks and its fuel from the chests around by itself, so call smelt; to move things for the player, start a chests sub-agent with start_task"
    else -> "this is a ${mine.wire} agent; ${asked.wire} needs its own, started with start_task"
  }

  /** Whether [player] may run automations of [kind] here: the switches in config.yml, then the permissions. */
  fun allowed(player: Audience, kind: Kind): String? = when {
    kind == Kind.BUILDING && !config().building.enabled -> "building is switched off on this server"
    kind == Kind.BUILDING && !player.can(Permissions.BUILD) -> "the player has no permission to build from the catalog"
    kind == Kind.BUILDING -> null
    !config().automation.on(kind) -> "automation of ${kind.wire} is switched off on this server"
    !player.can(Permissions.AUTOMATION) || !player.can(Permissions.automation(kind)) -> "the player has no permission for automation of ${kind.wire}"
    else -> null
  }

  /** Starts an agent for [player] with [brief]; [parent] is the agent that starts it, or null for the main chat. */
  fun launch(player: PlayerAudience, kind: Kind, name: String, brief: String, parent: Task?): Task {
    allowed(player, kind)?.let { throw ToolError(it) }
    unknown[kind]?.takeIf { System.currentTimeMillis() - it < UNKNOWN_MS }?.let { throw ToolError(unknownKind(kind)) }
    val task = book.start(player.uuid, kind, name, brief.take(2000), (parent?.depth ?: 0) + 1, parent?.id)
    // A sub-agent takes from where its parent may.
    parent?.let { task.scope = it.scope }
    val site = if (kind == Kind.BUILDING) {
      try {
        world.site(task, player)
      } catch (error: Throwable) {
        book.end(task)
        throw error
      }
    } else null
    task.limits = site?.limits
    agents[key(task)] = Agent(task, site?.anchor ?: player.position).also { it.job = site?.job }
    say(player, task, locales.of(player).text("automation.started"))
    platform.log.info("⚙️ Automation agent ${task.name} (${task.kind.wire}) started for ${player.name}")
    post(task, brief.take(2000))
    return task
  }

  /** Wakes [task] with [message] now, or right after the wake that's out. Safe from any thread. */
  fun post(task: Task, message: String) {
    if (task.done || stopping) return
    task.inbox += message
    onMain {
      wake(task)
      persist(task.owner)
    }
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
    persist(task.owner)
  }

  /** Stops [task] because someone took what it held or touched its blocks; the player hears what happened. */
  fun interfered(task: Task, reason: String) {
    val player = platform.player(task.owner) ?: return end(task, null)
    end(task, locales.of(player).text("automation.interfered", "reason" to reason))
  }

  /** The agent's `finish`: its last line, then it's gone. */
  fun finish(player: PlayerAudience, task: Task, summary: String) {
    // A summary the agent just said word for word isn't said twice.
    if (agents[key(task)]?.said != Legacy.strip(Legacy.color(summary))) line(player, task, "automation.finished", "text" to summary)
    platform.log.info("✅ Automation agent ${task.name} finished for ${player.name}")
    val agent = agents.remove(key(task))
    book.end(task)
    agent?.job?.cancel()
    reservations.release(task.id)
    world.stopPicking(player, task)
    tellParent(task, "finished: $summary")
    // Its sub-agents carry on: `finish` lets an agent go while they run only with handoff, when they own the rest.
    persist(player.uuid)
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

  /** The stop button under an agent's line: that agent alone. */
  fun stop(player: PlayerAudience, id: String) {
    val task = book.find(player.uuid, id) ?: return locales.of(player).send(player, "automation.gone")
    end(task, locales.of(player).text("automation.cancelled"))
  }

  /** A line about [task] with its stop button under it, for an agent that waits and might wait long. */
  private fun waiting(player: PlayerAudience, task: Task, key: String, vararg values: Pair<String, Any?>) {
    line(player, task, key, *values)
    clicks().show(player, listOf(Button.StopAgent(locales.of(player).text("buttons.stop_agent"), task.id)))
  }

  /** `/allay cancel` with no answer running: stops the player's agents. True when there were any. */
  fun cancel(player: PlayerAudience): Boolean {
    val tasks = book.of(player.uuid)
    tasks.forEach { end(it, locales.of(player).text("automation.cancelled")) }
    return tasks.isNotEmpty()
  }

  /**
   * The player left: their agents wait for them on the backend, jobs and all, and their picks go. Without a store they
   * end quietly, as before 6.35.
   */
  fun quit(player: PlayerAudience) {
    world.stopPicking(player, null)
    val store = store ?: return book.of(player.uuid).forEach { end(it, null) }
    suspend(player.uuid)
    store.flush(player.uuid)
  }

  /**
   * The player joined: the agents they had on this server come back from the backend as they were, their jobs against
   * the world as it is now. One that was mid-wake reads the world again first.
   */
  fun joined(player: PlayerAudience) {
    val store = store ?: return
    if (book.of(player.uuid).isNotEmpty()) return
    // Nothing about a player reaches the backend before they agree; only someone who agreed ever had agents.
    if (consents?.invoke()?.listed(player.uuid) != true) return
    scope.launch {
      val snapshots = store.load(player.uuid)?.ifEmpty { null } ?: return@launch
      onMain { restore(player, snapshots) }
    }
  }

  /** [owner]'s agents leave this run without ending: each one's snapshot goes to the backend, its job goes on in the world. */
  private fun suspend(owner: UUID) {
    val tasks = book.of(owner)
    if (tasks.isEmpty()) return
    store?.save(owner, tasks.mapNotNull(::snapshot))
    for (task in tasks) {
      val agent = agents.remove(key(task)) ?: continue
      agent.run?.cancel()
      agent.job?.let(world::detach)
      reservations.release(task.id)
      book.detach(task)
    }
  }

  /** What's kept of [task] until it comes back. */
  private fun snapshot(task: Task): JsonObject? {
    val agent = agents[key(task)] ?: return null
    val snapshot = AgentSnapshot(
      id = task.id, name = task.name, kind = task.kind.wire, depth = task.depth, parent = task.parent, brief = task.brief,
      started_at = task.startedAt, active_ms = task.activeMs, wakes = task.wakes, state = task.state.wire, question = task.question,
      wake_at = task.wakeAt, inbox = task.inbox.toList(), results = task.results(), anchor = agent.anchor, scope = task.scope,
      parked = task.parked, held = reservations.export(task.id), job = agent.job?.let(world::snapshot), limits = task.limits,
      broke = task.current?.takeIf { task.running },
    )
    return SNAPSHOTS.encodeToJsonElement(AgentSnapshot.serializer(), snapshot).jsonObject
  }

  /** Sends [owner]'s agents as they are now, a moment later; a burst of changes goes as one. */
  private fun persist(owner: UUID) {
    val store = store ?: return
    if (stopping) return
    store.save(owner, book.of(owner).mapNotNull(::snapshot))
  }

  /** Brings [player]'s agents back from [snapshots]: parents first, each with its job, its holds, and its place. */
  private fun restore(player: PlayerAudience, snapshots: List<JsonObject>) {
    if (platform.player(player.uuid) == null || book.of(player.uuid).isNotEmpty()) return
    val read = snapshots.mapNotNull { runCatching { SNAPSHOTS.decodeFromJsonElement(AgentSnapshot.serializer(), it) }.getOrNull() }.sortedBy { it.depth }
    for (snapshot in read) {
      val kind = Kind.entries.firstOrNull { it.wire == snapshot.kind } ?: continue
      // An admin may have switched the kind off or taken the permission away meanwhile.
      if (allowed(player, kind) != null) continue
      if (snapshot.parent != null && book.find(player.uuid, snapshot.parent) == null) continue
      val task = Task(snapshot.id, player.uuid, snapshot.name, kind, snapshot.depth, snapshot.parent, snapshot.brief, snapshot.started_at)
      task.activeMs = snapshot.active_ms
      task.wakes = snapshot.wakes
      task.state = Task.State.entries.firstOrNull { it.wire == snapshot.state && it != Task.State.DONE } ?: Task.State.WAITING
      task.question = snapshot.question
      task.wakeAt = snapshot.wake_at
      task.scope = snapshot.scope
      task.parked = snapshot.parked
      task.limits = snapshot.limits
      task.restore(snapshot.results)
      snapshot.inbox.forEach { task.inbox += it }
      // A wake that was out when it stopped may have run tools: it reads the world again before acting.
      snapshot.broke?.let { task.inbox.addFirst(task.resume(it)) }
      book.adopt(task)
      val agent = Agent(task, snapshot.anchor)
      agents[key(task)] = agent
      reservations.restore(task.id, snapshot.held)
      val job = snapshot.job
      if (job != null) {
        agent.job = runCatching { world.restore(task, player, job) }.onFailure { platform.log.warning("❌ Couldn't bring back the job of automation agent ${task.name}: $it") }.getOrNull()
        if (agent.job == null) task.inbox += "[plugin] Your job in the world couldn't come back after the stop: read the world again, then carry on or tell the player"
      }
      line(player, task, "automation.back")
      platform.log.info("⚙️ Automation agent ${task.name} (${task.kind.wire}) is back for ${player.name}")
      if (task.inbox.isNotEmpty() && task.parked == null) wake(task)
    }
    persist(player.uuid)
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
    if (!task.kind.solo && task.wakes >= settings.maxWakes) return end(task, locales.of(player).text("automation.too_many_steps"))
    // The gate has told the player already; the agent that started this one still hears why.
    if (!quota().allows(player)) return close(task, null, "stopped: ${locales.of(player).text("automation.quota")}")
    // Anything that wakes a parked agent ends its wait: the player wrote, so it decides again, and parks again if it must.
    val waited = task.parked?.also { task.parked = null }?.let { parked ->
      ("[plugin] You were waiting for the player to bring " + parked.needs.joinToString(", ") { "${it.count} × ${it.item}" } + "; this woke you meanwhile").take(Task.MESSAGE_LIMIT / 2) + "\n"
    }
    val message = waited?.let { it + task.drain(Task.MESSAGE_LIMIT - it.length) } ?: task.drain()
    task.wakes++
    task.running = true
    task.current = message
    task.card = card(task, agent, settings)
    task.keepUntil = System.currentTimeMillis() + parkHours(task) * 3_600_000L
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
        usage?.ran(player.uuid, run, command = null, answeredMillis = null)
        onMain { afterWake(task, player, message, failure, started = run.runId != null) }
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

  private fun afterWake(task: Task, player: PlayerAudience, message: String, failure: Exception?, started: Boolean) {
    task.running = false
    task.current = null
    if (task.done) return
    val agent = agents[key(task)] ?: return
    agent.run = null
    // A finish the plugin took stands, even when the stream broke after it.
    task.finished?.let { return finish(player, task, it) }
    if (failure != null && (Retry.passing(failure) || Retry.after(failure) != null)) return offline(task, agent, player, message, failure, started)
    if (failure == null && agent.offlineSince > 0) {
      agent.offlineSince = 0
      task.offline = false
      line(player, task, "automation.online")
    }
    if (refusesKind(failure)) {
      unknown[task.kind] = System.currentTimeMillis()
      platform.log.warning("❌ Automation agent ${task.name}: the backend doesn't know agents of kind ${task.kind.wire} yet; update it")
      return close(task, locales.of(player).text("automation.unknown_kind"), "stopped: ${unknownKind(task.kind)}")
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
    // Waiting for items counts as busy: what wakes it is the items, or the player.
    val busy = agent.job?.let(world::active) == true || book.children(task).isNotEmpty() || task.parked != null
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
    persist(task.owner)
  }

  /**
   * The backend is out of reach or busy, though the stream waited for it: the agent doesn't end, it waits too, for up to
   * `park_hours`, and its player hears so once. A wake that never started goes again as it was; one that did may have
   * run tools, so the agent reads the world first.
   */
  private fun offline(task: Task, agent: Agent, player: PlayerAudience, message: String, failure: Exception, started: Boolean) {
    val now = System.currentTimeMillis()
    task.inbox.addFirst(if (started) task.resume(message) else message)
    // Nothing that answered was paid for, so it doesn't count toward max_wakes either.
    if (!started) task.wakes--
    if (agent.offlineSince == 0L) {
      agent.offlineSince = now
      task.offline = true
      waiting(player, task, "automation.offline")
    } else if (now - agent.offlineSince > parkHours(task) * 3_600_000L) {
      return end(task, locales.of(player).text("automation.unreachable"))
    }
    retry(task, Retry.after(failure) ?: 0, "the backend couldn't take the wake ($failure)")
    persist(task.owner)
  }

  /** What the backend reads as the agent's state: everything it needs to carry on, even with its thread gone. */
  private fun card(task: Task, agent: Agent, settings: Config.Automation): String = buildString {
    if (task.kind.solo) append("Kind: ${task.kind.wire}. Worked ${task.activeMs / 60_000} min; wake ${task.wakes}; waits up to ${parkHours(task)} h for its player.")
    else append("Kind: ${task.kind.wire}. Worked ${task.activeMs / 60_000} of ${settings.maxMinutes} min; wake ${task.wakes} of ${settings.maxWakes}.")
    append("\nBrief: ").append(task.brief.take(800))
    val scope = task.scope
    append("\nSources: the inventory; the ender chest ${if (scope.ender_chest) "allowed" else "not allowed (ask the player first)"}; ")
    append(scope.chests?.let { "only the ${it.size} chests the player picked" } ?: "every chest around the player may open")
    agent.job?.let(world::describe)?.let { append("\nJob: ").append(it) }
    task.parked?.let { parked ->
      append("\nWaiting for the player to bring: ").append(parked.needs.joinToString(", ") { "${it.count} × ${it.item}" })
      parked.note?.let { append(" (").append(it).append(')') }
    }
    task.question?.let { append("\nOpen question to the player: ").append(it) }
    val done = task.results()
    if (done.isNotEmpty()) append("\nYour tools reported: ").append(done.joinToString("; "))
  }.take(CARD_LIMIT)

  /**
   * Parks [task] until its player brings [needs]: it holds no slot, its working time stands still, and it goes on once
   * the sources hold them, or when the player writes. True when it parked; false when everything is there already.
   */
  fun park(task: Task, player: PlayerAudience, needs: List<Need>, note: String?): Boolean {
    val have = world.count(task, player, needs)
    if (needs.all { (have[it.item] ?: 0) >= it.count }) return false
    val now = System.currentTimeMillis()
    task.parked = Parked(needs, now, now + parkHours(task) * 3_600_000L, now, note?.take(200))
    task.waited = true
    task.wakeAt = null
    waiting(player, task, "automation.parked", "items" to missing(player, needs, have))
    persist(task.owner)
    return true
  }

  /** What's still missing of [needs], as [player] reads it: “10 × Oak Log, 2 × Glass”. */
  fun missing(player: PlayerAudience, needs: List<Need>, have: Map<String, Int>): String =
    needs.mapNotNull { need -> (need.count - (have[need.item] ?: 0)).takeIf { it > 0 }?.let { "$it × ${world.itemName(player, need.item)}" } }.joinToString(", ")

  /** How many of each of [needs] the agent's sources hold now. */
  fun count(task: Task, player: PlayerAudience, needs: List<Need>): Map<String, Int> = world.count(task, player, needs)

  fun resolve(item: String): String? = world.resolve(item)

  fun picked(task: Task, player: PlayerAudience): List<String> = world.picked(task, player)

  /** [task]'s sources changed: the next snapshot keeps them. */
  fun changed(task: Task) = persist(task.owner)

  /** A parked agent looks at what its player brought, while they're at the base; once it's all there, it goes on. */
  private fun watch(task: Task, agent: Agent, player: PlayerAudience, parked: Parked, now: Long) {
    val settings = config().automation
    if (now > parked.until) return end(task, locales.of(player).text("automation.park_expired", "hours" to parkHours(task)))
    if (now - parked.reminded >= settings.remindMinutes * 60_000L) {
      val have = world.count(task, player, parked.needs)
      task.parked = parked.copy(reminded = now)
      waiting(player, task, "automation.still_waiting", "items" to missing(player, parked.needs, have))
      persist(task.owner)
    }
    if (agent.paused || now - agent.counted < WATCH_MS) return
    agent.counted = now
    val have = world.count(task, player, parked.needs)
    if (!parked.needs.all { (have[it.item] ?: 0) >= it.count }) return
    task.parked = null
    line(player, task, "automation.items_here")
    post(task, "[job] the items you waited for are here: " + parked.needs.joinToString(", ") { "${it.count} × ${it.item}" })
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
      val parked = task.parked
      // Only work counts toward the time limit: a pause, a wait for items, or a lost backend doesn't.
      if (!agent.paused && parked == null && agent.offlineSince == 0L && agent.ticked > 0) task.activeMs += (now - agent.ticked).coerceIn(0, 5_000)
      agent.ticked = now
      if (!task.kind.solo && task.activeMs > settings.maxMinutes * 60_000L) {
        end(task, locales.of(player).text("automation.timed_out", "minutes" to settings.maxMinutes))
        continue
      }
      val away = away(player.position, agent.anchor, task.limits?.pause_distance ?: settings.pauseDistance)
      if (away != agent.paused) {
        agent.paused = away
        // A parked agent just waits; it says so in its own lines.
        if (parked == null) line(player, task, if (away) "automation.paused" else "automation.resumed")
        if (!away) wake(task)
      }
      if (parked != null) {
        watch(task, agent, player, parked, now)
        continue
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

  /** How long [task] waits for its player: its tier's hours for a builder, `automation.park_hours` for the rest. */
  fun parkHours(task: Task): Int = task.limits?.park_hours ?: config().automation.parkHours

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
    /** How long a shutdown waits for the backend to take the agents. */
    private const val SHUTDOWN_MS = 3_000L

    /** How often a parked agent counts what its player brought, while they're at the base. */
    private const val WATCH_MS = 5_000L

    /** The most of a state card the backend takes. */
    const val CARD_LIMIT = 3000

    private val SNAPSHOTS = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

    /** What an agent hears after a wake that ended without a word to the player. */
    const val SILENT = "[plugin] Your last wake ended without say or finish and with nothing to wake you (no wait seconds, no job, no sub-agent, no question to the player), so the player heard nothing. Tell them in a line what happened and what's missing, then end with wait (seconds when something will finish) or finish."

    /**
     * Whether a wake left nothing to wake the agent: no line or buttons to the player, no open question, no job or
     * sub-agent running, nothing in the inbox, and no wait with seconds. A bare `wait` counts too, since it would hold a
     * slot until `max_minutes`; a timer instead would wake the agent again and again with nothing new, each wake paid.
     */
    fun stuck(said: Boolean, asked: Boolean, waited: Boolean, timer: Boolean, busy: Boolean, question: Boolean, inbox: Boolean): Boolean =
      !said && !asked && !(waited && timer) && !busy && !question && !inbox

    /** How long start_task refuses a kind the backend refused: an admin may update the backend meanwhile. */
    private const val UNKNOWN_MS = 10 * 60_000L

    private fun unknownKind(kind: Kind) = "this backend doesn't know agents of kind ${kind.wire} yet; do this part yourself or tell the player an admin can update the backend"

    /** The backend's answer to a kind it doesn't know: its own code since 6.22, a refused `task.kind` before. */
    fun refusesKind(failure: Throwable?): Boolean {
      val error = (failure as? ApiErrorException)?.apiError ?: return false
      return error.code == ErrorCodes.TASK_KIND_UNKNOWN || (error.code == ErrorCodes.BAD_REQUEST && "task.kind" in error.message)
    }

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
