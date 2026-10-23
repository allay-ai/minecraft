package chat.allay.modcore.mixin;

import chat.allay.modcore.automation.Jobs;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A furnace, blast furnace, or smoker finished an item: its result is in the output slot and the input shrank. Neither
 * loader has an event for it; Bukkit's `FurnaceSmeltEvent` is this, and automation's furnace jobs refill from it.
 */
@Mixin(AbstractFurnaceBlockEntity.class)
abstract class SmeltMixin {
  @Inject(
    method = "serverTick",
    at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity;setRecipeUsed(Lnet/minecraft/world/item/crafting/RecipeHolder;)V")
  )
  private static void allay$smelted(ServerLevel level, BlockPos pos, BlockState state, AbstractFurnaceBlockEntity furnace, CallbackInfo callback) {
    Jobs.INSTANCE.smelted(level, pos);
  }
}
