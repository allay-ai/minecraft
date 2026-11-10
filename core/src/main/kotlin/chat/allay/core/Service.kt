package chat.allay.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import chat.allay.core.Protocol.Event
import chat.allay.core.tools.NOT_OPERATOR
import chat.allay.core.tools.TimeZones
import chat.allay.core.tools.ToolError
import chat.allay.core.tools.ToolRegistry
import java.util.concurrent.TimeoutException
import java.util.logging.Logger

/** What the backend client needs from the game platform; [C] is what its tools see of a run. */
interface Host<C> {
  val log: Logger
  val pluginVersion: String

  /** The server software and the game version, like `Paper` and `26.3`. */
  val software: String
  val gameVersion: String

  /** What Allay runs on: `spigot` for the plugin, `fabric` or `neoforge` for a mod. */
  val loader: String

  /**
   * Where Allay runs: `server` (a dedicated server), `integrated` (the server inside a player's own game: singleplayer or
   * LAN), or `client` (a mod in the player's game on a server without Allay).
   */
  val side: String get() = "server"

  /** The game's DataVersion: builds saved by a newer game may hold blocks this one lacks. */
  val dataVersion: Int? get() = null

  /** The address the game listens on when the server's config names one, and its port; for the backend's admin panel. */
  val address: String? get() = null
  val port: Int? get() = null

  /** What the tools of this run see: the asker and the plugin's stores. */
  fun context(run: Run): C

  /** Runs [block] on the game's main thread and waits at most [seconds] for it; throws [TimeoutException] when it takes longer. */
  fun <T> onMain(seconds: Long, block: () -> T): T
}

