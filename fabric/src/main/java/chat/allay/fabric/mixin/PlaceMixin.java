package chat.allay.fabric.mixin;

import chat.allay.fabric.Hooks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** A player placed a block: Fabric has no event for it, Bukkit's `BlockPlaceEvent` is this. */
@Mixin(BlockItem.class)
abstract class PlaceMixin {
  @Inject(method = "place(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/InteractionResult;", at = @At("RETURN"))
  private void allay$placed(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> result) {
    if (result.getReturnValue().consumesAction() && context.getPlayer() instanceof ServerPlayer player && context.getLevel() instanceof ServerLevel level) {
      Hooks.INSTANCE.placed(player, level, context.getClickedPos());
    }
  }
}
