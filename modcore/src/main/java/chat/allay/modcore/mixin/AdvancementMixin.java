package chat.allay.modcore.mixin;

import chat.allay.modcore.Hooks;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A criterion of an advancement was met: Bukkit's `PlayerAdvancementDoneEvent` fires on the last one, goals read it then. */
@Mixin(PlayerAdvancements.class)
abstract class AdvancementMixin {
  @Shadow private ServerPlayer player;

  @Inject(method = "award", at = @At("RETURN"))
  private void allay$awarded(AdvancementHolder advancement, String criterion, CallbackInfoReturnable<Boolean> result) {
    if (result.getReturnValueZ()) Hooks.INSTANCE.advanced(player, advancement);
  }
}
