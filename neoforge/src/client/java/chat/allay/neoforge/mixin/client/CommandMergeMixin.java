package chat.allay.neoforge.mixin.client;

import chat.allay.modcore.Elsewhere;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.commands.CommandBuildContext;
import net.neoforged.neoforge.client.ClientCommandHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The commands the server sent, before NeoForge merges the client's own into the same tree: so the mod knows whether
 * the server runs Allay itself. NeoForge asks the client for its commands right after, in the same call.
 */
@Mixin(value = ClientCommandHandler.class, remap = false)
abstract class CommandMergeMixin {
  @Inject(method = "mergeServerCommands", at = @At("HEAD"))
  private static void allay$serverCommands(CommandDispatcher<ClientSuggestionProvider> server, CommandBuildContext context, CallbackInfoReturnable<CommandDispatcher<ClientSuggestionProvider>> callback) {
    Elsewhere.serverCommands(server.getRoot().getChildren().stream().map(node -> node.getName()).toList());
  }
}
