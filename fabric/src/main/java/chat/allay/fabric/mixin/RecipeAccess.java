package chat.allay.fabric.mixin;

import com.google.common.collect.Multimap;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeMap;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SingleItemRecipe;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * What a recipe makes and takes, private in the game: the recipe window, the item tools, and automation's crafting read
 * them as Bukkit's `Recipe.getResult` and `getChoiceList`. And the server's recipes by type, in the order Bukkit's
 * `recipeIterator` and `getRecipesFor` walk them.
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

  @Mixin(RecipeManager.class)
  public interface Manager {
    @Accessor("recipes") RecipeMap allay$recipes();
  }

  @Mixin(RecipeMap.class)
  public interface Map {
    @Accessor("byType") Multimap<RecipeType<?>, RecipeHolder<?>> allay$byType();
  }

  @Mixin(SmithingTransformRecipe.class)
  public interface Smithing {
    @Accessor("result") ItemStackTemplate allay$result();
  }
}
