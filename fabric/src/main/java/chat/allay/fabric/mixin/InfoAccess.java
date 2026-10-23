package chat.allay.fabric.mixin;

import net.minecraft.world.level.BaseSpawner;
import net.minecraft.world.level.SpawnData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * What the info tools read that the game keeps private, as Bukkit reads it: the mob a spawner makes. A brewing stand's
 * fuel and time come through [BrewingAccess].
 */
public final class InfoAccess {
  private InfoAccess() {}

  @Mixin(BaseSpawner.class)
  public interface Spawner {
    @Accessor("nextSpawnData") SpawnData allay$nextSpawnData();
  }
}
