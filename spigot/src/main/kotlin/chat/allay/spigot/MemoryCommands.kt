package chat.allay.spigot

import chat.allay.core.*
import chat.allay.core.tools.*
import chat.allay.spigot.tools.Features
import chat.allay.spigot.tools.ToolContext
import chat.allay.spigot.tools.id

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import chat.allay.spigot.Minecraft.formatMessage

/** `/allay new`, `/allay memory`, and `/allay forget`: what the backend remembers about the asker, under their control. */
class MemoryCommands(
  private val plugin: JavaPlugin,
  private val locales: Locales,
  private val features: Features,
  private val service: Service<ToolContext>,
  private val scope: CoroutineScope,
) {

  fun newSession(sender: CommandSender): Boolean = request(sender) { lang ->
    service.resetSession(userId(sender))
    listOf(formatMessage(lang.text("memory.session_cleared")))
  }

  fun list(sender: CommandSender): Boolean = request(sender) { lang ->
    val memories = service.memories(userId(sender))
    if (memories.isEmpty()) return@request listOf(formatMessage(lang.text("memory.empty")))
    (listOf(lang.text("memory.header")) +
      memories.mapIndexed { index, memory -> line(lang, index + 1, memory) } +
      lang.text("memory.footer")).map(::formatMessage)
  }

  /** One memory as `/allay memory` lists it; one with a place shows where. */
  private fun line(lang: Lang, n: Int, memory: Service.Memory): String = memory.place?.let { place ->
    lang.text("memory.line_place", "n" to n, "text" to memory.text, "x" to place.x, "y" to place.y, "z" to place.z, "dimension" to lang.text("dimensions.${place.dimension}"))
  } ?: lang.text("memory.line", "n" to n, "text" to memory.text)

  /**
   * `/allay memory <text>`: keeps what the player typed without asking the assistant. Coordinates in it give the memory
   * a place, in the dimension the text names or else where the player stands (backend 6.9; an older one keeps the text).
   */
  fun remember(sender: CommandSender, text: String): Boolean {
    val lang = locales.of(sender)
    if (text.length < 3) {
      lang.send(sender, "memory.remember_usage")
      return true
    }
    val place = (sender as? Player)?.let { player -> QuickNote.spot(text)?.let { place(player, it) } }
    return save(sender, text, place)
  }

  /** The note button under an answer: its text with the place the answer named. */
  fun rememberAt(player: Player, text: String, x: Int, y: Int, z: Int, dimension: String?): Boolean {
    val current = Dimensions.id(player.world)
    val where = Dimensions.normalize(dimension) ?: current
    return save(player, text, Service.Place(x, y, z, where, player.world.name.takeIf { where == current }))
  }

  private fun save(sender: CommandSender, text: String, place: Service.Place?): Boolean =
    request(sender) { lang ->
      try {
        val memory = service.remember(userId(sender), text.take(200), place)
        val saved = memory.place ?: return@request listOf(Minecraft.withText(lang.text("memory.remembered", "text" to Minecraft.SLOT), memory.text))
        listOf(Minecraft.withText(lang.text("memory.noted", "text" to Minecraft.SLOT, "x" to saved.x, "y" to saved.y, "z" to saved.z, "dimension" to lang.text("dimensions.${saved.dimension}")), memory.text))
      } catch (error: ApiErrorException) {
        if (error.apiError.code != "MEMORY_FULL") throw error
        listOf(formatMessage(lang.text("memory.full")))
      }
    }

  /** The place the text names, read on the server thread; without a height, the player's own. */
  private fun place(player: Player, spot: QuickNote.Spot): Service.Place {
    val current = Dimensions.id(player.world)
    val dimension = spot.dimension ?: current
    // The world's name tells two overworlds apart; a place in another dimension keeps only the dimension.
    return Service.Place(spot.x, spot.y ?: player.location.blockY, spot.z, dimension, player.world.name.takeIf { dimension == current })
  }

  /** Numbers come from a fresh list, so they match what `/allay memory` shows right now. */
  fun forget(sender: CommandSender, which: String): Boolean = request(sender) { lang ->
    val userId = userId(sender)
    if (which.equals("all", ignoreCase = true)) {
      val count = service.forget(userId, null)
      return@request listOf(formatMessage(lang.text("memory.forgot_all", "count" to count)))
    }
    val number = which.toIntOrNull() ?: return@request listOf(formatMessage(lang.text("memory.forget_usage")))
    val memory = service.memories(userId).getOrNull(number - 1)
      ?: return@request listOf(formatMessage(lang.text("memory.not_found", "n" to which)))
    service.forget(userId, memory.id)
    listOf(formatMessage(lang.text("memory.forgot", "text" to memory.text)))
  }

  fun usage(sender: CommandSender) = locales.of(sender).send(sender, "memory.forget_usage")

  /** Runs [block] off the server thread; it returns finished chat lines, colored already. */
  private fun request(sender: CommandSender, block: suspend (Lang) -> List<String>): Boolean {
    val lang = locales.of(sender)
    scope.launch {
      val lines = try {
        block(lang)
      } catch (error: ApiErrorException) {
        // A backend before 2.1 has no such routes, and one before 5.1 can't add a memory.
        listOf(formatMessage(if (error.apiError.statusCode in setOf(404, 405)) lang.text("memory.unsupported") else ErrorHandler.message(lang, error.apiError)))
      } catch (error: Exception) {
        plugin.logger.warning("Memory request failed: $error")
        listOf(formatMessage(ErrorHandler.message(lang, error)))
      }
      if (plugin.isEnabled) plugin.server.scheduler.runTask(plugin, Runnable { lines.filter { it.isNotEmpty() }.forEach { sender.sendMessage(it) } })
    }
    return true
  }

  private fun userId(sender: CommandSender) = (sender as? Player)?.uniqueId?.toString() ?: "console"
}
