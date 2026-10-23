package chat.allay.spigot

import chat.allay.core.Lang
import chat.allay.core.Window
import chat.allay.spigot.Minecraft.formatMessage
import chat.allay.spigot.paper.PaperWindows
import net.md_5.bungee.api.ChatColor
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.dialog.DialogBase
import net.md_5.bungee.api.dialog.MultiActionDialog
import net.md_5.bungee.api.dialog.action.ActionButton
import net.md_5.bungee.api.dialog.action.RunCommandAction
import net.md_5.bungee.api.dialog.action.StaticAction
import net.md_5.bungee.api.dialog.input.TextInput
import net.md_5.bungee.api.dialog.body.PlainMessageBody
import org.bukkit.entity.Player

/**
 * Draws a [Window]: through Paper's dialog API on Paper, which leaves out Spigot's, and through Spigot's elsewhere. The
 * window stays open while its buttons' commands run, so a link comes back to it; the command closes it or opens the next.
 */
object Windows {
  private val paper = runCatching { Class.forName("io.papermc.paper.dialog.Dialog") }.isSuccess

  /** Widths in pixels: the text, and a button or field across it. */
  const val BODY = 400
  const val WIDE = 300

  /**
   * Gray reads poorly over the blurred game, so a window's paragraphs are white, and what chat shows in white turns
   * yellow. Texts may still carry `&` codes; they're colored here.
   */
  fun show(player: Player, window: Window) {
    val ready = window.copy(title = formatMessage(window.title), paragraphs = window.paragraphs.map { brighter(formatMessage(it)) })
    if (paper) PaperWindows.show(player, ready) else spigot(player, ready)
  }

  /** A button from [lang]'s texts: [label] and [hover] are keys. */
  fun button(lang: Lang, label: String, hover: String, command: String? = null, url: String? = null, width: Int = WIDE, vararg values: Pair<String, Any>) =
    Window.Button(formatMessage(lang.text(label, *values)), formatMessage(lang.text(hover, *values)), width, command, url)

  private fun brighter(text: String) = text.replace("§f", "§e").replace("§7", "§f").replace("§8", "§7")

  fun close(player: Player) = if (paper) PaperWindows.close(player) else player.clearDialog()

  private fun spigot(player: Player, window: Window) {
    val base = DialogBase(text(window.title))
      .body(window.paragraphs.map { PlainMessageBody(if (window.links) linked(it) else text(it), window.width) })
      .inputs(window.inputs.map { TextInput(it.key, it.width, text(it.label), true, it.initial, it.maxLength) })
      .canCloseWithEscape(true)
      .pause(false)
      .afterAction(DialogBase.AfterAction.NONE)
    player.showDialog(MultiActionDialog(base, window.buttons.map(::button), window.columns, window.exit?.let(::button)))
  }

  private fun button(button: Window.Button): ActionButton {
    val action = when {
      button.url != null -> StaticAction(ClickEvent(ClickEvent.Action.OPEN_URL, button.url))
      button.template -> RunCommandAction(button.command!!)
      else -> StaticAction(ClickEvent(ClickEvent.Action.RUN_COMMAND, button.command!!))
    }
    return ActionButton(text(button.label), text(button.tooltip), button.width, action)
  }

  private fun text(legacy: String) = TextComponent(*plain(legacy))

  /** The window's text, where addresses like allay.chat open in the browser (BungeeCord finds them) and look like links. */
  private fun linked(legacy: String) = TextComponent(*TextComponent.fromLegacyText(legacy).onEach {
    if (it.clickEvent?.action == ClickEvent.Action.OPEN_URL) {
      it.color = ChatColor.AQUA
      it.isUnderlined = true
    }
  })
}
