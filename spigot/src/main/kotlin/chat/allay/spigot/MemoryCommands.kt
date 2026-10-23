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

/**
 * `/allay new` and `/allay memory`: what the backend remembers about the asker, under their control. Memory is a window:
 * a button for each memory, which opens it with the way to its place and a button to forget it, and buttons to add one
 * or forget everything. The windows' buttons run `/allay memory` subcommands, so ids come fresh from the backend.
 */
class MemoryCommands(
  private val plugin: JavaPlugin,
  private val locales: Locales,
  private val features: Features,
  private val service: Service<ToolContext>,
  private val scope: CoroutineScope,
  /** Where the way to a memory's place gets its button id. */
  private val store: ButtonStore,
) {

  fun newSession(sender: CommandSender): Boolean = request(sender) { lang ->
    service.resetSession(userId(sender))
    listOf(formatMessage(lang.text("memory.session_cleared")))
  }

  /** `/allay memory`: the window of memories; the console reads them as lines. */
  fun open(sender: CommandSender) {
    val player = sender as? Player ?: run { list(sender); return }
    load(player) { lang ->
      val memories = service.memories(userId(player))
      val buttons = buildList {
        add(Windows.button(lang, "memory.window.add", "memory.window.add_hover", "/allay memory new"))
        memories.forEach { memory ->
          val label = (if (memory.place != null) "⌖ " else "") + memory.text.let { if (it.length > LABEL) it.take(LABEL - 1) + "…" else it }
          add(Window.Button(label, Minecraft.withText(lang.text("memory.window.open_hover", "text" to Minecraft.SLOT), memory.text), Windows.WIDE, "/allay memory show ${memory.id}"))
        }
        if (memories.isNotEmpty()) add(Windows.button(lang, "memory.window.clear", "memory.window.clear_hover", "/allay memory clear"))
      }
      val about = if (memories.isEmpty()) lang.text("memory.empty") else lang.text("memory.window.about", "count" to memories.size)
      Window(lang.text("memory.window.title"), listOf(about), buttons, 1, Windows.BODY, exit = Windows.button(lang, "windows.back", "windows.back_hover", "/allay"))
    }
  }

  /** `/allay memory show <id>`: one memory, with the way to its place when it has one, and a button to forget it. */
  fun show(player: Player, id: String) = load(player) { lang ->
    val memory = service.memories(userId(player)).firstOrNull { it.id == id } ?: return@load list(lang, player)
    val place = memory.place
    val paragraphs = listOfNotNull(Minecraft.withText("&f" + Minecraft.SLOT, memory.text), place?.let { lang.text("memory.window.place", "x" to it.x, "y" to it.y, "z" to it.z, "dimension" to lang.text("dimensions.${it.dimension}")) })
    val buttons = buildList {
      // The way only within the dimension the player stands in: a trail can't cross a portal.
      if (place != null && place.dimension == Dimensions.id(player.world)) {
        add(Windows.button(lang, "memory.window.way", "memory.window.way_hover", "/allay do " + store.add(player.uniqueId, Button.Point("", place.x, place.y, place.z, place.dimension))))
      }
      add(Windows.button(lang, "memory.window.forget", "memory.window.forget_hover", "/allay memory forget ${memory.id}"))
    }
    Window(lang.text("memory.window.one"), paragraphs, buttons, 1, Windows.BODY, exit = Windows.button(lang, "windows.back", "windows.back_hover", "/allay memory"))
  }

  /** `/allay memory new`: a field for the text, and whether the memory keeps where the player stands. */
  fun create(player: Player) {
    val lang = locales.of(player)
    Windows.show(player, Window(
      lang.text("memory.window.add_title"), listOf(lang.text("memory.window.add_about")),
      listOf(
        Windows.button(lang, "memory.window.save", "memory.window.save_hover", "/allay memory add \$(text)", width = Windows.WIDE / 2),
        Windows.button(lang, "memory.window.save_here", "memory.window.save_here_hover", "/allay memory here \$(text)", width = Windows.WIDE / 2),
      ),
      2, Windows.BODY, listOf(Window.Input("text", lang.text("memory.window.field"), 200, Windows.WIDE)),
      exit = Windows.button(lang, "windows.back", "windows.back_hover", "/allay memory"),
    ))
  }

  /** `/allay memory clear`: forgetting everything can't be undone, so the window asks once more. */
  fun confirmClear(player: Player) {
    val lang = locales.of(player)
    Windows.show(player, Window(
      lang.text("memory.window.clear_title"), listOf(lang.text("memory.window.clear_about")),
      listOf(Windows.button(lang, "memory.window.clear_yes", "memory.window.clear_hover", "/allay memory clear confirm")),
      1, Windows.BODY, exit = Windows.button(lang, "windows.back", "windows.back_hover", "/allay memory"),
    ))
  }

  /** What the console reads for `/allay memory`. */
  private fun list(sender: CommandSender): Boolean = request(sender) { lang ->
    val memories = service.memories(userId(sender))
    if (memories.isEmpty()) return@request listOf(formatMessage(lang.text("memory.empty")))
    (listOf(lang.text("memory.header")) + memories.mapIndexed { index, memory -> line(lang, index + 1, memory) }).map(::formatMessage)
  }

  /** The list again, as the window after a change. */
  private suspend fun list(lang: Lang, player: Player): Window? {
    onMain { open(player) }
    return null
  }

  /** One memory as the console reads it; one with a place shows where. */
  private fun line(lang: Lang, n: Int, memory: Service.Memory): String = memory.place?.let { place ->
    lang.text("memory.line_place", "n" to n, "text" to memory.text, "x" to place.x, "y" to place.y, "z" to place.z, "dimension" to lang.text("dimensions.${place.dimension}"))
  } ?: lang.text("memory.line", "n" to n, "text" to memory.text)

  /**
   * `/allay memory add <text>` and `/allay memory here <text>`: keeps what the player typed without asking the
   * assistant, with the place where they stand for `here`; coordinates in the text give it that place instead. Then
   * the list again, for a player.
   */
  fun remember(sender: CommandSender, text: String, here: Boolean = false): Boolean {
    val lang = locales.of(sender)
    if (text.trim().length < 3) {
      lang.send(sender, "memory.remember_usage")
      return true
    }
    val place = (sender as? Player)?.let { player ->
      QuickNote.spot(text)?.let { place(player, it) } ?: player.location.takeIf { here }?.let { place(player, QuickNote.Spot(it.blockX, it.blockY, it.blockZ, null)) }
    }
    return save(sender, text.trim(), place)
  }

  /** The note button under an answer: its text with the place the answer named. */
  fun rememberAt(player: Player, text: String, x: Int, y: Int, z: Int, dimension: String?): Boolean {
    val current = Dimensions.id(player.world)
    val where = Dimensions.normalize(dimension) ?: current
    return save(player, text, Service.Place(x, y, z, where, player.world.name.takeIf { where == current }), window = false)
  }

  private fun save(sender: CommandSender, text: String, place: Service.Place?, window: Boolean = true): Boolean =
    request(sender) { lang ->
      try {
        val memory = service.remember(userId(sender), text.take(200), place)
        // From the window, the window shows it; the note button under an answer says so in chat.
        if (window && sender is Player) return@request emptyList<String>().also { onMain { open(sender) } }
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

  /** `/allay memory forget <id>` and `/allay memory clear confirm`: then the list again. */
  fun forget(player: Player, id: String?): Boolean = request(player) { _ ->
    service.forget(userId(player), id)
    onMain { open(player) }
    emptyList()
  }

  /** Builds a window off the server thread and shows it on it; errors go to chat. A null window shows nothing. */
  private fun load(player: Player, block: suspend (Lang) -> Window?) {
    val lang = locales.of(player)
    scope.launch {
      val window = try {
        block(lang)
      } catch (error: Exception) {
        val text = if (error is ApiErrorException) {
          if (error.apiError.statusCode in setOf(404, 405)) lang.text("memory.unsupported") else ErrorHandler.message(lang, error.apiError)
        } else {
          plugin.logger.warning("Memory request failed: $error")
          ErrorHandler.message(lang, error)
        }
        onMain { player.sendMessage(formatMessage(text)) }
        return@launch
      }
      if (window != null) onMain { Windows.show(player, window) }
    }
  }

  private fun onMain(block: () -> Unit) {
    if (plugin.isEnabled) plugin.server.scheduler.runTask(plugin, Runnable(block))
  }

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
      if (lines.isNotEmpty()) onMain { lines.filter { it.isNotEmpty() }.forEach { sender.sendMessage(it) } }
    }
    return true
  }

  private fun userId(sender: CommandSender) = (sender as? Player)?.uniqueId?.toString() ?: "console"

  private companion object {
    /** A memory's button shows this much of its text; the rest is in the tooltip. */
    const val LABEL = 40
  }
}
