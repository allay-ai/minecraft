package chat.allay.fabric.mixin;

import net.minecraft.world.level.BaseSpawner;
import net.minecraft.world.level.SpawnData;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * What the info tools read that the game keeps private, as Bukkit reads it: a brewing stand's fuel and time, and the
 * mob a spawner makes.
 */
public final class InfoAccess {
  private InfoAccess() {}

  @Mixin(BrewingStandBlockEntity.class)
  public interface BrewingStand {
    @Accessor("fuel") int allay$fuel();
    @Accessor("brewTime") int allay$brewTime();
  }

  @Mixin(BaseSpawner.class)
  public interface Spawner {
    @Accessor("nextSpawnData") SpawnData allay$nextSpawnData();
  }
}
