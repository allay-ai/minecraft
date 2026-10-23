package chat.allay.core

import chat.allay.core.tools.Books
import chat.allay.core.tools.Button
import chat.allay.core.tools.ButtonStore
import chat.allay.core.tools.InlineButtons
import chat.allay.core.tools.ToolError

/**
 * Buttons in and under answers. A button runs `/allay do <id>`; the id leads to an action this player was offered, so
 * nobody can press a button they weren't shown. What a button does in the game (a way, a window, a book, an agent)
 * is the platform's [Game].
 */
class Clicks(
  private val platform: Platform,
  private val config: Config,
  private val locales: Locales,
  private val books: Books,
  private val chat: ChatMode,
  private val store: ButtonStore,
  private val game: Game,
  /** Asks for the player, publicly when the third argument says so. */
  private val ask: (PlayerAudience, String, Boolean) -> Unit,
  /** Where the note button saves its text: a memory with a place on the backend. */
  private val memory: () -> MemoryCommands,
) {
  /** The buttons that reach into the game, on this platform. */
  interface Game {
    /**
     * The [Button] behind a tag the model wrote into its answer, checked with the asker's view of the world on the
     * server thread; a [ToolError] means it can't work.
     */
    fun check(run: Run, player: PlayerAudience, tag: InlineButtons.Part.Tag): Button

    /** A button [Clicks] doesn't handle itself: a way, a window, a book, an agent's reply, picking, undo. */
    fun press(player: PlayerAudience, button: Button)
  }

  private fun lang(player: PlayerAudience) = locales.of(player)

  /** A piece of an answer: its own text, or a button with the label to draw when it can't work for this reader. */
  sealed interface Piece {
    data class Text(val text: String) : Piece
    data class Press(val label: String, val button: Button?) : Piece
  }

  /**
   * The answer's tags as buttons, checked once on the server thread with the asker's view of the world: a tag that
   * can't work becomes its label. With buttons switched off in `config.yml`, every tag is a label.
   */
  fun pieces(run: Run, answer: String): List<Piece> {
    val player = run.asker.uuid?.let(platform::player)
    return InlineButtons.parse(answer).map { part ->
      when (part) {
        is InlineButtons.Part.Text -> Piece.Text(part.text)
        is InlineButtons.Part.Tag -> Piece.Press(part.label, player?.takeIf { config.tools.enabled("add_buttons") }?.let { asker ->
          try {
            game.check(run, asker, part)
          } catch (error: ToolError) {
            platform.log.fine("A button in the answer can't work: ${error.message}")
            null
          }
        })
      }
    }
  }

  /**
   * The answer in [template] (its `{text}` is [Legacy.SLOT]) for [reader], with each button in place. Colors carry
   * over a button, as if it weren't there. In an answer everyone reads ([public]), a command or a line to the chat stays
   * a label: someone could talk Allay into one for everybody. The anvil plan reads the asker's inventory, and nobody gets
   * the way to themself.
   */
  fun answer(reader: PlayerAudience, template: String, pieces: List<Piece>, public: Boolean, asker: PlayerAudience?): Line {
    val lang = lang(reader)
    val (before, after) = Legacy.color(template).split(Legacy.SLOT, limit = 2).let { it[0] to it.getOrElse(1) { "" } }
    val parts = mutableListOf<Line.Part>()
    var drawn = ""
    fun text(legacy: String) {
      if (legacy.isEmpty()) return
      parts += Line.Text(Legacy.lastColors(drawn) + legacy, links = true)
      drawn += legacy
    }
    text(before)
    pieces.forEach { piece ->
      when (piece) {
        is Piece.Text -> text(Legacy.color(piece.text))
        is Piece.Press -> {
          val button = piece.button?.let { if (public && it is Button.Ask) it.copy(public = true) else it }?.takeUnless {
            public && (it is Button.Command || it is Button.Say) ||
              reader.uuid != asker?.uuid && it is Button.Anvil ||
              it is Button.Follow && it.player.equals(reader.name, ignoreCase = true)
          }
          parts += when (button) {
            null -> Line.Text(Legacy.lastColors(drawn) + piece.label, links = true)
            else -> part(lang, reader, button, lang.text("buttons.format", "label" to piece.label))
          }
        }
      }
    }
    text(after)
    return Line(parts)
  }

  /** Draws one chat line of `[label]` buttons. */
  fun show(player: PlayerAudience, buttons: List<Button>) {
    if (buttons.isEmpty()) return
    val lang = lang(player)
    val parts = mutableListOf<Line.Part>()
    buttons.forEachIndexed { index, button ->
      if (index > 0) parts += Line.SPACE
      val label = button.label.ifBlank { defaultLabel(lang, button) }
      parts += part(lang, player, button, lang.text("buttons.format", "label" to label))
    }
    player.send(Line(parts))
  }

  /** The quiet tip under a second `/ask`, like Claude Code's: chat mode exists, with buttons to try it or hide the tip. */
  fun tip(player: PlayerAudience) {
    val lang = lang(player)
    val on = store.add(player.uuid, Button.ChatMode(""))
    val off = store.add(player.uuid, Button.HideTips(""))
    player.send(Line(
      Line.Text(Legacy.color(lang.text("chat_mode.tip")), links = true),
      Line.Text("  "),
      clickable(lang.text("chat_mode.tip_enable"), on, lang.text("chat_mode.tip_enable_hover")),
      Line.SPACE,
      clickable(lang.text("chat_mode.tip_hide"), off, lang.text("chat_mode.tip_hide_hover")),
    ))
  }

  /** A button as [text]: copying happens in the client, anything else goes through the store. */
  private fun part(lang: Lang, player: PlayerAudience, button: Button, text: String) =
    if (button is Button.Copy) Line.Button(Legacy.color(text), Legacy.color(hover(lang, player, button)), Line.Click.Copy(button.text))
    else clickable(text, store.add(player.uuid, button), hover(lang, player, button))

  private fun clickable(text: String, id: String, hover: String) = Line.Button(Legacy.color(text), Legacy.color(hover), Line.Click.Run("/allay do $id"))

  private fun defaultLabel(lang: Lang, button: Button) = when (button) {
    is Button.ReadBook -> lang.text("buttons.read_book")
    is Button.TakeBook -> lang.text("buttons.take_book")
    else -> "?"
  }

  private fun hover(lang: Lang, player: PlayerAudience, button: Button): String = when (button) {
    is Button.Ask -> lang.text("buttons.hover.ask", "question" to button.question)
    is Button.Command -> lang.text("buttons.hover.command", "command" to button.command)
    is Button.Say -> lang.text("buttons.hover.say", "text" to button.text)
    is Button.Point -> lang.text("buttons.hover.point", "x" to button.x, "y" to button.y, "z" to button.z)
    is Button.Follow -> lang.text("buttons.hover.follow", "name" to button.player)
    is Button.Note -> lang.text("buttons.hover.note", "text" to button.text, "x" to button.x, "y" to button.y, "z" to button.z)
    is Button.Copy -> lang.text("buttons.hover.copy", "text" to button.text)
    is Button.Recipe -> lang.text("buttons.hover.recipe")
    is Button.Enchants -> lang.text("buttons.hover.enchants")
    is Button.Brewing -> lang.text("buttons.hover.brewing")
    is Button.Anvil -> lang.text("buttons.hover.anvil")
    is Button.Villagers -> lang.text("buttons.hover.villagers")
    is Button.ReadBook -> lang.text("buttons.hover.read_book", "title" to (books.find(player.uuid, button.book)?.title ?: ""))
    is Button.TakeBook -> lang.text("buttons.hover.take_book", "title" to (books.find(player.uuid, button.book)?.title ?: ""))
    is Button.ChatMode -> lang.text("chat_mode.tip_enable_hover")
    is Button.HideTips -> lang.text("chat_mode.tip_hide_hover")
    is Button.Reply -> lang.text("buttons.hover.reply")
    is Button.Pick -> lang.text("buttons.hover.pick_${button.action}")
    is Button.Undo -> lang.text("buttons.hover.undo")
  }

  /** `/allay do <id>`. */
  fun press(player: PlayerAudience, id: String) {
    val lang = lang(player)
    when (val button = store.take(player.uuid, id)) {
      null -> lang.send(player, "buttons.expired")
      is Button.Ask -> {
        // A public question shows itself to everyone; one the player may not ask publicly stays theirs.
        val public = button.public && player.can(Permissions.ASKALL)
        if (!public) lang.send(player, "buttons.question", "question" to button.question)
        ask(player, button.question, public)
      }
      is Button.Command -> command(player, button.command)
      is Button.Say -> chat.say(player, button.text)
      is Button.Note -> memory().rememberAt(player, button.text, button.x, button.y, button.z, button.dimension)
      // Copying happens in the client and never reaches the store; kept for a complete `when`.
      is Button.Copy -> Unit
      is Button.ChatMode -> chat.set(player, true)
      is Button.HideTips -> chat.hints(player, false)
      else -> game.press(player, button)
    }
  }

  /** A command button runs as the player who clicks, as if typed; `blocked_commands` stay blocked, as for `/askx`. */
  private fun command(player: PlayerAudience, command: String) {
    val line = command.removePrefix("/")
    if (line.substringBefore(' ').lowercase() in config.blockedCommands) return lang(player).send(player, "buttons.command_blocked", "command" to command)
    platform.log.info("${player.name} ran a command from a button in Allay's answer: /$line")
    platform.dispatch(player, line)
  }
}
