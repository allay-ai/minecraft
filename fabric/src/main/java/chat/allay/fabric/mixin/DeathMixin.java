package chat.allay.fabric.mixin;

import chat.allay.fabric.Hooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A player dies, with their inventory still on them: Fabric's death events come before a totem decides or after the
 * drops are gone. Bukkit's `PlayerDeathEvent` is this.
 */
@Mixin(ServerPlayer.class)
abstract class DeathMixin {
  @Inject(method = "die", at = @At("HEAD"))
  private void allay$dying(DamageSource source, CallbackInfo callback) {
    Hooks.INSTANCE.dying((ServerPlayer) (Object) this, source);
  }
}
