package chat.allay.core.tools

import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * Each player's time zone, so Allay knows their real time of day. The game doesn't tell where a player is,
 * so everyone starts with the server's default and can name their own zone. Kept in `timezones.json`.
 */
class TimeZones(private val folder: File, private val log: Logger, private val default: () -> String) {
  private val file = File(folder, "timezones.json")
  private val json = Json { ignoreUnknownKeys = true }
  private val zones = ConcurrentHashMap<String, String>(load())

  fun of(player: UUID): String = zones[player.toString()] ?: default()

  /** Stores a zone such as Asia/Yekaterinburg; returns its canonical id, or throws when Java doesn't know it. */
  fun set(player: UUID, zone: String): ZoneId {
    val id = parse(zone) ?: throw ToolError("unknown time zone $zone; use an IANA id such as Europe/Moscow or Asia/Yekaterinburg")
    zones[player.toString()] = id.id
    save()
    return id
  }

  /** The zone the player named, or null when they never did; for an export. */
  fun chosen(player: UUID): String? = zones[player.toString()]

  /** Erasure: the player's zone goes, so the server's default applies again. */
  fun forget(player: UUID) {
    if (zones.remove(player.toString()) != null) save()
  }

  @Synchronized private fun save() {
    folder.mkdirs()
    val temp = File(folder, "timezones.json.tmp")
    temp.writeText(json.encodeToString(zones.toMap()))
    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
  }

  private fun load(): Map<String, String> = if (!file.exists()) emptyMap() else runCatching {
    json.decodeFromString<Map<String, String>>(file.readText())
  }.getOrElse {
    log.warning("Couldn't read timezones.json: ${it.message}; starting over")
    emptyMap()
  }

  companion object {
    fun parse(zone: String): ZoneId? = runCatching { ZoneId.of(zone.trim()) }.getOrNull()
  }
}
