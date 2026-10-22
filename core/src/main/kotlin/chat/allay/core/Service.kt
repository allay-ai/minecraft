package chat.allay.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
) : Consents.Backend, PlayerData.Backend {
  @Serializable data class AskRequest(val question: String, val platform: String = "minecraft", val user_id: String? = null, val provider: Protocol.Provider? = null)
  @Serializable data class AskResponse(val answer: String, val status: String? = null, val timestamp: String? = null)
  @Serializable data class AskXRequest(val action: String, val platform: String = "minecraft", val user_id: String? = null, val provider: Protocol.Provider? = null)
  @Serializable data class AskXResponse(val isSequence: Boolean, val commands: List<String>, val status: String? = null, val timestamp: String? = null, val error: String? = null)

  /** How a run ended. */
  data class Outcome(val answer: String, val commands: Protocol.Commands?)

  /** What the display needs to hear about while the stream runs. Called off the server thread. */
  interface Listener {
    fun toolStarted(callId: String, name: String, args: kotlinx.serialization.json.JsonObject?)
    fun toolFinished(callId: String, summary: String?, failed: Boolean)
  }

  private class V2Unsupported : Exception()

  // encodeDefaults sends platform: without it the backend files v1 requests under "unknown".
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
  }
  private val errorParser = ErrorParser()
  private val jsonType = "application/json".toMediaType()

  suspend fun chat(run: Run, message: String, listener: Listener): Outcome = withContext(IO) {
    try {
      stream(run, message, listener)
    } catch (_: V2Unsupported) {
      host.log.info("ℹ️ The backend has no /api/v2/chat; using v1")
      legacy(run, message)
    }
  }

  private fun stream(run: Run, message: String, listener: Listener): Outcome {
    identity?.ensure()
    val body = Protocol.json.encodeToString(Protocol.ChatRequest.serializer(), request(run, message))
    val call = client.newCall(
      Request.Builder()
        .url("${config.server.url}/api/v2/chat")
        .header("Accept", "text/event-stream")
        .header("X-Plugin-Version", host.pluginVersion)
        .post(body.toRequestBody(jsonType))
        .build(),
    )
    run.call = call
    call.execute().use { response ->
      if (response.code == 404 || response.code == 405) throw V2Unsupported()
      if (!response.isSuccessful) throw failure(errorParser.parseErrorFromResponse(response))

      var outcome: Outcome? = null
      var commands: Protocol.Commands? = null
      var failed: Protocol.ErrorEvent? = null
      val parser = SseParser { name, data ->
        when (val event = Protocol.decode(name, data)) {
          is Event.Started -> run.runId = event.data.run_id
          is Event.Reasoning -> run.addReasoning(event.delta)
          is Event.Text -> run.addText(event.delta)
          is Event.Status -> status(run, event.data, listener)
          is Event.Call -> {
            if (run.announced.add(event.data.call_id)) listener.toolStarted(event.data.call_id, event.data.name, event.data.args)
            scope.launch { answerTool(run, event.data, listener) }
          }
          is Event.Command -> commands = event.data
          is Event.Spent -> {
            run.usage(event.data.input_tokens, event.data.output_tokens + event.data.reasoning_tokens)
            run.cost = maxOf(run.cost, event.data.cost)
          }
          is Event.Finished -> outcome = Outcome(event.data.answer.ifBlank { run.answer() }, commands)
          is Event.Failed -> failed = event.data
          null -> {}
        }
      }
      val source = response.body.source()
      while (!run.cancelled && outcome == null && failed == null) {
        val line = source.readUtf8Line() ?: break
        parser.line(line)
      }
      parser.end()
      failed?.let { throw failure(ApiError(it.code, it.message, resetsAt = it.resets_at, retryAfter = it.retry_after)) }
      return outcome ?: Outcome(run.answer(), commands).takeIf { it.answer.isNotBlank() || commands != null }
        ?: throw ApiErrorException(ApiError("NO_AI_RESPONSE", "The stream ended without an answer"))
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

  private fun request(run: Run, message: String): Protocol.ChatRequest {
    val asker = run.asker
    val uuid = asker.uuid
    return Protocol.ChatRequest(
      mode = run.mode.wire,
      message = message,
      visibility = if (run.public) "public" else "private",
      user_id = uuid?.toString() ?: "console",
      locale = uuid?.let { asker.locale },
      timezone = uuid?.let(zones::of) ?: config.time.zone,
      player = uuid?.let { Protocol.PlayerInfo(asker.name, it.toString(), asker.operator, asker.dimension, private = if (fullPrivacy(it)) true else null, position = asker.position) },
      server = Protocol.ServerInfo(serverId, host.software, host.gameVersion, host.pluginVersion, config.server.address.ifEmpty { host.address }, host.port),
      // An automation agent never gets operator tools, whoever started it.
      tools = tools.offered(isOperator(asker) && run.task == null),
      provider = credentials.block(run.model),
      task = run.task?.let { Protocol.TaskInfo(it.id, it.name, it.depth, it.kind.wire) },
      tasks = when (run.mode) {
        Run.Mode.ASK -> tasks(run).takeIf { it.isNotEmpty() }
        // An agent sees its own running sub-agents, so it never claims one works after it ended.
        Run.Mode.TASK -> tasks(run)
        else -> null
      },
    )
  }

  /** The error with the key and the license masked out of its message, should the backend ever echo them. */
  private fun failure(error: ApiError) = ApiErrorException(error.copy(message = credentials.scrub(error.message)))

  /** Only a player can be an operator here: the console asks without the tools that change the game. */
  private fun isOperator(asker: Asker) = asker.uuid != null && asker.operator

  /** The asker's running automation agents for the main chat's prompt, or a waking agent's own sub-agents. */
  private fun tasks(run: Run): List<Protocol.TaskBrief> = run.asker.uuid?.let { owner ->
    taskBook?.of(owner)?.filter { run.task == null || it.parent == run.task.id }?.mapNotNull { task ->
      // The server thread may end a task meanwhile; the backend knows no state "done" and would refuse the request.
      val state = task.state.takeIf { it != chat.allay.core.tasks.Task.State.DONE } ?: return@mapNotNull null
      Protocol.TaskBrief(task.id, task.name, state.wire, task.question?.take(200))
    }?.take(4)
  }.orEmpty()

  /** Runs a client tool on the server thread and posts what came out; failures go back as errors, never as exceptions. */
  private fun answerTool(run: Run, call: Protocol.ToolCall, listener: Listener) {
    val tool = tools.find(call.name)
    val context = host.context(run)
    val result = if (tool == null) {
      Protocol.ToolResult(ok = false, error = "the tool ${call.name} is switched off on this server")
    } else if (tool.operator && (!isOperator(run.asker) || run.task != null)) {
      listener.toolFinished(call.call_id, NOT_OPERATOR, failed = true)
      Protocol.ToolResult(ok = false, error = NOT_OPERATOR)
    } else {
      try {
        val output = if (tool.offMain) tool.run(context, call.args) else host.onMain(config.http.toolTimeout) { tool.run(context, call.args) }
        listener.toolFinished(call.call_id, output.summary, failed = false)
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
    val runId = run.runId ?: return
    if (run.cancelled) return
    runCatching {
      client.newCall(
        Request.Builder()
          .url("${config.server.url}/api/v2/chat/$runId/tools/${call.call_id}")
          .post(Protocol.json.encodeToString(Protocol.ToolResult.serializer(), result).toRequestBody(jsonType))
          .build(),
      ).execute().close()
    }.onFailure { host.log.warning("❌ Couldn't send the result of ${call.name}: $it") }
  }

  /** A remembered fact; since backend 6.9 one with a [place] is what the notebook used to keep. */
  @Serializable data class Memory(val id: String, val text: String, val place: Place? = null, val created_at: String? = null)
  @Serializable data class Place(val x: Int, val y: Int, val z: Int, val dimension: String, val world: String? = null)
  @Serializable private data class Memories(val memories: List<Memory> = emptyList())
  @Serializable private data class Deleted(val deleted: Int = 0)
  @Serializable private data class Remember(val text: String, val place: Place? = null)
  @Serializable private data class Remembered(val memory: Memory)

  /** `/allay new`: the backend forgets the conversation but keeps the memories. */
  suspend fun resetSession(userId: String) = withContext(IO) { send("/api/v2/users/${path(userId)}/session", "DELETE") }

  suspend fun memories(userId: String): List<Memory> = withContext(IO) {
    json.decodeFromString<Memories>(send("/api/v2/users/${path(userId)}/memories", "GET")).memories
  }

  /**
   * `/allay memory <text>` and the note button: saves a fact as the memories tool would, with a [place] (backend 6.9;
   * an older one drops it). A full memory answers `MEMORY_FULL`.
   */
  suspend fun remember(userId: String, text: String, place: Place? = null): Memory = withContext(IO) {
    val body = json.encodeToString(Remember.serializer(), Remember(text, place))
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
  )

  /**
   * At start and on reload: the server's name, MOTD, and address for the backend's admin panel (since backend 6.7).
   * A name another server on the backend holds fails with `NAME_TAKEN`.
   */
  suspend fun register(motd: String?) = withContext(IO) {
    val body = Register(serverId, config.server.name.ifEmpty { null }, motd, host.software, host.gameVersion, host.pluginVersion, config.server.address.ifEmpty { host.address }, host.port)
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
