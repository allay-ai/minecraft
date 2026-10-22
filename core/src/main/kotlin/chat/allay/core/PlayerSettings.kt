package chat.allay.core

import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

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

/**
 * Each player's settings, part of their state on the backend ([PlayerData], since 6.7). Chat reads them off the server
 * thread; the state map is concurrent.
 */
class PlayerSettings(private val data: PlayerData) {
  fun of(player: UUID): Settings = data.of(player).settings ?: Settings()

  /** How many players in memory have chat mode on; safe from any thread. */
  fun chatModeCount(): Int = data.all().count { it.settings?.chat_mode == true }

  fun update(player: UUID, change: (Settings) -> Settings): Settings =
    data.update(player) { it.copy(settings = change(it.settings ?: Settings()).takeIf { new -> new != Settings() }) }.settings ?: Settings()

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
