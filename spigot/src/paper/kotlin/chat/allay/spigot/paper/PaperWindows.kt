package chat.allay.spigot.paper

import chat.allay.core.Window
import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.Style
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.entity.Player

/**
 * [Window] through Paper's dialog API. Paper leaves out Spigot's (`Player.showDialog` with BungeeCord's classes), so
 * this source set compiles against paper-api and loads only on Paper.
 */
object PaperWindows {
  private val legacy = LegacyComponentSerializer.legacySection()

  /** The window's text, where addresses like allay.chat open in the browser and look like links. */
  private val linked = LegacyComponentSerializer.builder().character(LegacyComponentSerializer.SECTION_CHAR)
    .extractUrls(Style.style(NamedTextColor.AQUA, TextDecoration.UNDERLINED)).build()

  fun show(player: Player, window: Window) {
    val base = DialogBase.builder(legacy.deserialize(window.title))
      .body(window.paragraphs.map { DialogBody.plainMessage(linked.deserialize(it), window.width) })
      .canCloseWithEscape(true)
      .pause(false)
      .afterAction(DialogBase.DialogAfterAction.NONE)
      .build()
    val buttons = window.buttons.map { button ->
      val click = button.url?.let { ClickEvent.openUrl(it) } ?: ClickEvent.runCommand(button.command!!)
      ActionButton.create(legacy.deserialize(button.label), legacy.deserialize(button.tooltip), button.width, DialogAction.staticAction(click))
    }
    player.showDialog(Dialog.create { it.empty().base(base).type(DialogType.multiAction(buttons, null, window.columns)) })
  }

  fun close(player: Player) = player.closeDialog()
}
