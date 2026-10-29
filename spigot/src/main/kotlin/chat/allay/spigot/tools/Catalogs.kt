package chat.allay.spigot.tools

import chat.allay.core.Expansions
import chat.allay.core.tools.Brewing
import chat.allay.core.tools.Catalog
import chat.allay.core.tools.CatalogCache
import chat.allay.core.tools.GameNames
import chat.allay.spigot.paper.PaperCatalog

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.event.server.ServerLoadEvent
import org.bukkit.inventory.BrewingRecipe
import org.bukkit.inventory.CookingRecipe
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.Recipe
import org.bukkit.inventory.RecipeChoice
import org.bukkit.inventory.ShapedRecipe
import org.bukkit.inventory.ShapelessRecipe
import org.bukkit.inventory.SmithingTransformRecipe
import org.bukkit.inventory.StonecuttingRecipe
import org.bukkit.inventory.TransmuteRecipe
import org.bukkit.inventory.meta.PotionMeta

/**
 * The [Catalog] on Bukkit, from the server as it runs: every item and potion in the registries, the items plugins and
 * data packs build on the game's own (a model of their own, like Red Bull's cans), what expansions describe, and how
 * each is made. Built on the server thread and kept until something it's made of changes: a plugin comes or goes, the
 * server loads or reloads its data packs (Paper), an expansion or the names change. A plugin may add a recipe
 * whenever it likes, and Spigot tells nobody, so the catalog also builds again after [MAX_AGE_MILLIS].
 */
object Catalogs {
  private val FORMS = listOf("potion", "splash_potion", "lingering_potion", "tipped_arrow")

  /** Paper's API shows a brew's ingredients and an item name's key; Spigot's doesn't. */
  val paper = runCatching { Class.forName("io.papermc.paper.datacomponent.DataComponentTypes") }.isSuccess

  private const val MAX_AGE_MILLIS = 5 * 60_000L
  private val cache = CatalogCache(MAX_AGE_MILLIS)

  fun build(context: ToolContext): Catalog = build(context.features.expansions, context.features.names())

  fun build(expansions: Expansions?, names: GameNames?): Catalog =
    cache.get(listOf(expansions, expansions?.revision, names, names?.revision)) { make(expansions, names) }

  /** Builds the catalog again on its next use. */
  fun clear() = cache.clear()

  /** Builds the catalog and the commands ([CommandTools]) again on their next use: Paper reloaded, which takes both. */
  fun reloaded() {
    clear()
    CommandTools.clear()
  }

  /**
   * What tells the catalog and the commands the server changed, beside Paper's data pack reloads
   * ([PaperCatalog.reloads]): a plugin brings or takes its commands along with its recipes.
   */
  object Changes : Listener {
    @EventHandler fun enabled(event: PluginEnableEvent) = reloaded()
    @EventHandler fun disabled(event: PluginDisableEvent) = reloaded()
    @EventHandler fun loaded(event: ServerLoadEvent) = reloaded()
  }

  private fun make(expansions: Expansions?, names: GameNames?): Catalog {
    val made = HashMap<String, MutableSet<String>>()
    val custom = LinkedHashMap<String, Catalog.Entry>()
    val keys = mutableSetOf<String>()
    Bukkit.recipeIterator().forEach { recipe ->
      (recipe as? org.bukkit.Keyed)?.key?.takeIf { it.namespace == NamespacedKey.MINECRAFT }?.let { keys += it.key }
      val result = recipe.result.takeUnless { it.type.isAir } ?: return@forEach
      val kind = kind(recipe) ?: return@forEach
      val id = id(result)
      made.getOrPut(id) { mutableSetOf() } += kind
      val namespace = (recipe as? org.bukkit.Keyed)?.key?.namespace ?: Catalog.MINECRAFT
      if (id != result.type.key.key && id !in custom) custom[id] = custom(id, result, source(namespace))
    }
    // Fireworks and the rest of the special recipes have no result to read: crafted all the same.
    chat.allay.core.tools.SpecialRecipes.made(keys).forEach { made.getOrPut(it) { mutableSetOf() } += "crafting" }
    val brewed = BrewingRecipes.all(Bukkit.getServer()).map { it.to.potion }.toSet()
    val items = Material.entries.filter { !it.name.startsWith("LEGACY_") && it.isItem && !it.isAir }.map { material ->
      val id = material.key.key
      Catalog.Entry(id, Catalog.Kind.ITEM, listOfNotNull(runCatching { material.itemTranslationKey }.getOrNull()), source = source(material.key.namespace), made = made[id].orEmpty())
    }
    val potions = Registry.POTION.map { type ->
      val id = type.key.key
      val base = id.removePrefix("long_").removePrefix("strong_")
      Catalog.Entry(id, Catalog.Kind.POTION, FORMS.map { "item.minecraft.$it.effect.$base" }, source = source(type.key.namespace), made = if (id in brewed) setOf("brewing") else emptySet())
    }
    val described = expansions?.items().orEmpty().associate { it.second.id() to it.second.recipes().orEmpty() }
    val extra = expansions?.entries().orEmpty().map { entry ->
      val recipes = described[entry.id].orEmpty()
      entry.copy(made = recipes.map { if (it.type() == chat.allay.expansion.Recipe.Type.BREWING) "brewing" else kindOf(it.type()) }.toSet() + made[entry.id].orEmpty())
    }
    return Catalog(Catalog.merge(items + potions + custom.values, extra), names)
  }

  /** Where a namespace comes from: the game, a plugin (its namespace is its name in lower case), or a data pack. */
  fun source(namespace: String): String = when (namespace) {
    NamespacedKey.MINECRAFT -> Catalog.MINECRAFT
    else -> Bukkit.getPluginManager().plugins.firstOrNull { it.name.lowercase() == namespace }?.name ?: "data pack $namespace"
  }

