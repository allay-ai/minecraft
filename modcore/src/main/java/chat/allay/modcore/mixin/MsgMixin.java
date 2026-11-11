package chat.allay.modcore.mixin;

import chat.allay.modcore.Mod;
import java.util.Collection;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.server.commands.MsgCommand;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A private message through the game's `/msg`, `/tell`, or `/w`, with its targets resolved: no loader has an event for it. */
@Mixin(MsgCommand.class)
abstract class MsgMixin {
  @Inject(method = "sendMessage", at = @At("HEAD"))
  private static void allay$whisper(CommandSourceStack source, Collection<ServerPlayer> players, PlayerChatMessage message, CallbackInfo callback) {
    Mod.whispered(source, players, message.signedContent());
  }
}
