package chat.allay.modcore.mixin;

import chat.allay.modcore.Hooks;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A player picked up some of an item from the ground: Fabric has no event for it, so both mods catch it here; Bukkit's
 * `EntityPickupItemEvent` is this. The game empties the stack it moved, so this keeps a copy from before.
 */
@Mixin(ItemEntity.class)
abstract class PickupMixin {
  @WrapOperation(
    method = "playerTouch",
    at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Inventory;add(Lnet/minecraft/world/item/ItemStack;)Z")
  )
  private boolean allay$pickedUp(Inventory inventory, ItemStack stack, Operation<Boolean> add) {
    ItemStack before = stack.copy();
    boolean added = add.call(inventory, stack);
    int count = before.getCount() - stack.getCount();
    if (inventory.player instanceof ServerPlayer player && count > 0) {
      Hooks.INSTANCE.pickedUp(player, (ItemEntity) (Object) this, before, count);
    }
    return added;
  }
}
