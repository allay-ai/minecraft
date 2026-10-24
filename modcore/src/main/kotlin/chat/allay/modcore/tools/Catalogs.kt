package chat.allay.modcore.tools

import chat.allay.core.Expansions
import chat.allay.core.tools.Brewing
import chat.allay.core.tools.Catalog
import chat.allay.core.tools.CatalogCache
import chat.allay.core.tools.GameNames
import chat.allay.modcore.Mod

import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.contents.TranslatableContents
import net.minecraft.server.MinecraftServer
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.crafting.AbstractCookingRecipe
import net.minecraft.world.item.crafting.BrewingRecipe
import net.minecraft.world.item.crafting.Recipe
import net.minecraft.world.item.crafting.ShapedRecipe
import net.minecraft.world.item.crafting.ShapelessRecipe
import net.minecraft.world.item.crafting.SmithingTransformRecipe
import net.minecraft.world.item.crafting.StonecutterRecipe

/**
 * The [Catalog] in the mods, from the server as it runs: every item and potion in the registries (mods' included), the
 * items data packs build on the game's own (a model of their own), what expansions describe, and how each is made.
 * Built on the server thread and kept while the recipes (a `/reload` makes new ones), the expansions, and the names stay
 * the same.
 */
object Catalogs {
  private val FORMS = listOf("potion", "splash_potion", "lingering_potion", "tipped_arrow")

  private val cache = CatalogCache()

  fun build(context: ToolContext): Catalog = build(context.server, context.features.expansions, context.features.names())

  fun build(server: MinecraftServer, expansions: Expansions?, names: GameNames?): Catalog =
    cache.get(listOf(Recipes.generation(server), expansions, expansions?.revision, names, names?.revision)) { make(server, expansions, names) }

  /** Lets go of the catalog and the server it was built from. */
  fun clear() = cache.clear()

  private fun make(server: MinecraftServer, expansions: Expansions?, names: GameNames?): Catalog {
    val mods = Mod.loader.mods().associate { it.id to it.name }
    fun source(namespace: String) = when (namespace) {
      Catalog.MINECRAFT -> Catalog.MINECRAFT
      else -> mods[namespace] ?: "data pack $namespace"
    }
    val made = HashMap<String, MutableSet<String>>()
    val custom = LinkedHashMap<String, Catalog.Entry>()
    Recipes.all(server).forEach { holder ->
      val recipe = holder.value()
      val result = (if (recipe is BrewingRecipe) recipe.output.create() else Recipes.result(recipe))?.takeUnless { it.isEmpty } ?: return@forEach
      val kind = kind(recipe) ?: return@forEach
      val id = id(result)
      made.getOrPut(id) { mutableSetOf() } += kind
      if (id != Recipes.id(result.item) && id !in custom) custom[id] = custom(id, result, source(holder.id().identifier().namespace))
    }
    val brewed = brews(server, null).map { it.to.potion }.toSet()
    val items = BuiltInRegistries.ITEM.filter { it != Items.AIR }.map { item ->
      val key = BuiltInRegistries.ITEM.getKey(item)
      val id = Recipes.id(item)
      Catalog.Entry(id, Catalog.Kind.ITEM, listOf(item.descriptionId), source = source(key.namespace), made = made[id].orEmpty())
    }
    val potions = BuiltInRegistries.POTION.entrySet().map { (key, potion) ->
      val id = Worlds.key(key.identifier())
      Catalog.Entry(id, Catalog.Kind.POTION, FORMS.map { "item.minecraft.$it.effect.${potion.name()}" }, source = source(key.identifier().namespace), made = if (id in brewed) setOf("brewing") else emptySet())
    }
    val described = expansions?.items().orEmpty().associate { it.second.id() to it.second.recipes().orEmpty() }
    val extra = expansions?.entries().orEmpty().map { entry ->
      val recipes = described[entry.id].orEmpty()
      entry.copy(made = recipes.map { if (it.type() == chat.allay.expansion.Recipe.Type.BREWING) "brewing" else kindOf(it.type()) }.toSet() + made[entry.id].orEmpty())
    }
    return Catalog(Catalog.merge(items + potions + custom.values, extra), names)
  }

  private fun kind(recipe: Recipe<*>): String? = when (recipe) {
    is ShapedRecipe, is ShapelessRecipe -> "crafting"
    is AbstractCookingRecipe -> "smelting"
    is StonecutterRecipe -> "stonecutting"
    is SmithingTransformRecipe -> "smithing"
    is BrewingRecipe -> "brewing"
    else -> null
  }

