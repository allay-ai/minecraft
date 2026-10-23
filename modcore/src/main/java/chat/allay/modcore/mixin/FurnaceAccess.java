package chat.allay.modcore.mixin;

import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * What a furnace keeps to itself: how long its fuel still burns, as Bukkit's `Furnace.getBurnTime`, and the recipes it
 * cooked since its output was last taken, whose experience automation pays the player and empties, as Paper's
 * `setRecipesUsed` lets it.
 */
@Mixin(AbstractFurnaceBlockEntity.class)
public interface FurnaceAccess {
  @Accessor("litTimeRemaining") int allay$litTime();
  @Accessor("recipesUsed") Reference2IntOpenHashMap<ResourceKey<Recipe<?>>> allay$recipesUsed();
}
