package chat.allay.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * One player's Allay settings; [language] is a `lang/` code they chose, null to have it picked for them. [outputs] is
 * where each kind of automation puts what it made, when the player said so once: `inventory`, `auto`, or chests.
 * [full_privacy] keeps the player's name, UUID, and face out of the backend's admin panel (backend 6.5).
 */
@Serializable data class Settings(
  val chat_mode: Boolean = false,
  val hints: Boolean = true,
  val language: String? = null,
  val outputs: Map<String, String> = emptyMap(),
  val full_privacy: Boolean = false,
)

/** Each player's settings in `players.json`. Chat reads them off the server thread, so the map is concurrent. */
class PlayerSettings(private val folder: File, private val log: Logger) {
  private val file = File(folder, "players.json")
  private val json = Json { ignoreUnknownKeys = true }
  private val players = ConcurrentHashMap<String, Settings>(load())

  fun of(player: UUID): Settings = players[player.toString()] ?: Settings()

  /** How many players have chat mode on; safe from any thread. */
  fun chatModeCount(): Int = players.values.count { it.chat_mode }

  fun update(player: UUID, change: (Settings) -> Settings): Settings {
    val settings = players.compute(player.toString()) { _, old -> change(old ?: Settings()) }!!
    save()
    return settings
  }

  @Synchronized private fun save() {
    folder.mkdirs()
    val temp = File(folder, "players.json.tmp")
    // Players who never changed anything don't need a line.
    temp.writeText(json.encodeToString(players.filterValues { it != Settings() }))
    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
  }

  private fun load(): Map<String, Settings> = if (!file.exists()) emptyMap() else runCatching {
    json.decodeFromString<Map<String, Settings>>(file.readText())
  }.getOrElse {
    log.warning("Couldn't read players.json: ${it.message}; starting over")
    emptyMap()
  }

  companion object {
    /**
     * A random id for this server, kept in `server-id`. The backend names the server's shared thread of
     * public questions after it, so it must stay the same across restarts and differ between servers.
     */
    fun serverId(folder: File): String {
      val file = File(folder, "server-id")
      file.takeIf { it.exists() }?.readText()?.trim()?.takeIf { ID.matches(it) }?.let { return it }
      val id = UUID.randomUUID().toString()
      folder.mkdirs()
      file.writeText(id)
      return id
    }

    private val ID = Regex("[A-Za-z0-9_-]{1,64}")
  }
}
