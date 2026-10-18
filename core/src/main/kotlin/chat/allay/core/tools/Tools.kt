package chat.allay.core.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import chat.allay.core.Lang

/** A tool result for the model, plus a short line for the chat in the asker's language (`⎿ 36 stacks`). */
class ToolOutput(val result: JsonElement, val summary: String)

class ToolError(message: String) : Exception(message)

fun JsonObject.int(key: String) = (get(key) as? JsonPrimitive)?.let { it.intOrNull ?: it.doubleOrNull?.toInt() }
fun JsonObject.double(key: String) = (get(key) as? JsonPrimitive)?.doubleOrNull
fun JsonObject.bool(key: String) = (get(key) as? JsonPrimitive)?.booleanOrNull
fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.ifEmpty { null }

/** A tool the backend can ask the plugin to run, seeing the run through [C]. Runs on the server thread. */
interface ClientTool<in C> {
  val name: String

  /** Changes the game for the asker; offered to operators only. */
  val operator: Boolean get() = false

  /**
   * Runs off the server thread and reaches the world through the context's own hop onto it, so long work (planning
   * sixty chests) doesn't stall the game: read in one tick, think elsewhere, write in another.
   */
  val offMain: Boolean get() = false

  fun run(context: C, args: JsonObject): ToolOutput
}

/** Tools that change the game: items, teleports, and the like. Only operators get them. */
val OPERATOR_TOOLS = setOf("give_item", "teleport", "set_gamemode", "heal", "apply_effect", "set_time", "set_weather")

/** What a non-operator hears when the assistant asks for an operator tool anyway. */
const val NOT_OPERATOR = "only operators can do this"

/** The tools this plugin offers, minus the ones the admin switched off. */
class ToolRegistry<C>(tools: List<ClientTool<C>>, private val enabled: (String) -> Boolean) {
  private val byName = tools.associateBy { it.name }

  /** What the asker may use: operator tools only for operators. */
  fun offered(operator: Boolean): List<String> = byName.values.filter { enabled(it.name) && (operator || !it.operator) }.map { it.name }

  fun find(name: String): ClientTool<C>? = byName[name]?.takeIf { enabled(it.name) }
}

/** Chat labels for tools from `tools.<name>` in the reader's language, with `{arg}` placeholders filled from the call's arguments. */
object ToolLabels {
  fun label(lang: Lang, name: String, args: JsonObject?): String {
    val values = args.orEmpty().mapNotNull { (key, value) -> (value as? JsonPrimitive)?.contentOrNull?.take(60)?.let { key to it } }
    // Tools with an action (reminders, goals) take a label per action, list when the call names none: tools.reminders.add.
    val action = values.toMap()["action"]?.lowercase() ?: "list"
    val key = listOf("tools.$name.$action", "tools.$name").firstOrNull(lang::has)
    val text = if (key != null) lang.text(key, *values.toTypedArray()) else lang.text("tool_lines.unknown", "name" to name)
    // An argument the call didn't pass, or one that isn't a plain value, leaves no trace.
    return Regex("\\{\\w+}").replace(text, "")
  }
}
