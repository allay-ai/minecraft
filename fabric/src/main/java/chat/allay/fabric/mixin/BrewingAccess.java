package chat.allay.fabric.mixin;

import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** What a brewing stand keeps to itself, as Bukkit's `BrewingStand.getBrewingTime` and `getFuelLevel` read it. */
@Mixin(BrewingStandBlockEntity.class)
public interface BrewingAccess {
  @Accessor("brewTime") int allay$brewTime();
  @Accessor("fuel") int allay$fuel();
}