  private fun kind(recipe: Recipe): String? = when (recipe) {
    is ShapedRecipe, is ShapelessRecipe, is TransmuteRecipe -> "crafting"
    is CookingRecipe<*> -> "smelting"
    is StonecuttingRecipe -> "stonecutting"
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
   * What tools call an item: the game's id, or, for one a plugin or data pack built on the game's own with a model of
   * its own, that model's id (`redbull:original`).
   */
  fun id(stack: ItemStack): String {
    val meta = stack.itemMeta ?: return stack.type.key.key
    if (!meta.hasItemModel()) return stack.type.key.key
    val model = meta.itemModel ?: return stack.type.key.key
    if (model == stack.type.key) return stack.type.key.key
    return if (model.namespace == NamespacedKey.MINECRAFT) model.key else model.toString()
  }

  private fun custom(id: String, stack: ItemStack, source: String): Catalog.Entry {
    val key = if (paper) PaperCatalog.nameKey(stack) else null
    val plain = if (paper) PaperCatalog.name(stack) else stack.itemMeta?.takeIf { it.hasItemName() }?.itemName
    val names = if (key == null && plain != null) mapOf(GameNames.ENGLISH to Catalog.plain(plain)) else emptyMap()
    return Catalog.Entry(id, Catalog.Kind.CUSTOM, listOfNotNull(key), names, source, base = stack.type.key.key)
  }

  /**
   * The brews beyond the game's own files: the world's data packs' recipe files, read as the game's are, and on Paper
   * plugins' potion mixes whose ingredients are items. Paper shows a data pack's brew without the potion it takes, and a
   * mix that matches by a plugin's own code says nothing about what goes in; its expansion has to.
   */
  fun extraBrews(): List<Brewing.Brew> = packBrews() + mixes()

  private fun mixes(): List<Brewing.Brew> {
    if (!paper) return emptyList()
    return runCatching { PaperCatalog.brews() }.getOrDefault(emptyList()).filter { !it.key.startsWith("minecraft:") && it.input is RecipeChoice.ExactChoice }.flatMap { brew ->
      val to = bottle(brew.result)
      val froms = choices(brew.input).map(::bottle).filter { it != Brewing.Bottle("potion", "potion") }
      val reagents = choices(brew.reagent).map(::id)
      froms.flatMap { from -> reagents.map { Brewing.Brew(from, it, to) } }
    }
  }

  @Volatile private var packs: Pair<Set<String>, List<Brewing.Brew>>? = null

  /** The brewing recipes in the data packs the worlds run, folders and zips alike; read again when the packs change. */
  private fun packBrews(): List<Brewing.Brew> {
    val names = WorldTools.datapacks(emptySet()).first.map { it.first }.toSet()
    packs?.takeIf { it.first == names }?.let { return it.second }
    // Since 26.1 a world's dimensions live in world/dimensions/minecraft/<dimension>; the packs stay at the world's root.
    val folders = Bukkit.getWorlds().mapNotNull { world ->
      generateSequence(world.worldFolder.absoluteFile.normalize()) { it.parentFile }.take(5).map { it.resolve("datapacks") }.firstOrNull { it.isDirectory }
    }.distinct()
    val brews = folders.flatMap { folder ->
      names.mapNotNull { name -> folder.resolve(name).takeIf { it.exists() } }.flatMap(::read)
    }
    packs = names to brews
    return brews
  }

  private val RECIPE = Regex("^data/[^/]+/recipe/.+\\.json$")

  private fun read(pack: java.io.File): List<Brewing.Brew> = runCatching {
    if (pack.isDirectory) {
      pack.walkTopDown().filter { it.isFile && RECIPE.matches(it.relativeTo(pack).invariantSeparatorsPath) }.flatMap { Brewing.parse(it.readText()) }.toList()
    } else java.util.zip.ZipFile(pack).use { zip ->
      zip.entries().asSequence().filter { RECIPE.matches(it.name) }.flatMap { entry -> zip.getInputStream(entry).use { Brewing.parse(it.readBytes().decodeToString()) } }.toList()
    }
  }.getOrDefault(emptyList())

  private fun choices(choice: RecipeChoice): List<ItemStack> = when (choice) {
    is RecipeChoice.ExactChoice -> choice.choices
    is RecipeChoice.MaterialChoice -> choice.choices.map(::ItemStack)
    else -> emptyList()
  }

  /** A bottle as [Brewing] names it: its form and potion, or a custom bottle by its id. */
  fun bottle(stack: ItemStack): Brewing.Bottle {
    val id = id(stack)
    if (id != stack.type.key.key) return Brewing.Bottle(id, id)
    val potion = (stack.itemMeta as? PotionMeta)?.basePotionType?.key?.key ?: return Brewing.Bottle(id, id)
    return Brewing.Bottle(id, potion)
  }

  /**
   * The language files for [locale] the server has: the game's English in its jar, and each plugin's own under
   * `assets/<its name>/lang/`.
   */
  fun names(locale: String): Map<String, String> {
    val table = HashMap<String, String>()
    fun read(loader: ClassLoader, path: String) = runCatching {
      loader.getResources(path).asSequence().forEach { url -> url.openStream().use { table += GameNames.parse(it.readBytes().decodeToString()) } }
    }
    read(Bukkit.getServer().javaClass.classLoader, "assets/minecraft/lang/$locale.json")
    Bukkit.getPluginManager().plugins.forEach { plugin -> read(plugin.javaClass.classLoader, "assets/${plugin.name.lowercase()}/lang/$locale.json") }
    return table
  }
}
