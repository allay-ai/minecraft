package chat.allay.modcore.menus

import chat.allay.core.*
import chat.allay.core.tools.*
import chat.allay.modcore.Sounds
import chat.allay.modcore.menus.Menu.Companion.lore
import chat.allay.modcore.tools.Catalogs
import chat.allay.modcore.tools.Recipes

import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.crafting.AbstractCookingRecipe
import net.minecraft.world.item.crafting.RecipeHolder
import net.minecraft.world.item.crafting.ShapedRecipe
import net.minecraft.world.item.crafting.ShapelessRecipe
import net.minecraft.world.item.crafting.SmithingTransformRecipe
import net.minecraft.world.item.crafting.StonecutterRecipe

/**
 * This server's recipes as the crafting screen draws them. Clicking an ingredient opens its recipe in the same
 * window, and “Back” walks the way back. Ingredients that accept several items (any planks) cycle once a second.
 * A data pack's or plugin's own item (`mypack:ruby`) shows its own recipes, not those of the game's item it's built on.
 */
class RecipeMenu(player: ServerPlayer, private val lang: Lang, first: Target) : Menu(player, 54, lang.text("menus.recipe.title")) {
  private val history = ArrayDeque<Target>()
  private var item = first
  private var recipes = recipesFor(player, first)
  private var page = 0
  private var tick = 0

  /** Whether an item has a recipe, asked once per item: the lookup walks every recipe on the server. */
  private val craftable = HashMap<Target, Boolean>()

  private fun craftable(target: Target) = craftable.getOrPut(target) { recipesFor(player, target).isNotEmpty() }

  /** What each slot shows: one or more items, cycled while the window is open. */
  private val slots = HashMap<Int, List<ItemStack>>()

  override fun draw() {
    clear()
    slots.clear()
    val recipe = recipes.getOrNull(page)?.value() ?: return
    when (recipe) {
      is ShapedRecipe -> Recipes.grid(recipe).forEachIndexed { index, ingredient ->
        val row = index / recipe.width
        val column = index % recipe.width
        if (ingredient != null) slots[GRID[row * 3 + column]] = Recipes.choices(ingredient)
      }
      is ShapelessRecipe -> Recipes.ingredients(recipe).take(9).forEachIndexed { index, ingredient -> slots[GRID[index]] = Recipes.choices(ingredient) }
      is AbstractCookingRecipe -> slots[GRID[4]] = Recipes.choices(recipe.input())
      is StonecutterRecipe -> slots[GRID[4]] = Recipes.choices(recipe.input())
      is SmithingTransformRecipe -> listOf(recipe.templateIngredient().orElse(null), recipe.baseIngredient(), recipe.additionIngredient().orElse(null)).forEachIndexed { index, choice ->
        if (choice != null) slots[GRID[3 + index]] = Recipes.choices(choice)
      }
    }
    set(STATION, station(recipe))
    val result = Recipes.result(recipe)!!.copy()
    set(RESULT, result.lore(null, if (result.count > 1) listOf(lang.text("menus.recipe.makes", "count" to result.count)) else emptyList()))
    if (history.isNotEmpty()) set(BACK, icon(Items.ARROW, lang.text("menus.back")))
    if (recipes.size > 1) {
      set(PREVIOUS, icon(Items.SPECTRAL_ARROW, lang.text("menus.previous")))
      set(PAGE, icon(Items.PAPER, lang.text("menus.recipe.page", "n" to page + 1, "total" to recipes.size)))
      set(NEXT, icon(Items.SPECTRAL_ARROW, lang.text("menus.next")))
    }
    set(CLOSE, icon(Items.BARRIER, lang.text("menus.close")))
    paint()
  }

  /** Puts the current choice of every ingredient into its slot. */
  private fun paint() {
    for ((slot, options) in slots) {
      val shown = options[tick % options.size].copy()
      val hint = if (craftable(Target.of(shown))) lang.text("menus.recipe.open") else lang.text("menus.recipe.none")
      set(slot, shown.lore(null, listOfNotNull(hint, lang.text("menus.recipe.options").takeIf { options.size > 1 })))
    }
  }

  override fun tick() {
    if (slots.values.none { it.size > 1 }) return
    tick++
    paint()
  }

  override fun click(slot: Int) {
    when (slot) {
      CLOSE -> close()
      BACK -> history.removeLastOrNull()?.let { show(it, remember = false) }
      PREVIOUS -> if (recipes.size > 1) {
        page = (page - 1 + recipes.size) % recipes.size
        draw()
      }
      NEXT -> if (recipes.size > 1) {
        page = (page + 1) % recipes.size
        draw()
      }
      in slots.keys -> {
        val options = slots[slot]!!
        val wanted = Target.of(options[tick % options.size])
        if (!craftable(wanted)) Sounds.play(player, SoundEvents.NOTE_BLOCK_BASS, 0.6f, 0.8f) else show(wanted, remember = true)
      }
    }
  }

  private fun show(next: Target, remember: Boolean) {
    if (remember) history.addLast(item)
    item = next
    recipes = recipesFor(player, next)
    page = 0
    Sounds.play(player, SoundEvents.BOOK_PAGE_TURN, 0.7f, 1f)
    draw()
  }

  private fun station(recipe: net.minecraft.world.item.crafting.Recipe<*>): ItemStack {
    val lore = if (recipe is AbstractCookingRecipe) {
      listOf(lang.text("menus.recipe.cooking", "seconds" to trim(recipe.cookingTime() / 20.0), "xp" to trim(recipe.experience().toDouble())))
    } else emptyList()
    return icon(Recipes.station(recipe), lore = lore)
  }

  private fun trim(value: Double) = if (value % 1.0 == 0.0) value.toLong().toString() else "%.1f".format(value)

  /** An item as tools name it ([Catalogs.id]: its registry id, or a data pack's own like `mypack:ruby`), and the game's item it's built on. */
  data class Target(val id: String, val base: Item) {
    companion object {
      fun of(stack: ItemStack) = Target(Catalogs.id(stack), stack.item)
      fun of(item: Item) = Target(Recipes.id(item), item)
    }
  }

  companion object {
    private val GRID = intArrayOf(10, 11, 12, 19, 20, 21, 28, 29, 30)
    private const val STATION = 23
    private const val RESULT = 25
    private const val BACK = 45
    private const val PREVIOUS = 48
    private const val PAGE = 49
    private const val NEXT = 50
    private const val CLOSE = 53

    /**
     * The recipes this window can draw for [target]; special ones (map cloning, dyeing armor) have no fixed grid. Of the
     * recipes that make its base item, only those that make [target] itself stay, as on Bukkit.
     */
    fun recipesFor(player: ServerPlayer, target: Target): List<RecipeHolder<*>> =
      Recipes.forItem(player.level().server, target.base).filter { holder -> Recipes.result(holder.value())?.let(Catalogs::id) == target.id }
  }
}
