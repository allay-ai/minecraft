package chat.allay.spigot

import chat.allay.core.Window
import chat.allay.spigot.paper.PaperWindows
import net.md_5.bungee.api.ChatColor
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.dialog.DialogBase
import net.md_5.bungee.api.dialog.MultiActionDialog
import net.md_5.bungee.api.dialog.action.ActionButton
import net.md_5.bungee.api.dialog.action.StaticAction
import net.md_5.bungee.api.dialog.body.PlainMessageBody
import org.bukkit.entity.Player

/**
 * Draws a [Window]: through Paper's dialog API on Paper, which leaves out Spigot's, and through Spigot's elsewhere. The
 * window stays open while its buttons' commands run, so a link comes back to it; the command closes it or opens the next.
 */
object Windows {
  private val paper = runCatching { Class.forName("io.papermc.paper.dialog.Dialog") }.isSuccess

  fun show(player: Player, window: Window) = if (paper) PaperWindows.show(player, window) else spigot(player, window)

  fun close(player: Player) = if (paper) PaperWindows.close(player) else player.clearDialog()

  private fun spigot(player: Player, window: Window) {
    val base = DialogBase(text(window.title))
      .body(window.paragraphs.map { PlainMessageBody(linked(it), window.width) })
      .canCloseWithEscape(true)
      .pause(false)
      .afterAction(DialogBase.AfterAction.NONE)
    val buttons = window.buttons.map { button ->
      val click = button.url?.let { ClickEvent(ClickEvent.Action.OPEN_URL, it) } ?: ClickEvent(ClickEvent.Action.RUN_COMMAND, button.command!!)
      ActionButton(text(button.label), text(button.tooltip), button.width, StaticAction(click))
    }
    player.showDialog(MultiActionDialog(base, buttons, window.columns, null))
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
