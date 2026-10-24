package chat.allay.spigot.paper

import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datapack.DatapackSource
import net.kyori.adventure.text.TranslatableComponent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.inventory.BrewingRecipe
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.RecipeChoice

/**
 * What Paper's API tells the catalog beyond Spigot's: a brew's bottle and reagent (Spigot's `BrewingRecipe` shows only
 * what comes out), an item name's translation key, and the data packs. Loaded only on Paper.
 */
object PaperCatalog {
  /** Calls [changed] whenever Paper reloads the data packs, recipes and all: `/minecraft:reload`, a pack switched on. */
  fun reloads(changed: () -> Unit): org.bukkit.event.Listener = Reloads(changed)

  class Reloads(private val changed: () -> Unit) : org.bukkit.event.Listener {
    @org.bukkit.event.EventHandler
    fun reloaded(event: io.papermc.paper.event.server.ServerResourcesReloadedEvent) = changed()
  }

  /** A brew as Paper describes it: the bottle below, the reagent on top, and what comes out. */
  class Brew(val key: String, val input: RecipeChoice, val reagent: RecipeChoice, val result: ItemStack)

  /** Every brewing recipe on this server: the game's, data packs', and plugins' potion mixes alike. */
  fun brews(): List<Brew> = Bukkit.recipeIterator().asSequence().filterIsInstance<BrewingRecipe>()
    .map { Brew(it.key.toString(), it.input, it.ingredient, it.result) }.toList()

  /** The translation key of an item's own name, when it has one: a plugin's `item.redbull.original`. */
  fun nameKey(item: ItemStack): String? {
    val name = item.getData(DataComponentTypes.CUSTOM_NAME) ?: item.getData(DataComponentTypes.ITEM_NAME) ?: return null
    return (name as? TranslatableComponent)?.key()
  }

  /** An item's own name in plain words, when it isn't a translation key. */
  fun name(item: ItemStack): String? {
    val name = item.getData(DataComponentTypes.CUSTOM_NAME) ?: item.getData(DataComponentTypes.ITEM_NAME) ?: return null
    if (name is TranslatableComponent) return null
    return PlainTextComponentSerializer.plainText().serialize(name).ifBlank { null }
  }

  /**
   * The data packs the worlds run, a server owner's or the world's own (not the game's or a plugin's), with their
   * descriptions, and the game's experiments turned on.
   */
  fun datapacks(): Pair<List<Pair<String, String?>>, List<String>> {
    val enabled = Bukkit.getDatapackManager().enabledPacks
    val plain = PlainTextComponentSerializer.plainText()
    val packs = enabled.filter { it.source == DatapackSource.WORLD || it.source == DatapackSource.SERVER }
      .map { it.name.removePrefix("file/") to plain.serialize(it.description).ifBlank { null } }
    return packs to enabled.filter { it.source == DatapackSource.FEATURE }.map { it.name }
  }
}
