package chat.allay.fabric.mixin;

import chat.allay.fabric.Hooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A player placed a block: Fabric has no event for it, Bukkit's `BlockPlaceEvent` is this. The spot is where the block
 * went, from the context the item settled on: scaffolding or a slab may go elsewhere than the clicked block.
 */
@Mixin(BlockItem.class)
abstract class PlaceMixin {
  @WrapOperation(
    method = "place(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/InteractionResult;",
    at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/BlockItem;placeBlock(Lnet/minecraft/world/item/context/BlockPlaceContext;Lnet/minecraft/world/level/block/state/BlockState;)Z")
  )
  private boolean allay$placed(BlockItem item, BlockPlaceContext context, BlockState state, Operation<Boolean> place) {
    boolean placed = place.call(item, context, state);
    if (placed && context.getPlayer() instanceof ServerPlayer player && context.getLevel() instanceof ServerLevel level) {
      Hooks.INSTANCE.placed(player, level, context.getClickedPos());
    }
    return placed;
  }
}
