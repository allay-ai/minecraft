package chat.allay.core

import chat.allay.core.tools.Catalog
import chat.allay.core.tools.ToolError
import chat.allay.core.tools.ToolOutput
import chat.allay.core.tools.ToolScope
import chat.allay.expansion.AllayApi
import chat.allay.expansion.AllayExpansion
import chat.allay.expansion.AllayExpansions
import chat.allay.expansion.AllayTool
import chat.allay.expansion.Args
import chat.allay.expansion.ItemInfo
import chat.allay.expansion.ToolCall
import chat.allay.expansion.ToolException
import chat.allay.expansion.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.logging.Logger

/**
 * Other plugins' and mods' expansions (the `chat.allay.expansion` API): their tools, offered to the model as
 * `x_<id>_<name>` under the same checks as Allay's own, their items and recipes for the [Catalog], and their prompt
 * lines. An expansion is checked once when it registers; a broken one stays out with a line in the log, and never
 * breaks a question.
 */
class Expansions(private val log: Logger, private val config: () -> Config, private val platform: () -> Platform?) : AllayExpansions.Host {
  /** A registered expansion, read when it came and again on `/allay reload`. */
  class Loaded(val expansion: AllayExpansion, val id: String, val name: String, val version: String, val tools: Map<String, Tool>, val items: List<ItemInfo>, val prompt: String?)

  /** What a tool gave on the server thread: its output, or since API 1.1 a stage that brings it later, off that thread. */
  class Started(val name: String, val now: ToolOutput?, val later: CompletionStage<ToolResult>?)

  /** One of its tools: the name the model sees and the schema it fills. */
  class Tool(val wire: String, val expansion: String, val tool: AllayTool, val description: String, val schema: JsonObject, val access: AllayTool.Access)

  private val loaded = ConcurrentHashMap<String, Loaded>()
  private val changes = java.util.concurrent.atomic.AtomicInteger()

  /** Goes up whenever an expansion comes, goes, or is read again: a cached catalog builds again then. */
  val revision: Int get() = changes.get()

  override fun apiVersion(): String = AllayApi.VERSION

