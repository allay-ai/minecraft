package chat.allay.modcore.mixin;

import chat.allay.modcore.Mod;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The player switched their game's language: no loader has an event for it on the server, Bukkit's `PlayerLocaleChangeEvent` is this. */
@Mixin(ServerPlayer.class)
abstract class LocaleMixin {
  @Inject(method = "updateOptions", at = @At("HEAD"))
  private void allay$locale(ClientInformation information, CallbackInfo callback) {
    ServerPlayer self = (ServerPlayer) (Object) this;
    String before = self.clientInformation().language();
    if (!before.equals(information.language())) Mod.localeChanged(self, information.language());
  }
}