  private fun kindOf(type: chat.allay.expansion.Recipe.Type) = when (type) {
    chat.allay.expansion.Recipe.Type.CRAFTING_SHAPED, chat.allay.expansion.Recipe.Type.CRAFTING_SHAPELESS -> "crafting"
    chat.allay.expansion.Recipe.Type.STONECUTTING -> "stonecutting"
    chat.allay.expansion.Recipe.Type.BREWING -> "brewing"
    else -> "smelting"
  }

  /**
   * What tools call an item: its registry id, or, for one a data pack or a plugin built on the game's own with a model
   * of its own, that model's id (`mypack:ruby`), as Bukkit's catalog names it.
   */
  fun id(stack: ItemStack): String {
    val model = stack.get(DataComponents.ITEM_MODEL) ?: return Recipes.id(stack.item)
    val default = stack.item.components().get(DataComponents.ITEM_MODEL)
    return if (model == default) Recipes.id(stack.item) else Worlds.key(model)
  }

  private fun custom(id: String, stack: ItemStack, source: String): Catalog.Entry {
    val name = stack.get(DataComponents.CUSTOM_NAME) ?: stack.get(DataComponents.ITEM_NAME)
    val key = (name?.contents as? TranslatableContents)?.key
    val names = if (name != null && key == null) mapOf(GameNames.ENGLISH to name.string) else emptyMap()
    return Catalog.Entry(id, Catalog.Kind.CUSTOM, listOfNotNull(key), names, source, base = Recipes.id(stack.item))
  }

  /**
   * Every brew the stand knows on this server, from its recipes: the game's, data packs', and mods' alike, with
   * [expansions]' brews behind them.
   */
  fun brews(server: MinecraftServer, expansions: Expansions?): List<Brewing.Brew> {
    val brews = Recipes.all(server).mapNotNull { it.value() as? BrewingRecipe }.flatMap { recipe ->
      val output = recipe.output.create()
      val to = bottle(output) ?: return@flatMap emptyList()
      val reagents = recipe.reagent.ingredient().items().map { Recipes.id(it.value()) }.toList()
      val input = recipe.input
      val items = input.ingredient().items().map { it.value() }.toList()
      val potions = input.potions().flatMap { it.potions() }.map { set -> set.map { Worlds.key(it.unwrapKey().get().identifier()) } }.orElse(null)
      val froms = items.flatMap { item -> potions?.map { Brewing.Bottle(Recipes.id(item), it) } ?: listOf(plain(item)) }
      froms.flatMap { from -> reagents.map { Brewing.Brew(from, it, to) } }
    }
    val extra = expansions?.items().orEmpty().flatMap { (_, item) -> item.recipes().mapNotNull { Catalog.brew(item.id(), it) } }
    return brews + extra
  }

  /** A bottle as [Brewing] names it: its form and potion, or a custom bottle by its id. */
  private fun bottle(stack: ItemStack): Brewing.Bottle? {
    val id = id(stack)
    if (id != Recipes.id(stack.item)) return Brewing.Bottle(id, id)
    val potion = stack.get(DataComponents.POTION_CONTENTS)?.potion()?.orElse(null)?.unwrapKey()?.orElse(null)
    return if (potion != null) Brewing.Bottle(Recipes.id(stack.item), Worlds.key(potion.identifier())) else Brewing.Bottle(id, id)
  }

  private fun plain(item: Item): Brewing.Bottle = Recipes.id(item).let { Brewing.Bottle(it, it) }

  /**
   * The language files the game and every mod carry for [locale], read off the class path: a dedicated server has the
   * game's English and each mod's own languages, never the game's others.
   */
  fun names(locale: String): Map<String, String> {
    val loader = MinecraftServer::class.java.classLoader
    val namespaces = (BuiltInRegistries.ITEM.keySet().map { it.namespace } + BuiltInRegistries.POTION.keySet().map { it.namespace } + Catalog.MINECRAFT).toSet()
    val table = HashMap<String, String>()
    for (namespace in namespaces) {
      runCatching {
        loader.getResources("assets/$namespace/lang/$locale.json").asSequence().forEach { url -> url.openStream().use { table += GameNames.parse(it.readBytes().decodeToString()) } }
      }
    }
    return table
  }

  /** The item a name of the catalog means, for the tools that want the game's item: a plugin's item is its base. */
  fun item(catalog: Catalog, entry: Catalog.Entry): Item? = when (entry.kind) {
    Catalog.Kind.ITEM -> InteractTools.known(entry.id)
    Catalog.Kind.CUSTOM -> entry.base?.let(InteractTools::known)
    Catalog.Kind.POTION -> Items.POTION
  }
}
