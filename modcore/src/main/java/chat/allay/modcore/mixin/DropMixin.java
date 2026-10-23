package chat.allay.modcore.mixin;

import chat.allay.modcore.Hooks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Every entity the game adds to a world, so [DeathMixin] can count the items a dying player drops. */
@Mixin(ServerLevel.class)
abstract class DropMixin {
  @Inject(method = "addFreshEntity", at = @At("HEAD"))
  private void allay$adding(Entity entity, CallbackInfoReturnable<Boolean> callback) {
    Hooks.INSTANCE.spawned(entity);
  }
}
