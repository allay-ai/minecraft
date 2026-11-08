package chat.allay.core

import chat.allay.core.tools.Button
import chat.allay.core.tools.ButtonStore
import chat.allay.core.tools.Dimensions
import chat.allay.core.tools.QuickNote
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * `/allay new` and `/allay memory`: what the backend remembers about the asker, under their control. Memory is a window:
 * a button for each memory, which opens it with the way to its place and buttons to pin, edit, or forget it, the
 * player's tags to filter by, and buttons to add one or forget everything. The windows' buttons run `/allay memory` subcommands, so ids come fresh from the backend.
 */
class MemoryCommands(
  private val platform: Platform,
  private val locales: Locales,
  private val service: Service<*>,
  private val scope: CoroutineScope,
  /** Where the way to a memory's place gets its button id. */
  private val store: ButtonStore,
) {

  fun newSession(sender: Audience): Boolean = request(sender) { lang ->
    service.resetSession(userId(sender))
    listOf(Legacy.color(lang.text("memory.session_cleared")))
  }

  /**
   * `/allay memory` and `/allay memory tag <tag>`: the window of memories, pinned ones first, then places nearest
   * first, then the rest newest first; the tags the player uses most filter it. The console reads them as lines.
   */
  fun open(sender: Audience, tag: String? = null) {
    val player = sender as? PlayerAudience ?: run { list(sender); return }
    load(player) { lang ->
      val all = service.memories(userId(player))
      val memories = if (tag == null) all else all.filter { tag in it.tags }
      val buttons = buildList {
        add(Window.button(lang, "memory.window.add", "memory.window.add_hover", "/allay memory new"))
        if (tag != null) add(Window.button(lang, "memory.window.all_tags", "memory.window.all_tags_hover", "/allay memory"))
        else tags(all).forEach { (name, count) ->
          add(Window.Button(Legacy.withText("&b#" + Legacy.SLOT + " &7($count)", name), Legacy.withText(lang.text("memory.window.tag_hover", "tag" to Legacy.SLOT), name), Window.WIDE, "/allay memory tag $name"))
        }
        ordered(memories, player.position).forEach { memory ->
          // The icon is ours and may be colored; the label is the player's words, so it never is.
          val label = memory.label.let { if (it.length > LABEL) it.take(LABEL - 1) + "…" else it }
          add(Window.Button(Legacy.withText(icon(memory) + Legacy.SLOT, label), Legacy.withText(lang.text("memory.window.open_hover", "text" to Legacy.SLOT), memory.text), Window.WIDE, "/allay memory show ${memory.id}"))
        }
        if (tag == null && memories.isNotEmpty()) add(Window.button(lang, "memory.window.clear", "memory.window.clear_hover", "/allay memory clear"))
      }
      val about = when {
        all.isEmpty() -> lang.text("memory.empty")
        tag != null -> Legacy.withText(lang.text("memory.window.tagged", "tag" to Legacy.SLOT, "count" to memories.size), tag)
        else -> lang.text("memory.window.about", "count" to all.size)
      }
      Window(lang.text("memory.window.title"), listOf(about), buttons, 1, Window.BODY, exit = Window.button(lang, "windows.back", "windows.back_hover", if (tag == null) "/allay" else "/allay memory"))
    }
  }

  /**
   * `/allay memory show <id>`: one memory with its kind, tags, and place, the way there, and buttons to pin, edit, or
   * forget it.
   */
  fun show(player: PlayerAudience, id: String) = load(player) { lang ->
    val memory = service.memories(userId(player)).firstOrNull { it.id == id } ?: return@load list(player)
    val place = memory.place
    val here = player.position
    val away = place?.takeIf { it.dimension == here.dimension }?.let { Math.round(Math.hypot((it.x - here.x).toDouble(), (it.z - here.z).toDouble())) }
    val paragraphs = listOfNotNull(
      memory.title?.let { Legacy.withText("&6" + Legacy.SLOT, it) },
      Legacy.withText("&f" + Legacy.SLOT, memory.text),
      lang.text("memory.window.kind", "kind" to lang.text("memory.kinds.${memory.category?.let { "category_$it" } ?: memory.kind ?: if (place != null) "place" else "fact"}")),
      memory.tags.takeIf { it.isNotEmpty() }?.let { tags -> Legacy.withText(lang.text("memory.window.tags", "tags" to Legacy.SLOT), tags.joinToString(" ") { "#$it" }) },
      place?.let { lang.text("memory.window.place", "x" to it.x, "y" to it.y, "z" to it.z, "dimension" to lang.text("dimensions.${it.dimension}")) },
      away?.let { lang.text("memory.window.away", "blocks" to it) },
    )
    val buttons = buildList {
      // The way only within the dimension the player stands in: a trail can't cross a portal.
      if (place != null && place.dimension == here.dimension) {
        add(Window.button(lang, "memory.window.way", "memory.window.way_hover", "/allay do " + store.add(player.uuid, Button.Point("", place.x, place.y, place.z, place.dimension))))
      }
      add(if (memory.pinned) Window.button(lang, "memory.window.unpin", "memory.window.unpin_hover", "/allay memory unpin ${memory.id}")
        else Window.button(lang, "memory.window.pin", "memory.window.pin_hover", "/allay memory pin ${memory.id}"))
      add(Window.button(lang, "memory.window.edit", "memory.window.edit_hover", "/allay memory edit ${memory.id}"))
      add(Window.button(lang, "memory.window.forget", "memory.window.forget_hover", "/allay memory forget ${memory.id}"))
    }
    Window(lang.text("memory.window.one"), paragraphs, buttons, 1, Window.BODY, exit = Window.button(lang, "windows.back", "windows.back_hover", "/allay memory"))
  }

  /** `/allay memory new`: fields for the text, a title, and tags, and whether the memory keeps where the player stands. */
  fun create(player: PlayerAudience) {
    val lang = locales.of(player)
    player.show(Window(
      lang.text("memory.window.add_title"), listOf(lang.text("memory.window.add_about")),
      listOf(
        Window.button(lang, "memory.window.save", "memory.window.save_hover", "/allay memory add ${FORM}", width = Window.WIDE / 2),
        Window.button(lang, "memory.window.save_here", "memory.window.save_here_hover", "/allay memory here ${FORM}", width = Window.WIDE / 2),
      ),
      2, Window.BODY, fields(lang),
      exit = Window.button(lang, "windows.back", "windows.back_hover", "/allay memory"),
    ))
  }

  /** `/allay memory edit <id>`: the same fields, filled with what the memory says now. */
  fun edit(player: PlayerAudience, id: String) = load(player) { lang ->
    val memory = service.memories(userId(player)).firstOrNull { it.id == id } ?: return@load list(player)
    Window(
      lang.text("memory.window.edit_title"), listOf(lang.text("memory.window.edit_about")),
      listOf(Window.button(lang, "memory.window.save_edit", "memory.window.save_edit_hover", "/allay memory change ${memory.id} ${FORM}")),
      1, Window.BODY, fields(lang, memory),
      exit = Window.button(lang, "windows.back", "windows.back_hover", "/allay memory show ${memory.id}"),
    )
  }

  /** `/allay memory change <id> <tags> | <title> | <text>`: what the edit window sends; then the memory again. */
  fun change(player: PlayerAudience, id: String, form: String) {
    val (tags, title, text) = Form.parse(form) ?: return show(player, id)
    if (text.length < 3) {
      locales.of(player).send(player, "memory.remember_usage")
      return
    }
    changed(player, id, buildJsonObject {
      put("text", text.take(MAX_TEXT))
      put("title", title.take(MAX_TITLE))
      putJsonArray("tags") { tags.forEach { add(it) } }
    })
  }

  /** `/allay memory pin <id>` and `unpin <id>`: Allay keeps a pinned memory in mind every time. */
  fun pin(player: PlayerAudience, id: String, pinned: Boolean) = changed(player, id, buildJsonObject { put("pinned", pinned) })

  private fun changed(player: PlayerAudience, id: String, changes: JsonObject) {
    request(player) { lang ->
      try {
        service.change(userId(player), id, changes)
        platform.scheduler.main { show(player, id) }
        emptyList()
      } catch (error: ApiErrorException) {
        if (error.apiError.code != "MEMORY_PINS_FULL") throw error
        listOf(Legacy.color(lang.text("memory.pins_full")))
      }
    }
  }

  /** The window's fields: tags, a title, and the text, filled from [memory] when it's an edit. */
  private fun fields(lang: Lang, memory: Service.Memory? = null) = listOf(
    Window.Input("text", lang.text("memory.window.field"), MAX_TEXT, Window.WIDE, memory?.text.orEmpty()),
    Window.Input("title", lang.text("memory.window.field_title"), MAX_TITLE, Window.WIDE, memory?.title.orEmpty()),
    Window.Input("tags", lang.text("memory.window.field_tags"), 120, Window.WIDE, memory?.tags?.joinToString(", ").orEmpty()),
  )

  /** `/allay memory clear`: forgetting everything can't be undone, so the window asks once more. */
  fun confirmClear(player: PlayerAudience) {
    val lang = locales.of(player)
    player.show(Window(
      lang.text("memory.window.clear_title"), listOf(lang.text("memory.window.clear_about")),
      listOf(Window.button(lang, "memory.window.clear_yes", "memory.window.clear_hover", "/allay memory clear confirm")),
      1, Window.BODY, exit = Window.button(lang, "windows.back", "windows.back_hover", "/allay memory"),
    ))
  }

  /** What the console reads for `/allay memory`. */
  private fun list(sender: Audience): Boolean = request(sender) { lang ->
    val memories = service.memories(userId(sender))
    if (memories.isEmpty()) return@request listOf(Legacy.color(lang.text("memory.empty")))
    (listOf(lang.text("memory.header")) + memories.mapIndexed { index, memory -> line(lang, index + 1, memory) }).map(Legacy::color)
  }

  /** The list again, as the window after a change. */
  private fun list(player: PlayerAudience): Window? {
    platform.scheduler.main { open(player) }
    return null
  }

  /** One memory as the console reads it, pinned ones starred, with its tags; one with a place shows where. */
  private fun line(lang: Lang, n: Int, memory: Service.Memory): String {
    val text = (if (memory.pinned) "★ " else "") + memory.text + memory.tags.joinToString("") { " #$it" }
    return memory.place?.let { place ->
      lang.text("memory.line_place", "n" to n, "text" to text, "x" to place.x, "y" to place.y, "z" to place.z, "dimension" to lang.text("dimensions.${place.dimension}"))
    } ?: lang.text("memory.line", "n" to n, "text" to text)
  }

  /**
   * `/allay memory add <text>` and `/allay memory here <text>`: keeps what the player typed without asking the
   * assistant, with the place where they stand for `here`; coordinates in the text give it that place instead. Then
   * the list again, for a player.
   */
  fun remember(sender: Audience, typed: String, here: Boolean = false): Boolean {
    val lang = locales.of(sender)
    // The window sends tags and a title before the text; a typed line is the text alone.
    val (tags, title, text) = Form.parse(typed) ?: Triple(emptyList(), "", typed.trim())
    if (text.length < 3) {
      lang.send(sender, "memory.remember_usage")
      return true
    }
    val place = (sender as? PlayerAudience)?.let { player ->
      QuickNote.spot(text)?.let { place(player, it) } ?: player.position.takeIf { here }?.let { place(player, QuickNote.Spot(it.x, it.y, it.z, null)) }
    }
    return save(sender, text, place, title = title.ifEmpty { null }, tags = tags)
  }

  /** The note button under an answer: its text with the place the answer named. */
  fun rememberAt(player: PlayerAudience, text: String, x: Int, y: Int, z: Int, dimension: String?): Boolean {
    val position = player.position
    val where = Dimensions.normalize(dimension) ?: position.dimension
    return save(player, text, Service.Place(x, y, z, where, position.world.takeIf { where == position.dimension }), window = false)
  }

  private fun save(sender: Audience, text: String, place: Service.Place?, window: Boolean = true, title: String? = null, tags: List<String> = emptyList()): Boolean =
    request(sender) { lang ->
      try {
        val memory = service.remember(userId(sender), text.take(MAX_TEXT), place, title?.take(MAX_TITLE), tags)
        // From the window, the window shows it; the note button under an answer says so in chat.
        if (window && sender is PlayerAudience) return@request emptyList<String>().also { platform.scheduler.main { open(sender) } }
        val saved = memory.place ?: return@request listOf(Legacy.withText(lang.text("memory.remembered", "text" to Legacy.SLOT), memory.text))
        listOf(Legacy.withText(lang.text("memory.noted", "text" to Legacy.SLOT, "x" to saved.x, "y" to saved.y, "z" to saved.z, "dimension" to lang.text("dimensions.${saved.dimension}")), memory.text))
      } catch (error: ApiErrorException) {
        if (error.apiError.code != "MEMORY_FULL") throw error
        listOf(Legacy.color(lang.text("memory.full")))
      }
    }

  /** The place the text names, read on the server thread; without a height, the player's own. */
  private fun place(player: PlayerAudience, spot: QuickNote.Spot): Service.Place {
    val position = player.position
    val dimension = spot.dimension ?: position.dimension
    // The world's name tells two overworlds apart; a place in another dimension keeps only the dimension.
    return Service.Place(spot.x, spot.y ?: position.y, spot.z, dimension, position.world.takeIf { dimension == position.dimension })
  }

  /** `/allay memory forget <id>` and `/allay memory clear confirm`: then the list again. */
  fun forget(player: PlayerAudience, id: String?): Boolean = request(player) { _ ->
    service.forget(userId(player), id)
    platform.scheduler.main { open(player) }
    emptyList()
  }

  /** Builds a window off the server thread and shows it on it; errors go to chat. A null window shows nothing. */
  private fun load(player: PlayerAudience, block: suspend (Lang) -> Window?) {
    val lang = locales.of(player)
    scope.launch {
      val window = try {
        block(lang)
      } catch (error: Exception) {
        val text = if (error is ApiErrorException) {
          if (error.apiError.statusCode in setOf(404, 405)) lang.text("memory.unsupported") else ErrorHandler.message(lang, error.apiError)
        } else {
          platform.log.warning("Memory request failed: $error")
          ErrorHandler.message(lang, error)
        }
        platform.scheduler.main { player.send(Legacy.color(text)) }
        return@launch
      }
      if (window != null) platform.scheduler.main { player.show(window) }
    }
  }

  /** Runs [block] off the server thread; it returns finished chat lines, colored already. */
  private fun request(sender: Audience, block: suspend (Lang) -> List<String>): Boolean {
    val lang = locales.of(sender)
    scope.launch {
      val lines = try {
        block(lang)
      } catch (error: ApiErrorException) {
        // A backend before 2.1 has no such routes, and one before 5.1 can't add a memory.
        listOf(Legacy.color(if (error.apiError.statusCode in setOf(404, 405)) lang.text("memory.unsupported") else ErrorHandler.message(lang, error.apiError)))
      } catch (error: Exception) {
        platform.log.warning("Memory request failed: $error")
        listOf(Legacy.color(ErrorHandler.message(lang, error)))
      }
      if (lines.isNotEmpty()) platform.scheduler.main { lines.filter { it.isNotEmpty() }.forEach(sender::send) }
    }
    return true
  }

  private fun userId(sender: Audience) = sender.uuid?.toString() ?: "console"

  /** Pinned first, then places in the player's dimension nearest first, then the rest newest first. */
  private fun ordered(memories: List<Service.Memory>, here: Protocol.Position): List<Service.Memory> {
    val distance = { m: Service.Memory -> m.place?.takeIf { it.dimension == here.dimension }?.let { Math.hypot((it.x - here.x).toDouble(), (it.z - here.z).toDouble()) } }
    val (pinned, rest) = memories.reversed().partition { it.pinned }
    val (near, other) = rest.partition { distance(it) != null }
    return pinned + near.sortedBy { distance(it) } + other
  }

  /**
   * The tags the player uses most, with how many memories have each. A tag on one memory filters to that memory alone,
   * which its own button already opens, so only tags shared by two or more get a button.
   */
  private fun tags(memories: List<Service.Memory>) =
    memories.flatMap { it.tags }.groupingBy { it }.eachCount().entries.filter { it.value > 1 }
      .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).take(TAG_BUTTONS).map { it.key to it.value }

  /** What the window and `/allay memory edit` send: tags, a title, and the text, split by bars. */
  object Form {
    /** Tags, the title, and the text, or null when [typed] isn't the window's form. The text may hold bars itself. */
    fun parse(typed: String): Triple<List<String>, String, String>? {
      val parts = typed.split("|", limit = 3)
      if (parts.size < 3) return null
      val tags = parts[0].split(',', ' ').map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }.distinct().take(5)
      return Triple(tags, parts[1].trim(), parts[2].trim())
    }
  }

  private companion object {
    /** A memory's button shows this much of its title or text; the rest is in the tooltip. */
    const val LABEL = 40
    const val MAX_TEXT = 500
    const val MAX_TITLE = 40
    const val TAG_BUTTONS = 4
    /** The fields as the window's buttons send them: tags | title | text. */
    const val FORM = "\$(tags) | \$(title) | \$(text)"

    /** A mark for a memory's kind or place: a star for a pinned one first. */
    fun icon(memory: Service.Memory): String = (if (memory.pinned) "&6★&r " else "") + when (memory.category) {
      "base" -> "⌂ "; "farm" -> "☘ "; "storage" -> "▣ "; "portal" -> "◎ "; "villager" -> "☺ "; "mine" -> "⛏ "; "structure" -> "⚑ "; "build" -> "⚒ "
      else -> when {
        memory.place != null -> "⌖ "
        memory.kind == "goal" -> "➹ "
        memory.kind == "preference" -> "♥ "
        else -> ""
      }
    }
  }
}
