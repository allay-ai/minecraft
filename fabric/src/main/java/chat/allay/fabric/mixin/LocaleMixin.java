package chat.allay.fabric.mixin;

import chat.allay.fabric.AllayMod;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The player switched their game's language: Fabric has no event for it, Bukkit's `PlayerLocaleChangeEvent` is this. */
@Mixin(ServerPlayer.class)
abstract class LocaleMixin {
  @Inject(method = "updateOptions", at = @At("HEAD"))
  private void allay$locale(ClientInformation information, CallbackInfo callback) {
    ServerPlayer self = (ServerPlayer) (Object) this;
    String before = self.clientInformation().language();
    if (!before.equals(information.language())) AllayMod.localeChanged(self, information.language());
  }
}
