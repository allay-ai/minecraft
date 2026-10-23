package chat.allay.modcore

import chat.allay.core.Window

import com.mojang.serialization.JavaOps
import net.minecraft.core.Holder
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.protocol.common.ClientboundClearDialogPacket
import net.minecraft.server.dialog.ActionButton
import net.minecraft.server.dialog.CommonButtonData
import net.minecraft.server.dialog.CommonDialogData
import net.minecraft.server.dialog.DialogAction
import net.minecraft.server.dialog.Input
import net.minecraft.server.dialog.MultiActionDialog
import net.minecraft.server.dialog.action.CommandTemplate
import net.minecraft.server.dialog.action.ParsedTemplate
import net.minecraft.server.dialog.action.StaticAction
import net.minecraft.server.dialog.body.PlainMessage
import net.minecraft.server.dialog.input.TextInput
import net.minecraft.server.level.ServerPlayer
import java.net.URI
import java.util.Optional

/**
 * Draws a [Window] as the game's dialog. The window stays open while its buttons' commands run, so a link comes back
 * to it; the command closes it or opens the next. It doesn't pause a singleplayer game.
 */
object Windows {
  fun show(player: ServerPlayer, window: Window) {
    val ready = window.ready()
    val common = CommonDialogData(
      Texts.legacy(ready.title),
      Optional.empty(),
      true,
      false,
      DialogAction.NONE,
      ready.paragraphs.map { PlainMessage(if (ready.links) Texts.linked(it) else Texts.legacy(it), ready.width) },
      ready.inputs.map { Input(it.key, TextInput(it.width, Texts.legacy(it.label), true, it.initial, it.maxLength, Optional.empty())) },
    )
    player.openDialog(Holder.direct(MultiActionDialog(common, ready.buttons.map(::button), Optional.ofNullable(ready.exit?.let(::button)), ready.columns)))
  }

  fun close(player: ServerPlayer) = player.connection.send(ClientboundClearDialogPacket.INSTANCE)

  private fun button(button: Window.Button): ActionButton {
    val action = when {
      button.url != null -> StaticAction(ClickEvent.OpenUrl(URI(button.url)))
      button.template -> CommandTemplate(ParsedTemplate.CODEC.parse(JavaOps.INSTANCE, button.command!!).getOrThrow())
      else -> StaticAction(ClickEvent.RunCommand(button.command!!))
    }
    return ActionButton(CommonButtonData(Texts.legacy(button.label), Optional.of(Texts.legacy(button.tooltip)), button.width), Optional.of(action))
  }
}
