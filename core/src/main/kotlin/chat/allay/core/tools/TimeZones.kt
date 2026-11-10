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

  /** Forgets the zone the player named: their country's, or the server's, again. */
  fun forget(player: UUID) = data.update(player) { it.copy(timezone = null) }

  /** The country the player's address is in, as GeoIP has it, for the zones to pick from first. */
  fun country(player: UUID): String? = country.invoke(player)

  companion object {
    fun parse(zone: String): ZoneId? = runCatching { ZoneId.of(zone.trim()) }.getOrNull()

    /** Each country's zones by ISO code, the one most of its people live in first, from `timezones.txt`. */
    private val COUNTRIES: Map<String, List<String>> by lazy {
      TimeZones::class.java.getResourceAsStream("/timezones.txt")?.bufferedReader(Charsets.UTF_8)?.useLines { lines ->
        lines.filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull { line ->
          val words = line.split(' ')
          val zones = words.drop(1).filter { parse(it) != null }
          if (zones.isEmpty()) null else words[0] to zones
        }.toMap()
      }.orEmpty()
    }

    /** The time zone most people of [country] (an ISO code like `DE`) live in; null for one nobody knows. */
    fun zoneOf(country: String): String? = COUNTRIES[country.uppercase()]?.firstOrNull()

    /** Every zone of [country], the main one first; empty for one nobody knows. */
    fun zonesOf(country: String): List<String> = COUNTRIES[country.uppercase()].orEmpty()

    /** Every country with zones, by ISO code, sorted by its name in [locale]. */
    fun countries(locale: java.util.Locale): List<String> =
      COUNTRIES.keys.sortedWith(compareBy(java.text.Collator.getInstance(locale)) { countryName(it, locale) })

    /** A country's name in [locale], as the JDK knows it: «Россия» for `RU`. */
    fun countryName(country: String, locale: java.util.Locale): String =
      java.util.Locale.of("", country).getDisplayCountry(locale).ifEmpty { country }

    /**
     * A zone as a player reads it in [locale]: the JDK's name for it (“Yekaterinburg Standard Time”), the city where
     * its id names one, the offset from UTC now, and the time there now.
     */
    fun label(zone: String, locale: java.util.Locale, now: java.time.Instant = java.time.Instant.now()): Label {
      val id = parse(zone) ?: return Label(zone, zone, "", "")
      val at = now.atZone(id)
      val name = java.util.TimeZone.getTimeZone(id).getDisplayName(id.rules.isDaylightSavings(now), java.util.TimeZone.LONG, locale)
      val offset = at.offset.id.let { if (it == "Z") "UTC" else "UTC$it" }
      val city = zone.substringAfterLast('/').replace('_', ' ')
      return Label(name, city, offset, at.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")))
    }

    /** What a zone's icon says: its [name] and [city], how far it is from UTC, and the [time] there. */
    data class Label(val name: String, val city: String, val offset: String, val time: String)
  }
}
