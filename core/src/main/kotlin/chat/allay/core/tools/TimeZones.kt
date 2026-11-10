package chat.allay.core.tools

import chat.allay.core.PlayerData
import java.time.ZoneId
import java.util.UUID

/**
 * Each player's time zone, so Allay knows their real time of day. The game doesn't tell where a player is: the zone a
 * player named comes first (part of their state on the backend, [PlayerData]), then their [country]'s (by the address
 * they play from, as GeoIP has it), then the server's default.
 */
class TimeZones(private val data: PlayerData, private val country: (UUID) -> String? = { null }, private val default: () -> String) {
  fun of(player: UUID): String = chosen(player) ?: country(player)?.let(::zoneOf) ?: default()

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

    /** Each country's zone by ISO code (the capital's where it has several), from `timezones.txt`. */
    private val COUNTRIES: Map<String, String> by lazy {
      TimeZones::class.java.getResourceAsStream("/timezones.txt")?.bufferedReader(Charsets.UTF_8)?.useLines { lines ->
        lines.filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull { line ->
          line.split(' ').takeIf { it.size == 2 && parse(it[1]) != null }?.let { it[0] to it[1] }
        }.toMap()
      }.orEmpty()
    }

    /** The time zone most people of [country] (an ISO code like `DE`) live in; null for one nobody knows. */
    fun zoneOf(country: String): String? = COUNTRIES[country.uppercase()]
  }
}
