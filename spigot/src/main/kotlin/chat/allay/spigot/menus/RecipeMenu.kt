package chat.allay.spigot.menus

import chat.allay.core.*
import chat.allay.core.tools.*

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.BlastingRecipe
import org.bukkit.inventory.CampfireRecipe
import org.bukkit.inventory.CookingRecipe
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.Recipe
import org.bukkit.inventory.RecipeChoice
import org.bukkit.inventory.ShapedRecipe
import org.bukkit.inventory.ShapelessRecipe
import org.bukkit.inventory.SmithingTransformRecipe
import org.bukkit.inventory.SmokingRecipe
import org.bukkit.inventory.StonecuttingRecipe
import chat.allay.core.Lang
import chat.allay.spigot.menus.Menu.Companion.lore
import chat.allay.spigot.tools.Catalogs

/**
 * This server's recipes as the crafting screen draws them. Clicking an ingredient opens its recipe in the same
 * window, and “Back” walks the way back. Ingredients that accept several items (any planks) cycle once a second.
 * A plugin's own item (`redbull:ice`) shows its own recipes, not those of the game's item it's built on.
 */
class RecipeMenu(player: Player, private val lang: Lang, first: Target) : Menu(player, 54, lang.text("menus.recipe.title")) {
  private val history = ArrayDeque<Target>()
  private var item = first
  private var recipes = recipesFor(first)
  private var page = 0
  private var tick = 0

  /** Whether an item has a recipe, asked once per item: the lookup walks every recipe on the server. */
  private val craftable = HashMap<Target, Boolean>()

  private fun craftable(target: Target) = craftable.getOrPut(target) { recipesFor(target).isNotEmpty() }

  /** What each slot shows: one or more items, cycled while the window is open. */
  private val slots = HashMap<Int, List<ItemStack>>()

  override fun draw() {
    clear()
    slots.clear()
    val recipe = recipes.getOrNull(page) ?: return
    when (recipe) {
      is ShapedRecipe -> recipe.shape.forEachIndexed { row, line ->
        line.forEachIndexed { column, symbol -> recipe.choiceMap[symbol]?.let { slots[GRID[row * 3 + column]] = items(it) } }
      }
      is ShapelessRecipe -> recipe.choiceList.take(9).forEachIndexed { index, choice -> slots[GRID[index]] = items(choice) }
      is CookingRecipe<*> -> slots[GRID[4]] = items(recipe.inputChoice)
      is StonecuttingRecipe -> slots[GRID[4]] = items(recipe.inputChoice)
      is SmithingTransformRecipe -> listOf(recipe.template, recipe.base, recipe.addition).forEachIndexed { index, choice ->
        if (choice != null) slots[GRID[3 + index]] = items(choice)
      }
    }
    set(STATION, station(recipe))
    val result = recipe.result.clone()
    set(RESULT, result.lore(null, if (result.amount > 1) listOf(lang.text("menus.recipe.makes", "count" to result.amount)) else emptyList()))
    if (history.isNotEmpty()) set(BACK, icon(Material.ARROW, lang.text("menus.back")))
    if (recipes.size > 1) {
      set(PREVIOUS, icon(Material.SPECTRAL_ARROW, lang.text("menus.previous")))
      set(PAGE, icon(Material.PAPER, lang.text("menus.recipe.page", "n" to page + 1, "total" to recipes.size)))
      set(NEXT, icon(Material.SPECTRAL_ARROW, lang.text("menus.next")))
    }
    set(CLOSE, icon(Material.BARRIER, lang.text("menus.close")))
    paint()
  }

  /** Puts the current choice of every ingredient into its slot. */
  private fun paint() {
    for ((slot, options) in slots) {
      val shown = options[tick % options.size].clone()
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
      CLOSE -> player.closeInventory()
      BACK -> history.removeLastOrNull()?.let { show(it, remember = false) }
      PREVIOUS -> if (recipes.size > 1) { page = (page - 1 + recipes.size) % recipes.size; draw() }
      NEXT -> if (recipes.size > 1) { page = (page + 1) % recipes.size; draw() }
      in slots.keys -> {
        val options = slots[slot]!!
        val wanted = Target.of(options[tick % options.size])
        if (!craftable(wanted)) player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f) else show(wanted, remember = true)
      }
    }
  }

  private fun show(target: Target, remember: Boolean) {
    if (remember) history.addLast(item)
    item = target
    recipes = recipesFor(target)
    page = 0
    player.playSound(player.location, Sound.ITEM_BOOK_PAGE_TURN, 0.7f, 1f)
    draw()
  }

  private fun station(recipe: Recipe): ItemStack {
    val material = when (recipe) {
      is BlastingRecipe -> Material.BLAST_FURNACE
      is SmokingRecipe -> Material.SMOKER
      is CampfireRecipe -> Material.CAMPFIRE
      is CookingRecipe<*> -> Material.FURNACE
      is StonecuttingRecipe -> Material.STONECUTTER
      is SmithingTransformRecipe -> Material.SMITHING_TABLE
      else -> Material.CRAFTING_TABLE
    }
    val lore = if (recipe is CookingRecipe<*>) {
      listOf(lang.text("menus.recipe.cooking", "seconds" to trim(recipe.cookingTime / 20.0), "xp" to trim(recipe.experience.toDouble())))
    } else emptyList()
    return icon(material, lore = lore)
  }

  private fun trim(value: Double) = if (value % 1.0 == 0.0) value.toLong().toString() else "%.1f".format(value)

  /** An item as tools name it ([Catalogs.id]: the game's id, or a plugin's own like `redbull:ice`), and the game's item it's built on. */
  data class Target(val id: String, val base: Material) {
    companion object {
      fun of(stack: ItemStack) = Target(Catalogs.id(stack), stack.type)
      fun of(material: Material) = Target(material.key.key, material)
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
     * The recipes this window can draw for [target]; special ones (map cloning, dyeing armor) have no fixed grid. Bukkit
     * finds recipes by the game's item, so a plugin's can and a potion share theirs: only those that make [target] stay.
     */
    fun recipesFor(target: Target): List<Recipe> = if (!target.base.isItem || target.base.isAir) emptyList() else
      Bukkit.getRecipesFor(ItemStack(target.base)).filter {
        (it is ShapedRecipe || it is ShapelessRecipe || it is CookingRecipe<*> || it is StonecuttingRecipe || it is SmithingTransformRecipe) && Catalogs.id(it.result) == target.id
      }.distinctBy { it.javaClass to (it as? org.bukkit.Keyed)?.key }

    private fun items(choice: RecipeChoice): List<ItemStack> = when (choice) {
      is RecipeChoice.MaterialChoice -> choice.choices.filter { it.isItem }.map(::ItemStack)
      is RecipeChoice.ExactChoice -> choice.choices.map(ItemStack::clone)
      else -> listOf(choice.itemStack.clone())
    }.ifEmpty { listOf(ItemStack(Material.BARRIER)) }
  }
}
