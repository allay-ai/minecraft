package chat.allay.spigot

import chat.allay.core.UsageBook
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

/**
 * Allay's numbers in WorldAPI, when it runs: the `allay` namespace with questions, tokens, cost, and answer times,
 * for the server and each player. [UsageExpansion] speaks WorldAPI's expansion API; this class holds no type of it, so
 * Allay loads without WorldAPI. Allay keeps the numbers; WorldAPI reads them on request.
 */
class WorldApiLink(private val plugin: JavaPlugin, val book: UsageBook) {
  @Volatile var connected = false
    private set

  /** Registers the expansion when WorldAPI is installed; nothing happens without it. Call on the first tick. */
  fun connect() {
    if (plugin.server.pluginManager.getPlugin(WORLD_API)?.isEnabled != true) return
    try {
      UsageExpansion.register(this)
      connected = true
      plugin.logger.info("WorldAPI found: questions, tokens, and answer times at /extensions/${UsageExpansion.ID}")
    } catch (error: LinkageError) {
      // WorldAPI before 3.4.0 has no expansion API.
      plugin.logger.warning("WorldAPI is too old for Allay's numbers; it needs 3.4.0 or newer")
    } catch (error: Exception) {
      plugin.logger.warning("WorldAPI didn't take Allay's numbers: ${error.message}")
    }
  }

  fun disconnect() {
    if (!connected) return
    connected = false
    runCatching { UsageExpansion.unregister() }
  }

  /** A player's numbers; [cost] only for clients that may read players' private data. */
  fun card(id: UUID, totals: UsageBook.Totals, cost: Boolean): Map<String, Any?> = buildMap {
    put("uuid", id.toString())
    put("name", name(id))
    putAll(numbers(totals, cost))
  }

  fun numbers(totals: UsageBook.Totals, cost: Boolean): Map<String, Any?> = buildMap {
    put("questions", totals.asked)
    put("byCommand", COMMANDS.associateWith { totals.questions[it] ?: 0L })
    put("inputTokens", totals.input_tokens)
    put("outputTokens", totals.output_tokens)
    if (cost) put("cost", totals.cost)
    put("answers", totals.answers)
    put("averageSeconds", totals.averageSeconds)
    put("medianSeconds", totals.medianSeconds)
  }

  fun name(id: UUID): String? = runCatching { Bukkit.getOfflinePlayer(id).name }.getOrNull()

  /** A UUID, or the name of a player with numbers here. */
  fun player(raw: String): UUID = runCatching { UUID.fromString(raw) }.getOrNull()
    ?: book.players().keys.firstOrNull { name(it).equals(raw, ignoreCase = true) }
    ?: throw NoSuchElementException("No player named $raw has asked Allay here")

  companion object {
    const val WORLD_API = "WorldAPI"
    val COMMANDS = listOf("ask", "askall", "askx")
  }
}