/** Talks to the Allay backend: the v2 stream with tools, or v1 when the backend is older. */
class Service<C>(
  private val config: Config,
  private val client: OkHttpClient,
  private val tools: ToolRegistry<C>,
  private val zones: TimeZones,
  /** Names the server's shared thread of public questions on the backend. */
  private val serverId: String,
  private val host: Host<C>,
  private val scope: CoroutineScope,
  /** Who pays: the headers go on through the client's interceptor, the `provider` block goes in the body here. */
  private val credentials: Credentials,
  /** The automation agents, so a question tells the backend which of the asker's are running. */
  private val taskBook: chat.allay.core.tasks.TaskBook? = null,
  /** A byok server's license as its identity on a hosted backend, fetched before the first request that needs it. */
  private val identity: Identity? = null,
  /** Whether a player has full privacy on, so each question tells the backend to keep them out of its admin panel. */
  private val fullPrivacy: (java.util.UUID) -> Boolean = { false },
  /** Other plugins' and mods' expansions: their prompt lines go with every question, their tools with the runs that offer them. */
  private val expansions: Expansions? = null,
) : Consents.Backend, PlayerData.Backend, chat.allay.core.tasks.AgentStore.Backend {
  @Serializable data class AskRequest(val question: String, val platform: String = "minecraft", val user_id: String? = null, val provider: Protocol.Provider? = null)
  @Serializable data class AskResponse(val answer: String, val status: String? = null, val timestamp: String? = null)
  @Serializable data class AskXRequest(val action: String, val platform: String = "minecraft", val user_id: String? = null, val provider: Protocol.Provider? = null)
  @Serializable data class AskXResponse(val isSequence: Boolean, val commands: List<String>, val status: String? = null, val timestamp: String? = null, val error: String? = null)

  /** How a run ended. */
  data class Outcome(val answer: String, val commands: Protocol.Commands?)

  /** What the display needs to hear about while the stream runs. Called off the server thread. */
  interface Listener {
    fun toolStarted(callId: String, name: String, args: kotlinx.serialization.json.JsonObject?)
    /** [failed]: an error, its reason written for the model; [refused]: the tool answered no, its summary in the player's language. */
    fun toolFinished(callId: String, summary: String?, failed: Boolean, refused: Boolean = false)
    /** The backend stayed out of reach for a few tries and the question waits to reconnect; once a question. */
    fun reconnecting() {}
  }

  private class V2Unsupported : Exception()

  private companion object {
    /** Running agents a request lists: all that `automation.max_tasks` allows. Backends before 6.22 take [OLD_MAX_TASKS]. */
    const val MAX_TASKS = 8
    const val OLD_MAX_TASKS = 4
    /** Failed tries before the player hears the backend is out of reach. */
    const val TELL_AFTER = 3
  }

  // encodeDefaults sends platform: without it the backend files v1 requests under "unknown".
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
  }
  private val errorParser = ErrorParser()

  /** The answer's streams: silence past `http.stream_idle_seconds` means the connection is gone, though no error came. */
  private val streams = client.newBuilder().readTimeout(config.http.streamIdleSeconds, java.util.concurrent.TimeUnit.SECONDS).build()
  private val jsonType = "application/json".toMediaType()

  suspend fun chat(run: Run, message: String, listener: Listener): Outcome = withContext(IO) {
    try {
      stream(run, message, listener)
    } catch (_: V2Unsupported) {
      host.log.info("ℹ️ The backend has no /api/v2/chat; using v1")
      legacy(run, message)
    }
  }

  /**
   * The question's stream, kept through a lost backend: on a dropped connection or a backend out of reach, it reaches out
   * again with short pauses for `http.reconnect_seconds`, and reads on after the last event it got (backend 6.31) or asks
   * again under the same request id, which never runs twice. A backend before 6.31 numbers no events: a stream that
   * broke after its run started then fails, as it always did.
   */
  private suspend fun stream(run: Run, message: String, listener: Listener): Outcome {
    val reach = Reach(config.http.reconnectSeconds)
    var taskLimit = MAX_TASKS
    var failures = 0
    run.onCancel = { cancelOnBackend(run) }
    while (true) {
      try {
        identity?.ensure()
        return read(run, message, listener, taskLimit) { reach.reached() }
      } catch (_: TooManyTasks) {
        // An older backend refuses more than four; the newest four go again rather than the question failing.
        taskLimit = OLD_MAX_TASKS
      } catch (ended: Ended) {
        throw ended.error
      } catch (error: Exception) {
        if (run.cancelled || error is V2Unsupported) throw error
        // Mid-answer on a backend that can't read on: what it did stays lost, and the error goes through as before.
        if (run.runId != null && !run.resumable) throw error
        val seconds = reach.next(error) ?: throw if (Retry.passing(error)) Unreachable(error) else error
        run.reconnecting = true
        // A blip passes unnoticed; after the third try the player hears it and may stop the question.
        if (++failures == TELL_AFTER) listener.reconnecting()
        host.log.info("⏳ Lost the backend ($error); reaching out again in $seconds s")
        delay(seconds * 1000)
      }
    }
  }

  /** The stream refused more running agents than an older backend takes. */
  private class TooManyTasks : Exception()

  /** The run itself failed on the backend: asking again would only read the same end. */
  private class Ended(val error: ApiErrorException) : Exception(error.message, error)

  /** One connection of [stream]: the question, or the rest of its run after [Run.lastEventId]. [reached] fires on each new event. */
  private fun read(run: Run, message: String, listener: Listener, taskLimit: Int, reached: () -> Unit): Outcome {
    val runId = run.runId
    val builder = Request.Builder().header("Accept", "text/event-stream").header("X-Plugin-Version", host.pluginVersion)
    if (run.lastEventId > 0) builder.header("Last-Event-ID", run.lastEventId.toString())
    val reading = runId != null && run.resumable
    if (reading) {
      builder.url("${config.server.url}/api/v2/chat/$runId/events").get()
    } else {
      val body = Protocol.json.encodeToString(Protocol.ChatRequest.serializer(), request(run, message, taskLimit))
      builder.url("${config.server.url}/api/v2/chat").post(body.toRequestBody(jsonType))
    }
    val call = streams.newCall(builder.build())
    run.call = call
    if (run.cancelled) call.cancel()
    call.execute().use { response ->
      if (!reading && (response.code == 404 || response.code == 405)) throw V2Unsupported()
      if (!response.isSuccessful) {
        val error = errorParser.parseErrorFromResponse(response)
        if (!reading && error.code == ErrorCodes.BAD_REQUEST && "tasks" in error.message && taskLimit > OLD_MAX_TASKS) throw TooManyTasks()
        throw failure(error)
      }

      var outcome: Outcome? = null
      var failed: Protocol.ErrorEvent? = null
      val parser = SseParser { name, data ->
        when (val event = Protocol.decode(name, data)) {
          is Event.Started -> run.runId = event.data.run_id
          is Event.Reasoning -> run.addReasoning(event.delta)
          is Event.Text -> run.addText(event.delta)
          is Event.Status -> status(run, event.data, listener)
          is Event.Call -> {
            if (run.announced.add(event.data.call_id)) listener.toolStarted(event.data.call_id, event.data.name, event.data.args)
            if (run.handled.add(event.data.call_id)) scope.launch { answerTool(run, event.data, listener) }
          }
          is Event.Command -> run.commands = event.data
          is Event.Spent -> {
            run.usage(event.data.input_tokens, event.data.output_tokens + event.data.reasoning_tokens)
            run.cost = maxOf(run.cost, event.data.cost)
          }
          is Event.Finished -> outcome = Outcome(event.data.answer.ifBlank { run.answer() }, run.commands)
          is Event.Failed -> failed = event.data
          null -> {}
        }
      }
      val source = response.body.source()
      while (!run.cancelled && outcome == null && failed == null) {
        val line = source.readUtf8Line() ?: break
        parser.line(line)
        parser.lastId?.toLongOrNull()?.let { id ->
          if (id > run.lastEventId) {
            run.lastEventId = id
            run.resumable = true
            run.reconnecting = false
            reached()
          }
        }
      }
      parser.end()
      failed?.let { throw Ended(failure(ApiError(it.code, it.message, resetsAt = it.resets_at, retryAfter = it.retry_after))) }
      outcome?.let { return it }
      // A stream with ids that ends before done was cut off: the next connection reads on.
      if (run.resumable && !run.cancelled) throw java.io.EOFException("the stream ended before the answer")
      return Outcome(run.answer(), run.commands).takeIf { it.answer.isNotBlank() || run.commands != null }
        ?: throw ApiErrorException(ApiError("NO_AI_RESPONSE", "The stream ended without an answer"))
    }
  }

  /** Stops the run on the backend too, which since 6.31 waits for a plugin that went away instead of stopping. */
  private fun cancelOnBackend(run: Run) {
    val runId = run.runId?.takeIf { run.resumable } ?: return
    scope.launch(IO) {
      runCatching { client.newCall(Request.Builder().url("${config.server.url}/api/v2/chat/$runId").delete().build()).execute().close() }
        .onFailure { host.log.info("ℹ️ Couldn't tell the backend the question was cancelled: $it") }
    }
  }

  private fun status(run: Run, status: Protocol.ToolStatus, listener: Listener) {
    when (status.state) {
      "started" -> if (run.announced.add(status.call_id)) listener.toolStarted(status.call_id, status.name, status.args)
      // The plugin reports its own tools when it posts their results; only server tools finish here.
      "finished", "failed" -> if (status.kind == "server") listener.toolFinished(status.call_id, serverSummary(run.lang, status), status.state == "failed")
    }
  }

  /** A server tool's summary in the reader's language when the backend sent a key the language file knows, else its English text. */
  private fun serverSummary(lang: Lang, status: Protocol.ToolStatus): String? {
    val key = status.summary_key?.let { "summary.server.$it" }?.takeIf(lang::has) ?: return status.summary
    val values = status.summary_values.orEmpty().mapNotNull { (name, value) -> (value as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { name to it } }
    return lang.text(key, *values.toTypedArray())
  }

  private fun request(run: Run, message: String, taskLimit: Int): Protocol.ChatRequest {
    val asker = run.asker
    val uuid = asker.uuid
    return Protocol.ChatRequest(
      mode = run.mode.wire,
      message = message,
      visibility = if (run.public) "public" else "private",
      user_id = uuid?.toString() ?: "console",
      locale = uuid?.let { asker.locale },
      timezone = uuid?.let(zones::of) ?: config.time.zone,
      player = uuid?.let { Protocol.PlayerInfo(asker.name, it.toString(), asker.operator, asker.dimension, private = if (fullPrivacy(it)) true else null, position = asker.position, game_mode = asker.gameMode) },
      server = Protocol.ServerInfo(serverId, host.software, host.gameVersion, host.pluginVersion, config.server.address.ifEmpty { host.address }, host.port, host.loader, host.side,
        // Only questions use it, so actions and automation agents don't carry it.
        about = config.server.about.takeIf { it.isNotEmpty() && run.mode == Run.Mode.ASK }),
      // An automation agent never gets operator tools, whoever started it.
      tools = tools.offered(isOperator(asker) && run.task == null),
      provider = credentials.block(run.model),
      task = run.task?.let { Protocol.TaskInfo(it.id, it.name, it.depth, it.kind.wire, it.card, it.keepUntil) },
      tasks = when (run.mode) {
        Run.Mode.ASK -> tasks(run, taskLimit).takeIf { it.isNotEmpty() }
        // An agent sees its own running sub-agents, so it never claims one works after it ended.
        Run.Mode.TASK -> tasks(run, taskLimit)
        else -> null
      },
      expansion_tools = run.expansionTools.ifEmpty { null },
      expansions = expansions?.prompts(run.mode)?.ifEmpty { null },
      request_id = run.requestId,
      building = building(run),
    )
  }

  /** What the asker may build, for a run that offers `build`: the backend's search keeps to it. */
  private fun building(run: Run): Protocol.Building? {
    val asker = run.asker
    if (run.task != null || asker.uuid == null || !config.building.enabled || !asker.can(Permissions.BUILD) || tools.find("build") == null) return null
    val limits = config.building.tiers.of(asker.operator) { asker.can(Permissions.build(it)) }
    return Protocol.Building(limits.maxBlocks.takeIf { it < Int.MAX_VALUE }, limits.maxSize.takeIf { it < Int.MAX_VALUE }, host.dataVersion)
  }

  /** The error with the key and the license masked out of its message, should the backend ever echo them. */
  private fun failure(error: ApiError) = ApiErrorException(error.copy(message = credentials.scrub(error.message)))

  /** Only a player can be an operator here: the console asks without the tools that change the game. */
  private fun isOperator(asker: Asker) = asker.uuid != null && asker.operator

  /** The asker's running automation agents for the main chat's prompt, or a waking agent's own sub-agents. */
  private fun tasks(run: Run, limit: Int): List<Protocol.TaskBrief> = run.asker.uuid?.let { owner ->
    taskBook?.of(owner)?.filter { run.task == null || it.parent == run.task.id }?.mapNotNull { task ->
      // The server thread may end a task meanwhile; the backend knows no state "done" and would refuse the request.
      val state = task.state.takeIf { it != chat.allay.core.tasks.Task.State.DONE } ?: return@mapNotNull null
      Protocol.TaskBrief(task.id, task.name, state.wire, task.question?.take(200))
    }?.takeLast(limit)
  }.orEmpty()

  /** Runs a client tool on the server thread and posts what came out; failures go back as errors, never as exceptions. */
  private suspend fun answerTool(run: Run, call: Protocol.ToolCall, listener: Listener) {
    val tool = tools.find(call.name)
    val context = host.context(run)
    val clock = ToolClock()
    val answer = if (expansions != null && expansions.isExpansionTool(call.name)) {
      expansionTool(run, call, context, listener, clock)
    } else if (tool == null) {
      Protocol.ToolResult(ok = false, error = "the tool ${call.name} is switched off on this server")
    } else if (tool.operator && (!isOperator(run.asker) || run.task != null)) {
      listener.toolFinished(call.call_id, NOT_OPERATOR, failed = true)
      Protocol.ToolResult(ok = false, error = NOT_OPERATOR)
    } else {
      try {
        val output = if (tool.offMain) clock.run { tool.run(context, call.args) } else host.onMain(config.http.toolTimeout) { clock.run { tool.run(context, call.args) } }
        listener.toolFinished(call.call_id, output.summary, failed = false, refused = output.refused)
        Protocol.ToolResult(ok = true, result = output.result)
      } catch (error: Exception) {
        val cause = generateSequence<Throwable>(error) { it.cause }.firstOrNull { it is ToolError } ?: generateSequence<Throwable>(error) { it.cause }.last()
        val reason = when (cause) {
          is ToolError -> cause.message ?: "failed"
          is TimeoutException -> "the server took too long"
          else -> "failed: ${cause.javaClass.simpleName}".also { host.log.warning("❌ Tool ${call.name} failed: $cause") }
        }
        listener.toolFinished(call.call_id, reason, failed = true)
        // The backend refuses an error over 2000 characters, and the model would wait out the timeout instead.
        Protocol.ToolResult(ok = false, error = reason.take(2000))
      }
    }
    deliver(run, call, answer.copy(timing = clock.timing()))
  }

  /**
   * Posts a tool's result, again and again while the backend is out of reach: since 6.31 the run waits for it. `409` means
   * an earlier try got through, `404` that the run is over; either way there's nothing left to send.
   */
  private suspend fun deliver(run: Run, call: Protocol.ToolCall, result: Protocol.ToolResult) {
    val reach = Reach(config.http.reconnectSeconds)
    val body = Protocol.json.encodeToString(Protocol.ToolResult.serializer(), result)
    while (!run.cancelled) {
      val runId = run.runId ?: return
      try {
        client.newCall(Request.Builder().url("${config.server.url}/api/v2/chat/$runId/tools/${call.call_id}").post(body.toRequestBody(jsonType)).build()).execute().use { response ->
          if (response.isSuccessful || response.code == 404 || response.code == 409) return
          throw failure(errorParser.parseErrorFromResponse(response))
        }
      } catch (error: Exception) {
        val seconds = reach.next(error) ?: return host.log.warning("❌ Couldn't send the result of ${call.name}: $error")
        delay(seconds * 1000)
      }
    }
  }

  /** An expansion's tool, only one this run offered, on the server thread like any other; one that answers later is awaited here, off it. */
  private fun expansionTool(run: Run, call: Protocol.ToolCall, context: C, listener: Listener, clock: ToolClock): Protocol.ToolResult {
    val scope = context as? chat.allay.core.tools.ToolScope
    if (scope == null || run.expansionTools.none { it.name == call.name }) {
      listener.toolFinished(call.call_id, "off", failed = true)
      return Protocol.ToolResult(ok = false, error = "the tool ${call.name} isn't offered in this run")
    }
    return try {
      val started = host.onMain(config.http.toolTimeout) { clock.start(); expansions!!.run(call.name, scope, run.mode, run.public, call.args) }
      val output = expansions!!.finish(started, config.http.toolTimeout).also { clock.stop() }
      listener.toolFinished(call.call_id, output.summary, failed = false, refused = output.refused)
      Protocol.ToolResult(ok = true, result = output.result)
    } catch (error: Exception) {
      val cause = generateSequence<Throwable>(error) { it.cause }.firstOrNull { it is ToolError } ?: generateSequence<Throwable>(error) { it.cause }.last()
      val reason = when (cause) {
        is ToolError -> cause.message ?: "failed"
        is TimeoutException -> "the server took too long"
        // Someone else's code: its own trouble goes to the log under its name, the model hears only that it failed.
        else -> "failed".also { host.log.warning("❌ Expansion tool ${call.name} failed: $cause") }
      }
      listener.toolFinished(call.call_id, reason, failed = true)
      Protocol.ToolResult(ok = false, error = reason.take(2000))
    }
  }

  /**
   * How long a tool waited for the server thread and then ran, for the backend's trace (6.24). A tool that never
   * started reports nothing; one that failed reports what it took until then.
   */
  private class ToolClock {
    private val received = System.nanoTime()
    @Volatile private var began = 0L
    @Volatile private var ended = 0L

    fun start() { began = System.nanoTime() }
    fun stop() { ended = System.nanoTime() }

    fun <T> run(block: () -> T): T {
      start()
      try { return block() } finally { stop() }
    }

    fun timing(): Protocol.Timing? {
      if (began == 0L) return null
      val end = if (ended == 0L) System.nanoTime() else ended
      return Protocol.Timing(queued_ms = (began - received) / 1_000_000, run_ms = (end - began) / 1_000_000)
    }
  }

  /**
   * A remembered fact; since backend 6.9 one may have a [place], as notes once did. Since 6.27 it has a [kind]
   * (fact, preference, goal, place), a [title] for the list, [tags], a [pinned] flag, and a place a [category].
   */
  @Serializable data class Memory(
    val id: String, val text: String, val place: Place? = null, val created_at: String? = null,
    val kind: String? = null, val title: String? = null, val tags: List<String> = emptyList(), val pinned: Boolean = false, val category: String? = null,
  ) {
    /** What the list shows: the title, or the text. */
    val label get() = title ?: text
  }
  @Serializable data class Place(val x: Int, val y: Int, val z: Int, val dimension: String, val world: String? = null, val radius: Int? = null)
  @Serializable private data class Memories(val memories: List<Memory> = emptyList())
  @Serializable private data class Deleted(val deleted: Int = 0)
  @Serializable private data class Remember(val text: String, val place: Place? = null, val title: String? = null, val tags: List<String>? = null)
  @Serializable private data class Remembered(val memory: Memory)

  /**
   * The memory window's edits (backend 6.27): what [changes] holds replaces what was, an empty title removes it.
   * Too many pinned answers `MEMORY_PINS_FULL`.
   */
  suspend fun change(userId: String, id: String, changes: JsonObject): Memory = withContext(IO) {
    json.decodeFromString<Remembered>(send("/api/v2/users/${path(userId)}/memories/${path(id)}", "PATCH", changes.toString())).memory
  }

  /** `/allay new`: the backend forgets the conversation but keeps the memories. */
  suspend fun resetSession(userId: String) = withContext(IO) { send("/api/v2/users/${path(userId)}/session", "DELETE") }

  suspend fun memories(userId: String): List<Memory> = withContext(IO) {
    json.decodeFromString<Memories>(send("/api/v2/users/${path(userId)}/memories", "GET")).memories
  }

  /**
   * `/allay memory add <text>` (the memory window) and the note button: saves a fact as the memories tool would, with a [place] (backend 6.9;
   * an older one drops it). A full memory answers `MEMORY_FULL`.
   */
  suspend fun remember(userId: String, text: String, place: Place? = null, title: String? = null, tags: List<String> = emptyList()): Memory = withContext(IO) {
    val body = json.encodeToString(Remember.serializer(), Remember(text, place, title, tags.ifEmpty { null }))
    json.decodeFromString<Remembered>(send("/api/v2/users/${path(userId)}/memories", "POST", body)).memory
  }

  @Serializable data class Erased(val erased: Boolean = false, val memories: Int = 0, val runs: Int = 0, val traces: Int = 0)

  @Serializable data class ExportLink(val url: String, val expires_at: String? = null)

  /** `/allay privacy export`: a link to everything the backend keeps about the player, good for 24 hours (backend 6.9). */
  suspend fun exportLink(userId: String): ExportLink = withContext(IO) {
    json.decodeFromString<ExportLink>(send("/api/v2/users/${path(userId)}/export", "POST", "{}"))
  }

  @Serializable private data class StateAnswer(val state: PlayerState? = null, val updated_at: String? = null)

  /** The player's time zone, settings, and goals, kept by the backend since 6.9. */
  override suspend fun state(userId: String): PlayerState? = withContext(IO) {
    json.decodeFromString<StateAnswer>(send("/api/v2/users/${path(userId)}/state", "GET")).state
  }

  override suspend fun saveState(userId: String, state: PlayerState) = withContext(IO) {
    send("/api/v2/users/${path(userId)}/state", "PUT", json.encodeToString(PlayerState.serializer(), state))
    Unit
  }

  @Serializable private data class AgentsAnswer(val agents: List<JsonObject> = emptyList())
  @Serializable private data class AgentsBody(val server_id: String, val agents: List<JsonObject>)

  /** A player's automation agents on this server (backend 6.31), to bring them back after a rejoin or a restart. */
  override suspend fun agents(userId: String, serverId: String): List<JsonObject> = withContext(IO) {
    json.decodeFromString<AgentsAnswer>(workState(userId) { route -> send("$route?server_id=${path(serverId)}", "GET") }).agents
  }

  override suspend fun saveAgents(userId: String, serverId: String, agents: List<JsonObject>) = withContext(IO) {
    workState(userId) { route -> send(route, "PUT", json.encodeToString(AgentsBody.serializer(), AgentsBody(serverId, agents))) }
    Unit
  }

  /** The player's work state on the backend: `/workstate` since 6.32, `/agents` on 6.31.0, which knew only that name. */
  private fun workState(userId: String, call: (String) -> String): String = try {
    call("/api/v2/users/${path(userId)}/workstate")
  } catch (error: ApiErrorException) {
    if (error.apiError.statusCode != 404) throw error
    call("/api/v2/users/${path(userId)}/agents")
  }

  /** One death, for the history the backend pages for the model (6.9). */
  override suspend fun death(userId: String, death: chat.allay.core.tools.Death) = withContext(IO) {
    send("/api/v2/users/${path(userId)}/deaths", "POST", json.encodeToString(chat.allay.core.tools.Death.serializer(), death))
    Unit
  }

  /** `/allay privacy erase`: the backend forgets the player on this server; their questions stay only as numbers. */
  suspend fun erase(userId: String): Erased = withContext(IO) { json.decodeFromString<Erased>(send("/api/v2/users/${path(userId)}", "DELETE")) }

  @Serializable private data class Register(
    val id: String,
    val name: String? = null,
    val motd: String? = null,
    val software: String,
    val version: String,
    val plugin_version: String,
    val address: String? = null,
    val port: Int? = null,
    val loader: String? = null,
    val side: String? = null,
  )

  /**
   * At start and on reload: the server's name, MOTD, and address for the backend's admin panel (since backend 6.7).
   * A name another server on the backend holds fails with `NAME_TAKEN`.
   */
  suspend fun register(motd: String?) = withContext(IO) {
    val body = Register(serverId, config.server.name.ifEmpty { null }, motd, host.software, host.gameVersion, host.pluginVersion, config.server.address.ifEmpty { host.address }, host.port, host.loader, host.side)
    send("/api/v2/server", "PUT", json.encodeToString(Register.serializer(), body))
    Unit
  }

  @Serializable private data class Privacy(val full: Boolean)
  @Serializable data class PrivacyAnswer(val full: Boolean = false, val forgotten: Boolean = false)

  /** `/allay privacy full`: the admin panel forgets the player's name at once, or names them again from the next question (since backend 6.5). */
  suspend fun privacy(userId: String, full: Boolean): PrivacyAnswer = withContext(IO) {
    json.decodeFromString<PrivacyAnswer>(send("/api/v2/users/${path(userId)}/privacy", "PUT", json.encodeToString(Privacy.serializer(), Privacy(full))))
  }

  @Serializable data class Consent(val version: Int, val hash: String? = null, val revision: String? = null, val accepted_at: String? = null, val age_confirmed: Boolean? = null)
  @Serializable private data class ConsentAnswer(val consent: Consent? = null, val revision: String? = null)
  @Serializable private data class GiveConsent(val version: Int, val hash: String? = null, val age_confirmed: Boolean? = null)
  @Serializable private data class Withdrawn(val withdrawn: Boolean = false)

  /** The notice the player agreed to (since backend 6.1), and the policy revision with its hash (6.9). */
  override suspend fun consent(userId: String): Consents.Answer = withContext(IO) {
    val answer = json.decodeFromString<ConsentAnswer>(send("/api/v2/users/${path(userId)}/consent", "GET"))
    Consents.Answer(answer.consent?.let { Consents.Given(it.version, it.hash, it.accepted_at, it.age_confirmed) }, answer.revision)
  }

  override suspend fun giveConsent(userId: String, version: Int, hash: String?, ageConfirmed: Boolean?) = withContext(IO) {
    send("/api/v2/users/${path(userId)}/consent", "PUT", json.encodeToString(GiveConsent.serializer(), GiveConsent(version, hash, ageConfirmed)))
    Unit
  }

  override suspend fun withdrawConsent(userId: String): Boolean = withContext(IO) {
    json.decodeFromString<Withdrawn>(send("/api/v2/users/${path(userId)}/consent", "DELETE")).withdrawn
  }

  /** Forgets one memory, or all of them when [id] is null; returns how many went. */
  suspend fun forget(userId: String, id: String?): Int = withContext(IO) {
    val suffix = id?.let { "/${path(it)}" } ?: ""
    json.decodeFromString<Deleted>(send("/api/v2/users/${path(userId)}/memories$suffix", "DELETE")).deleted
  }

  private fun path(segment: String) = java.net.URLEncoder.encode(segment, Charsets.UTF_8).replace("+", "%20")

  private fun send(path: String, method: String, body: String? = null): String {
    identity?.ensure()
    val request = Request.Builder().url("${config.server.url}$path").method(method, body?.toRequestBody(jsonType)).build()
    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) throw failure(errorParser.parseErrorFromResponse(response))
      return response.body.string()
    }
  }

  /** The v1 API: one request, one answer, no tools. Kept for backends that predate v2. */
  private fun legacy(run: Run, message: String): Outcome {
    identity?.ensure()
    val userId = run.asker.uuid?.toString() ?: "console"
    return when (run.mode) {
      Run.Mode.ASK -> {
        val body = json.encodeToString(AskRequest.serializer(), AskRequest(message, user_id = userId, provider = credentials.block()))
        val answer = execute(run, "/api/ask", body) { json.decodeFromString<AskResponse>(it).answer }
        Outcome(answer, null)
      }
      Run.Mode.ASKX -> {
        val body = json.encodeToString(AskXRequest.serializer(), AskXRequest(message, user_id = userId, provider = credentials.block()))
        val response = execute(run, "/api/askx", body) { json.decodeFromString<AskXResponse>(it) }
        response.error?.let { throw ApiErrorException(ApiError("AI_ERROR", it)) }
        Outcome("", Protocol.Commands(response.isSequence, response.commands))
      }
      Run.Mode.TASK -> throw ApiErrorException(ApiError("V2_REQUIRED", "Automation needs an Allay Server with protocol v2"))
    }
  }

  private fun <T> execute(run: Run, path: String, body: String, read: (String) -> T): T {
    val call = client.newCall(Request.Builder().url("${config.server.url}$path").post(body.toRequestBody(jsonType)).build())
    run.call = call
    call.execute().use { response ->
      if (!response.isSuccessful) throw failure(errorParser.parseErrorFromResponse(response))
      return read(response.body.string())
    }
  }

}
