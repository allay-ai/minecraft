package chat.allay.fabric.mixin;

import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SingleItemRecipe;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * What a recipe makes and takes, private in the game: the recipe window, the item tools, and automation's crafting read
 * them as Bukkit's `Recipe.getResult` and `getChoiceList`.
 */
public final class RecipeAccess {
  private RecipeAccess() {}

  @Mixin(ShapedRecipe.class)
  public interface Shaped {
    @Accessor("result") ItemStackTemplate allay$result();
  }

  @Mixin(ShapelessRecipe.class)
  public interface Shapeless {
    @Accessor("result") ItemStackTemplate allay$result();
    @Accessor("ingredients") List<Ingredient> allay$ingredients();
  }

  @Mixin(SingleItemRecipe.class)
  public interface Single {
    @Accessor("result") ItemStackTemplate allay$result();
  }

  @Mixin(SmithingTransformRecipe.class)
  public interface Smithing {
    @Accessor("result") ItemStackTemplate allay$result();
  }
}
