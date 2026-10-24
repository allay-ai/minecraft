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

/**
 * The things a lookup tool was asked about: [many]'s list (since 6.13) or [one]'s single name, each cut to 64
 * characters, repeats dropped. Null when neither is there; a [ToolError] for more than [max].
 */
fun JsonObject.asked(many: String, one: String, max: Int): List<String>? {
  val list = (get(many) as? kotlinx.serialization.json.JsonArray)?.mapNotNull { element ->
    (element as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.ifEmpty { null }?.take(64)
  }?.distinct()
  if (list != null && list.size > max) throw ToolError("ask for at most $max at once")
  return list?.ifEmpty { null } ?: string(one)?.take(64)?.let(::listOf)
}

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

/** Tools still being worked on: off unless `tools.enabled` turns them on. Drafts cost many tokens and often come out wrong. */
val EXPERIMENTAL_TOOLS = setOf("show_draft")

/** Tools that change the game: items, teleports, and the like. Only operators get them. */
val OPERATOR_TOOLS = setOf("give_item", "teleport", "set_gamemode", "heal", "apply_effect", "set_time", "set_weather")

/**
 * Every client tool of the protocol, by name: each platform offers exactly these, with the same arguments and results,
 * so the model can't tell where it runs. `ToolsTest` holds Bukkit to it, the game tests Fabric.
 */
val CLIENT_TOOLS = setOf(
  "get_player_status", "get_inventory", "get_surroundings", "look_at", "get_world", "get_statistics", "get_advancements", "get_recipes", "search_items", "locate_structure", "get_server", "get_locations", "get_nearby_players", "show_marks", "show_draft", "inspect_redstone",
  "point_to", "sort_inventory", "reminders", "scan_nearby_containers", "find_item", "get_light_spawn_check", "get_brewing_items", "get_brewing_recipe", "scan_farm",
  "get_item_durability", "get_xp_info", "get_food_options", "ping_me",
  "locate_biome", "get_death_info", "find_villagers", "get_anvil_plan", "write_book", "set_timezone", "goals", "inspect_item",
  "get_allay_guide", "allay_settings",
  "start_task", "message_task", "cancel_task", "say", "wait", "finish", "pick_blocks", "chests", "smelt", "brew", "craft", "anvil",
  "give_item", "teleport", "set_gamemode", "heal", "apply_effect", "set_time", "set_weather",
)

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
  private val LISTS = mapOf("items" to "item", "queries" to "query")

  fun label(lang: Lang, name: String, args: JsonObject?): String {
    val plain = args.orEmpty().mapNotNull { (key, value) -> (value as? JsonPrimitive)?.contentOrNull?.take(60)?.let { key to it } }
    // A lookup of several names (since 6.13) shows them all where the label names one: “beacon, anvil”.
    val listed = LISTS.mapNotNull { (many, one) ->
      (args?.get(many) as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.ifEmpty { null }?.let { one to it.joinToString().take(60) }
    }
    val values = plain.filter { (key, _) -> listed.none { it.first == key } } + listed
    // Tools with an action (reminders, goals) take a label per action, list when the call names none: tools.reminders.add.
    val action = values.toMap()["action"]?.lowercase() ?: "list"
    val key = listOf("tools.$name.$action", "tools.$name").firstOrNull(lang::has)
    val text = if (key != null) lang.text(key, *values.toTypedArray()) else lang.text("tool_lines.unknown", "name" to name)
    // An argument the call didn't pass, or one that isn't a plain value, leaves no trace.
    return Regex("\\{\\w+}").replace(text, "")
  }
}