  override fun register(expansion: AllayExpansion) {
    val read = runCatching { read(expansion) }.getOrElse {
      log.warning("❌ Expansion ${runCatching { expansion.id() }.getOrNull() ?: expansion.javaClass.name} refused: ${it.message ?: it.javaClass.simpleName}")
      return
    }
    loaded[read.id] = read
    changes.incrementAndGet()
    fun count(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"
    log.info("Expansion ${read.name}${read.version.ifEmpty { null }?.let { " $it" }.orEmpty()} is on: ${count(read.tools.size, "tool")}, ${count(read.items.size, "item")}${if (read.prompt != null) ", a prompt" else ""}")
  }

  override fun unregister(id: String) {
    loaded.remove(id)?.let {
      changes.incrementAndGet()
      log.info("Expansion ${it.name} is off")
    }
  }

  /** Reads every expansion again, on `/allay reload`: their tools and items may change with their own config. */
  fun reload() {
    loaded.values.toList().forEach { register(it.expansion) }
    // The config may switch expansions on or off without any of them changing.
    changes.incrementAndGet()
  }

  /** The expansions the server didn't switch off. */
  fun active(): List<Loaded> {
    val settings = config().expansions
    if (!settings.enabled) return emptyList()
    return loaded.values.filter { it.id !in settings.disabled }.sortedBy { it.id }
  }

  /** Every expansion's items, with the expansion's name as where they come from. */
  fun items(): List<Pair<String, ItemInfo>> = active().flatMap { expansion -> expansion.items.map { expansion.name to it } }

  /** Catalog entries for every expansion's items. */
  fun entries(): List<Catalog.Entry> = items().map { (source, item) ->
    Catalog.Entry(item.id(), Catalog.Kind.CUSTOM, names = item.names(), source = source, base = item.base(), description = item.description())
  }

  /**
   * The tools [asker] may get in this run, as the protocol sends them: none for an automation agent or the console,
   * reading ones only in `/askx`, the ones marked for it in a public question, operator ones for operators only, and
   * each behind its own permission.
   */
  fun offered(asker: Audience, mode: Run.Mode, public: Boolean, task: Boolean): List<Protocol.ExpansionTool> {
    if (task || mode == Run.Mode.TASK || asker.uuid == null) return emptyList()
    return active().flatMap { it.tools.values }.filter { allowed(it, asker, mode, public) }.take(MAX_OFFERED).map {
      Protocol.ExpansionTool(it.wire, it.description, it.schema, it.access.name.lowercase(), it.expansion)
    }
  }

  /** The name of each tool's expansion, by the tool's name. */
  fun names(tools: List<Protocol.ExpansionTool>): Map<String, String> {
    val byId = active().associate { it.id to it.name }
    return tools.mapNotNull { tool -> byId[tool.expansion]?.let { tool.name to it } }.toMap()
  }

  /** The prompt lines of the expansions this run may hear from; none for an automation agent. */
  fun prompts(mode: Run.Mode): List<Protocol.ExpansionInfo> {
    if (mode == Run.Mode.TASK) return emptyList()
    return active().map { Protocol.ExpansionInfo(it.id, it.name, it.version.ifEmpty { null }, it.prompt) }
  }

  private fun allowed(tool: Tool, asker: Audience, mode: Run.Mode, public: Boolean): Boolean = when {
    mode == Run.Mode.ASKX && tool.access != AllayTool.Access.READ -> false
    public && !tool.tool.inPublic() -> false
    tool.access == AllayTool.Access.OPERATOR && !asker.operator -> false
    else -> tool.tool.permission()?.let { asker.can(it, byDefault = true) } ?: true
  }

  fun isExpansionTool(name: String) = name.startsWith(PREFIX)

  /**
   * Runs [name] for the asker of [scope], on the server thread, after checking again what [offered] checked: the
   * backend may only call a tool this run offered, and rights can change while it runs. A tool that answers
   * [ToolResult.later] comes back unfinished; [finish] waits for it elsewhere.
   */
  fun run(name: String, scope: ToolScope, mode: Run.Mode, public: Boolean, args: JsonObject): Started {
    val tool = active().firstNotNullOfOrNull { it.tools[name] } ?: throw ToolError("the tool $name isn't on this server")
    val asker = scope.requireAsker()
    if (scope.task != null) throw ToolError("automation agents don't use other plugins' tools")
    if (tool.access == AllayTool.Access.OPERATOR && !asker.operator) throw ToolError(chat.allay.core.tools.NOT_OPERATOR)
    if (!allowed(tool, asker, mode, public)) throw ToolError("the asker may not use $name here")
    val call = Call(asker, Args(plain(args) as Map<String, Any?>), if (mode == Run.Mode.ASKX) ToolCall.Mode.ASKX else ToolCall.Mode.ASK, public, scope)
    val result: ToolResult = try {
      tool.tool.run(call) ?: throw ToolError("$name returned nothing")
    } catch (error: ToolException) {
      throw ToolError(error.message?.take(500) ?: "failed")
    }
    return result.pending()?.let { Started(name, null, it) } ?: Started(name, output(result), null)
  }

  /**
   * The output of a call [run] started, off the server thread: at once, or once its stage completes, within [seconds].
   * A stage that takes longer is cancelled and the wait ends with a [TimeoutException], as for any slow tool.
   */
  fun finish(started: Started, seconds: Long): ToolOutput {
    started.now?.let { return it }
    val future = future(started.later!!)
    val result = try {
      future.get(seconds, TimeUnit.SECONDS)
    } catch (timeout: TimeoutException) {
      future.cancel(true)
      throw timeout
    } catch (failed: ExecutionException) {
      val cause = failed.cause
      if (cause is ToolException) throw ToolError(cause.message?.take(500) ?: "failed")
      throw cause ?: failed
    } ?: throw ToolError("${started.name} returned nothing")
    if (result.pending() != null) throw ToolError("${started.name} answered later twice")
    return output(result)
  }

  private fun output(result: ToolResult): ToolOutput {
    val data = json(result.data())
    val text = data.toString()
    val output = if (text.length <= MAX_RESULT) data else JsonObject(mapOf("truncated" to JsonPrimitive(true), "json" to JsonPrimitive(text.take(MAX_RESULT) + "…")))
    return ToolOutput(output, result.summary()?.let(Catalog::plain)?.take(60).orEmpty())
  }

  /** A stage as a future to wait on and cancel; some stages refuse to be one, and get a future that follows them. */
  private fun future(stage: CompletionStage<ToolResult>): CompletableFuture<ToolResult> =
    runCatching { stage.toCompletableFuture() }.getOrElse {
      CompletableFuture<ToolResult>().also { future -> stage.whenComplete { result, error -> if (error != null) future.completeExceptionally(error) else future.complete(result) } }
    }

  /** What an expansion's tool sees of the call; made on the server thread, so the asker is read there. */
  private inner class Call(private val asker: PlayerAudience, private val args: Args, private val mode: ToolCall.Mode, private val public: Boolean, private val scope: ToolScope) : ToolCall {
    private val player = Player(asker)
    override fun asker(): chat.allay.expansion.Asker = player
    override fun args() = args
    override fun mode() = mode
    override fun isPublic() = public
    override fun nameOf(player: UUID, name: String) = scope.nameOf(player, name)
    override fun canSee(player: UUID): Boolean {
      val other = platform()?.player(player) ?: return false
      return platform()?.visible(asker, other) == true
    }
  }

  /** The asker as the call started: what a tool that answers later may still read on its own thread. */
  private class Player(private val player: PlayerAudience) : chat.allay.expansion.Asker {
    private val at = player.position
    private val uuid = player.uuid
    private val name = player.name
    private val locale = player.locale
    private val operator = player.operator
    override fun uuid(): UUID = uuid
    override fun name(): String = name
    override fun locale(): String = locale
    override fun isOperator() = operator
    override fun hasPermission(permission: String, byDefault: Boolean) = player.can(permission, byDefault)
    override fun dimension(): String = at.dimension
    override fun x() = at.x
    override fun y() = at.y
    override fun z() = at.z
    override fun <T> unwrap(type: Class<T>): T? = player.unwrap(type)
  }

  private fun read(expansion: AllayExpansion): Loaded {
    val id = expansion.id()
    if (id == null || !ID.matches(id)) throw IllegalArgumentException("its id must be 1–24 lower-case letters, digits, or underscores, not “$id”")
    val required = expansion.requiresApi()
    if (!AllayApi.satisfies(AllayApi.VERSION, required)) throw IllegalArgumentException("it needs expansion API $required, and this Allay has ${AllayApi.VERSION}; update Allay")
    val name = expansion.name()?.trim()?.take(40)?.ifEmpty { null } ?: id
    val tools = expansion.tools().orEmpty()
    if (tools.size > MAX_TOOLS) throw IllegalArgumentException("it has ${tools.size} tools; at most $MAX_TOOLS")
    val read = tools.associate { tool ->
      val toolName = tool.name()
      if (toolName == null || !TOOL.matches(toolName)) throw IllegalArgumentException("a tool's name must be 1–32 lower-case letters, digits, or underscores, not “$toolName”")
      val description = tool.description()?.trim().orEmpty()
      if (description.isEmpty() || description.length > MAX_DESCRIPTION) throw IllegalArgumentException("$toolName needs a description of 1–$MAX_DESCRIPTION characters")
      val schemaText = tool.parameters().toJson()
      if (schemaText.length > MAX_SCHEMA) throw IllegalArgumentException("$toolName's schema is over $MAX_SCHEMA characters")
      val schema = runCatching { Json.parseToJsonElement(schemaText).jsonObject }.getOrElse { throw IllegalArgumentException("$toolName's schema isn't a JSON object") }
      if ((schema["type"] as? JsonPrimitive)?.content != "object") throw IllegalArgumentException("$toolName's schema must be of type object")
      val wire = "$PREFIX${id}_$toolName"
      wire to Tool(wire, id, tool, description, schema, tool.access() ?: AllayTool.Access.READ)
    }
    if (read.size < tools.size) throw IllegalArgumentException("two tools share a name")
    val items = expansion.items().orEmpty().take(MAX_ITEMS)
    val prompt = expansion.promptExpansion()?.trim()?.ifEmpty { null }?.let {
      if (it.length > MAX_PROMPT) log.warning("Expansion $name's prompt is ${it.length} characters; Allay keeps the first $MAX_PROMPT")
      it.take(MAX_PROMPT)
    }
    return Loaded(expansion, id, name, expansion.version()?.trim()?.take(32).orEmpty(), read, items, prompt)
  }

  companion object {
    /** What sets the model's tools from expansions apart from Allay's own. */
    const val PREFIX = "x_"
    const val MAX_TOOLS = 32
    /** All expansions together; the backend takes no more. */
    const val MAX_OFFERED = 32
    const val MAX_DESCRIPTION = 1000
    const val MAX_SCHEMA = 4000
    const val MAX_PROMPT = 2000
    const val MAX_RESULT = 8000
    const val MAX_ITEMS = 500
    private val ID = Regex("[a-z0-9_]{1,24}")
    private val TOOL = Regex("[a-z0-9_]{1,32}")

    /** JSON as the plain values the API hands an expansion: strings, Long or Double numbers, booleans, lists, and maps. */
    fun plain(element: JsonElement): Any? = when (element) {
      is JsonNull -> null
      is JsonPrimitive -> if (element.isString) element.content else element.booleanOrNull ?: element.longOrNull ?: element.doubleOrNull
      is JsonArray -> element.map(::plain)
      is JsonObject -> element.mapValues { plain(it.value) }
    }

    /** An expansion's plain values as JSON; anything else becomes its text. */
    fun json(value: Any?): JsonElement = when (value) {
      null -> JsonNull
      is JsonElement -> value
      is String -> JsonPrimitive(value)
      is Boolean -> JsonPrimitive(value)
      is Number -> JsonPrimitive(value)
      is Map<*, *> -> JsonObject(value.entries.associate { (key, item) -> key.toString() to json(item) })
      is Iterable<*> -> JsonArray(value.map(::json))
      is Array<*> -> JsonArray(value.map(::json))
      is UUID -> JsonPrimitive(value.toString())
      else -> JsonPrimitive(value.toString())
    }
  }
}
