package chat.allay.core

/**
 * Parses a Server-Sent Events stream line by line: `event:`, `data:`, and `id:` fields, a blank line
 * ends an event, lines starting with `:` are comments (the backend's pings).
 */
class SseParser(private val onEvent: (name: String, data: String) -> Unit) {
  private var name = "message"
  private val data = StringBuilder()
  private var id: String? = null

  /** The id of the last event handed on, as a reconnect's `Last-Event-ID` sends it; null while no event had one. */
  var lastId: String? = null
    private set

  fun line(raw: String) {
    val line = raw.removeSuffix("\r")
    when {
      line.isEmpty() -> dispatch()
      line.startsWith(":") -> Unit
      else -> {
        val field = line.substringBefore(':')
        val value = line.substringAfter(':', "").removePrefix(" ")
        when (field) {
          "event" -> name = value
          "id" -> id = value
          "data" -> {
            if (data.isNotEmpty()) data.append('\n')
            data.append(value)
          }
        }
      }
    }
  }

  /** Flushes an event that the stream ended without a blank line after. */
  fun end() = dispatch()

  private fun dispatch() {
    if (data.isNotEmpty()) {
      // The id counts as read once the event is handled, so a reconnect never skips one half-done.
      onEvent(name, data.toString())
      id?.let { lastId = it }
    }
    name = "message"
    data.setLength(0)
    id = null
  }
}
