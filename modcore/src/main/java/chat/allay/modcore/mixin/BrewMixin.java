package chat.allay.modcore.mixin;

import chat.allay.modcore.automation.Jobs;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A brewing stand finished a step: the bottles changed and the ingredient was used. Fabric has no event for it,
 * so both mods catch it here; Bukkit's `BrewEvent` is this, and automation's brewing jobs put in the next reagent from
 * it.
 */
@Mixin(BrewingStandBlockEntity.class)
abstract class BrewMixin {
  @Inject(method = "doBrew", at = @At("TAIL"))
  private static void allay$brewed(ServerLevel level, BlockPos pos, BrewingStandBlockEntity stand, CallbackInfo callback) {
    Jobs.INSTANCE.brewed(level, pos);
  }
}
