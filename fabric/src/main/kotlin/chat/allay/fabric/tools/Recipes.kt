package chat.allay.fabric.tools

import chat.allay.fabric.mixin.RecipeAccess

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.crafting.AbstractCookingRecipe
import net.minecraft.world.item.crafting.BlastingRecipe
import net.minecraft.world.item.crafting.CampfireCookingRecipe
import net.minecraft.world.item.crafting.Ingredient
import net.minecraft.world.item.crafting.Recipe
import net.minecraft.world.item.crafting.RecipeHolder
import net.minecraft.world.item.crafting.ShapedRecipe
import net.minecraft.world.item.crafting.ShapelessRecipe
import net.minecraft.world.item.crafting.SingleItemRecipe
import net.minecraft.world.item.crafting.SmithingTransformRecipe
import net.minecraft.world.item.crafting.SmokingRecipe
import net.minecraft.world.item.crafting.StonecutterRecipe

/**
 * This server's recipes as Bukkit's `getRecipesFor` and its recipe classes give them: what each makes, and its
 * ingredients in the grid. Data packs count, as on Bukkit. Read on the server thread.
 */
object Recipes {
  /**
   * Every recipe in the order Bukkit's `recipeIterator` walks them: grouped by type, the types in the order the game
   * first met them. Only the first few recipes for an item reach the model, so the order matters.
   */
  fun all(server: MinecraftServer): Collection<RecipeHolder<*>> =
    ((server.recipeManager as RecipeAccess.Manager).`allay$recipes`() as RecipeAccess.Map).`allay$byType`().values()

  /** What [recipe] makes, or null for a special one with no fixed result (map cloning, dyeing armor). */
  fun result(recipe: Recipe<*>): ItemStack? = when (recipe) {
    is ShapedRecipe -> (recipe as RecipeAccess.Shaped).`allay$result`().create()
    is ShapelessRecipe -> (recipe as RecipeAccess.Shapeless).`allay$result`().create()
    is SingleItemRecipe -> (recipe as RecipeAccess.Single).`allay$result`().create()
    is SmithingTransformRecipe -> (recipe as RecipeAccess.Smithing).`allay$result`().create()
    else -> null
  }

  /** The recipes that make [item] and that a grid can draw: crafting, cooking, the stonecutter, and smithing. */
  fun forItem(server: MinecraftServer, item: Item): List<RecipeHolder<*>> = if (item == Items.AIR) emptyList() else
    all(server).filter { holder ->
      val recipe = holder.value()
      (recipe is ShapedRecipe || recipe is ShapelessRecipe || recipe is AbstractCookingRecipe || recipe is StonecutterRecipe || recipe is SmithingTransformRecipe) &&
        result(recipe)?.item == item
    }

  /** A shapeless recipe's ingredients, in order. */
  fun ingredients(recipe: ShapelessRecipe): List<Ingredient> = (recipe as RecipeAccess.Shapeless).`allay$ingredients`()

  /** A shaped recipe's grid, row by row, `width` wide; null for an empty cell. */
  fun grid(recipe: ShapedRecipe): List<Ingredient?> = recipe.ingredients.map { it.orElse(null) }

  /** The items an ingredient takes, as the game lists them; [Items.BARRIER] for none. */
  fun choices(ingredient: Ingredient): List<ItemStack> = ingredient.items().map { ItemStack(it) }.toList().ifEmpty { listOf(ItemStack(Items.BARRIER)) }

  /** The station a recipe needs, as the window's icon. */
  fun station(recipe: Recipe<*>): Item = when (recipe) {
    is BlastingRecipe -> Items.BLAST_FURNACE
    is SmokingRecipe -> Items.SMOKER
    is CampfireCookingRecipe -> Items.CAMPFIRE
    is AbstractCookingRecipe -> Items.FURNACE
    is StonecutterRecipe -> Items.STONECUTTER
    is SmithingTransformRecipe -> Items.SMITHING_TABLE
    else -> Items.CRAFTING_TABLE
  }

  /** An item's id as tools name it. */
  fun id(item: Item): String = Worlds.key(BuiltInRegistries.ITEM.getKey(item))
}
