package chat.allay.fabric.mixin.client;

import chat.allay.modcore.Elsewhere;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The commands the server sent, before Fabric adds the client's own to the same tree: so the mod knows whether the
 * server runs Allay itself. A low priority runs it ahead of Fabric API's injection at the same spot.
 */
@Mixin(value = ClientPacketListener.class, priority = 100)
abstract class CommandTreeMixin {
  @Shadow private CommandDispatcher<SharedSuggestionProvider> commands;

  @Inject(method = "handleCommands", at = @At("TAIL"))
  private void allay$serverCommands(ClientboundCommandsPacket packet, CallbackInfo callback) {
    Elsewhere.serverCommands(commands.getRoot().getChildren().stream().map(node -> node.getName()).toList());
  }
}
