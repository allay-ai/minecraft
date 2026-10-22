package chat.allay.core.tools

import chat.allay.core.PlayerData
import java.time.ZoneId
import java.util.UUID

/**
 * Each player's time zone, so Allay knows their real time of day. The game doesn't tell where a player is, so everyone
 * starts with the server's default and can name their own zone; it's part of their state on the backend ([PlayerData]).
 */
class TimeZones(private val data: PlayerData, private val default: () -> String) {
  fun of(player: UUID): String = chosen(player) ?: default()

  /** Stores a zone such as Asia/Yekaterinburg; returns its canonical id, or throws when Java doesn't know it. */
  fun set(player: UUID, zone: String): ZoneId {
    val id = parse(zone) ?: throw ToolError("unknown time zone $zone; use an IANA id such as Europe/Moscow or Asia/Yekaterinburg")
    data.update(player) { it.copy(timezone = id.id) }
    return id
  }

  /** The zone the player named, or null when they never did. */
  fun chosen(player: UUID): String? = data.of(player).timezone

  companion object {
    fun parse(zone: String): ZoneId? = runCatching { ZoneId.of(zone.trim()) }.getOrNull()
  }
}
