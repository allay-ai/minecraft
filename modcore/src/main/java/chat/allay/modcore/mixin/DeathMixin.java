package chat.allay.modcore.mixin;

import chat.allay.modcore.Hooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A player dies, with their inventory still on them: Fabric's death events come before a totem decides or after the
 * drops are gone. Bukkit's `PlayerDeathEvent` is this. Around the drops, the items the game spawns are counted
 * ([DropMixin]), as the event's drops list them: the inventory and whatever the loot table adds.
 */
@Mixin(ServerPlayer.class)
abstract class DeathMixin {
  private static final String DROP = "Lnet/minecraft/server/level/ServerPlayer;dropAllDeathLoot(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;)V";

  @Inject(method = "die", at = @At("HEAD"))
  private void allay$dying(DamageSource source, CallbackInfo callback) {
    Hooks.INSTANCE.dying((ServerPlayer) (Object) this, source);
  }

  @Inject(method = "die", at = @At(value = "INVOKE", target = DROP))
  private void allay$dropping(DamageSource source, CallbackInfo callback) {
    Hooks.INSTANCE.dropping();
  }

  @Inject(method = "die", at = @At(value = "INVOKE", target = DROP, shift = At.Shift.AFTER))
  private void allay$dropped(DamageSource source, CallbackInfo callback) {
    Hooks.INSTANCE.dropped((ServerPlayer) (Object) this);
  }
}
