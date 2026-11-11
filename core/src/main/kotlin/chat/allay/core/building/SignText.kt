package chat.allay.core.building

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A sign's line as a player reads it. Before 1.21.5 the game kept each line as JSON (`{"text":"Inn"}`, `"Inn"`); a
 * line that isn't JSON stays as it is.
 */
object SignText {
  fun plain(line: String): String {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || trimmed[0] !in "{[\"") return line
    val json = runCatching { Json.parseToJsonElement(trimmed) }.getOrNull() ?: return line
    return text(json, 0)
  }

  /** A component's text, then its `extra`'s, in order; a translation or a score says nothing a sign can show. */
  private fun text(json: JsonElement, depth: Int): String {
    if (depth > 32) return ""
    return when (json) {
      is JsonPrimitive -> if (json.isString) json.content else ""
      is JsonArray -> json.joinToString("") { text(it, depth + 1) }
      is JsonObject -> (json["text"]?.let { text(it, depth + 1) } ?: "") + (json["extra"]?.let { text(it, depth + 1) } ?: "")
    }
  }
}
