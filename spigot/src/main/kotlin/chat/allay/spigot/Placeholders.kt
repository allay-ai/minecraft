package chat.allay.spigot

import chat.allay.core.Handler
import chat.allay.core.Locales
import chat.allay.core.PlayerSettings
import chat.allay.spigot.tools.Goals
import me.clip.placeholderapi.PlaceholderAPI
import me.clip.placeholderapi.PlaceholderAPIPlugin
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

/**
 * PlaceholderAPI both ways, when it's there. `{papi:name}` in a text turns into `%name%` for the player the
 * message is about: a placeholder of the template, never raw `%…%` replaced over the whole line, so a percent
 * sign or a player's words stay as they are. And `%allay_…%` tells TAB, scoreboards, and other plugins about Allay.
 */
object Placeholders {
  val available: Boolean get() = Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")

  /** What fills `{papi:…}` for [sender]; null for the console and command blocks, whose tags show nothing. */
  fun about(sender: CommandSender?): ((String) -> String?)? {
    val player = sender as? Player ?: return null
    // Only touch PlaceholderAPI's classes when it's there; they aren't on the classpath otherwise.
    return { name -> if (available) PlaceholderAPI.setPlaceholders(player, "%$name%") else null }
  }

  /** Registers `%allay_…%`. Call only when [available]. Returns a handle that unregisters it. */
  fun register(plugin: JavaPlugin, settings: PlayerSettings, goals: Goals, locales: Locales, handler: () -> Handler): AutoCloseable {
    val expansion = Expansion(plugin, settings, goals, locales, handler)
    expansion.register()
    return AutoCloseable { expansion.unregister() }
  }

  /**
   * `%allay_chat_mode%` and `%allay_thinking%` (PlaceholderAPI's true or false: chat mode is on, Allay is
   * answering right now), `%allay_goals%` (how many goals the player has), and `%allay_language%` (the
   * language Allay talks to them in). TAB may ask from its own thread, so everything here reads thread-safe state.
   */
  private class Expansion(
    private val plugin: JavaPlugin,
    private val settings: PlayerSettings,
    private val goals: Goals,
    private val locales: Locales,
    private val handler: () -> Handler,
  ) : PlaceholderExpansion() {
    override fun getIdentifier() = "allay"
    override fun getAuthor() = "MairwunNx"
    override fun getVersion(): String = plugin.description.version
    override fun persist() = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? {
      val id = player?.uniqueId ?: return ""
      return when (params.lowercase()) {
        "chat_mode" -> bool(settings.of(id).chat_mode)
        "thinking" -> bool(handler().busy(id))
        "goals" -> "${goals.count(id)}"
        "language" -> player.player?.let { locales.code(it) } ?: ""
        else -> null
      }
    }

    private fun bool(value: Boolean): String = if (value) PlaceholderAPIPlugin.booleanTrue() else PlaceholderAPIPlugin.booleanFalse()
  }
}
