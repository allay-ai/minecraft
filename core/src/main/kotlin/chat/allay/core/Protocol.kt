package chat.allay.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Models of the Allay streaming protocol v2 (`POST /api/v2/chat`). Field names follow the wire format. */
object Protocol {
  val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
  }

  @Serializable data class ChatRequest(
    val mode: String,
    val message: String,
    val platform: String = "minecraft",
    /** Since 3.0: "public" when every player online sees the question and the answer. */
    val visibility: String = "private",
    val user_id: String,
    val locale: String? = null,
    /** The asker's IANA time zone, so the backend can tell the model their real time. */
    val timezone: String? = null,
    val player: PlayerInfo? = null,
    val server: ServerInfo,
    val tools: List<String>,
    /**
     * Since 5.0, in byok mode: the server's own model; the key itself goes in `X-Provider-Key`. Since 5.7 also without a
     * key, carrying only the model a player's tier picks; a backend with billing off honors it.
     */
    val provider: Provider? = null,
    /** Since 5.7, with mode "task": which automation agent wakes. */
    val task: TaskInfo? = null,
    /**
     * Since 5.7, with mode "ask": the asker's running automation agents, so the model can pass answers on. Since 5.12.1,
     * with mode "task": the waking agent's own running sub-agents.
     */
    val tasks: List<TaskBrief>? = null,
  )

  @Serializable data class TaskInfo(val id: String, val name: String, val depth: Int, val kind: String)

  /** [state] is working, waiting, or asking; [question] what an asking agent wants to know. */
  @Serializable data class TaskBrief(val id: String, val name: String, val state: String, val question: String? = null)

  /** Empty [model] means the backend's default; empty [search_model] means no web search. */
  @Serializable data class Provider(val base_url: String, val model: String, val search_model: String)

  /**
   * [dimension] (since 5.3) is where the player stands, so the model knows which dimension “here” is. [private] is true
   * while the player has full privacy on (backend 6.5): the admin panel then knows them by pseudonym only. [position]
   * (backend 6.9) is the block they stand on, for distances to memories with a place and deaths, and for “here”.
   */
  @Serializable data class PlayerInfo(
    val name: String,
    val uuid: String,
    val op: Boolean,
    val dimension: String? = null,
    val private: Boolean? = null,
    val position: Position? = null,
  )

  /** A block in a world; [world] is the world's folder name, which tells two overworlds apart. */
  @Serializable data class Position(val x: Int, val y: Int, val z: Int, val dimension: String, val world: String? = null)

  /**
   * [id] is random and kept by the plugin; it names the server's shared thread of public questions. [address] and [port]
   * say where players join, for the backend's admin panel (backend 6.6).
   */
  /** [loader] and [side] since backend 6.12: what Allay runs on and where, see [Host.loader] and [Host.side]. */
  @Serializable data class ServerInfo(
    val id: String? = null, val software: String, val version: String, val plugin_version: String, val address: String? = null, val port: Int? = null,
    val loader: String? = null, val side: String? = null,
  )

  @Serializable data class Start(val run_id: String, val model: String? = null)

  @Serializable data class Delta(val delta: String = "")

  @Serializable data class ToolCall(val call_id: String, val name: String, val args: JsonObject = JsonObject(emptyMap()))

  @Serializable data class ToolStatus(
    val call_id: String,
    val name: String,
    val kind: String = "server",
    val state: String,
    val args: JsonObject? = null,
    val summary: String? = null,
    /** Since 2.4: the summary as a key and values, so the plugin can say it in the reader's language. */
    val summary_key: String? = null,
    val summary_values: JsonObject? = null,
  )

  @Serializable data class Commands(val is_sequence: Boolean = false, val commands: List<String> = emptyList())

  @Serializable data class Usage(
    val input_tokens: Long = 0,
    val output_tokens: Long = 0,
    val reasoning_tokens: Long = 0,
    val cost: Double = 0.0,
  )

  @Serializable data class Done(val answer: String = "", val duration_ms: Long = 0)

  @Serializable data class ErrorEvent(
    val code: String = "PROCESSING_ERROR",
    val message: String = "",
    /** Since 5.0: when a spent quota refills, and when a rate limit lets go (seconds). */
    val resets_at: String? = null,
    val retry_after: Long? = null,
  )

  @Serializable data class ToolResult(val ok: Boolean, val result: JsonElement? = null, val error: String? = null)

  /** One server-sent event after decoding; unknown event names are ignored. */
  sealed interface Event {
    data class Started(val data: Start) : Event
    data class Reasoning(val delta: String) : Event
    data class Text(val delta: String) : Event
    data class Call(val data: ToolCall) : Event
    data class Status(val data: ToolStatus) : Event
    data class Command(val data: Commands) : Event
    data class Spent(val data: Usage) : Event
    data class Finished(val data: Done) : Event
    data class Failed(val data: ErrorEvent) : Event
  }

  fun decode(name: String, data: String): Event? = runCatching {
    when (name) {
      "start" -> Event.Started(json.decodeFromString(data))
      "reasoning" -> Event.Reasoning(json.decodeFromString<Delta>(data).delta)
      "text" -> Event.Text(json.decodeFromString<Delta>(data).delta)
      "tool_call" -> Event.Call(json.decodeFromString(data))
      "tool_status" -> Event.Status(json.decodeFromString(data))
      "commands" -> Event.Command(json.decodeFromString(data))
      "usage" -> Event.Spent(json.decodeFromString(data))
      "done" -> Event.Finished(json.decodeFromString(data))
      "error" -> Event.Failed(json.decodeFromString(data))
      else -> null
    }
  }.getOrNull()
}
