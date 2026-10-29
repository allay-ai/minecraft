package chat.allay.spigot.tools

import chat.allay.core.*
import chat.allay.core.tools.*

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.inventory.CookingRecipe
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.Recipe
import org.bukkit.inventory.RecipeChoice
import org.bukkit.inventory.ShapedRecipe
import org.bukkit.inventory.ShapelessRecipe
import org.bukkit.inventory.SmithingTransformRecipe
import org.bukkit.inventory.StonecuttingRecipe

/** What this server knows that the model might not: its exact recipes, and the asker's advancements. */
object KnowledgeTools {
  private val POTIONS = setOf("potion", "splash_potion", "lingering_potion")
  private val POTION_ITEMS = setOf(Material.POTION, Material.SPLASH_POTION, Material.LINGERING_POTION)

  private val recipes = object : Tool {
    override val name = "get_recipes"

    override fun run(context: ToolContext, args: JsonObject): ToolOutput {
      val asked = args.asked("items", "item", MAX_ITEMS) ?: throw ToolError("say which item")
      val catalog = Catalogs.build(context)
      val locale = context.nameLocale
      // With plan, each item also comes with what crafting it would take from the player's things, parts on the way.
      // Items in one call share the player's things: a set of armor counts the same ingots once.
      val plan = if (args.bool("plan") == true) (args.int("count") ?: 1).coerceIn(1, chat.allay.spigot.automation.Crafting.MAX_COUNT) else null
      val spent = mutableMapOf<String, Int>()
      // One item answers as before 6.13; several (items) answer each in its place, an error among them, with fewer recipes each.
      if (args["items"] == null) {
        val result = recipes(context, catalog, locale, asked.single(), MAX_RECIPES, plan, spent)
        return ToolOutput(result, summary(context, listOf(result)))
      }
      val each = if (asked.size == 1) MAX_RECIPES else MAX_RECIPES_EACH
      val results = asked.map { name ->
        try {
          buildJsonObject { put("query", name); recipes(context, catalog, locale, name, each, plan, spent).forEach { (key, value) -> put(key, value) } }
        } catch (error: ToolError) {
          buildJsonObject { put("query", name); put("error", error.message.orEmpty()) }
        }
      }
      return ToolOutput(buildJsonObject { put("results", JsonArray(results)) }, summary(context, results))
    }

    /** The chat's line: how many recipes came back for everything asked. */
    private fun summary(context: ToolContext, results: List<JsonObject>): String {
      val count = results.sumOf { ((it["recipes"] as? JsonArray)?.size ?: 0) + (it.int("more_recipes") ?: 0) }
      return if (count == 0) context.lang.text("summary.recipes_none") else context.lang.text("summary.recipes", "count" to count)
    }

    /**
     * How [asked] is made here, up to [most] recipes, or a [ToolError] that says why there's none to show. With [plan],
     * what crafting that many would take after what [spent] holds, or why it can't be planned.
     */
    private fun recipes(context: ToolContext, catalog: Catalog, locale: String?, asked: String, most: Int, plan: Int? = null, spent: MutableMap<String, Int> = mutableMapOf()): JsonObject {
      val item = Names.normalize(asked)
      // Potions come from the brewing stand: get_brewing_recipe tells its steps.
      if (item in POTIONS) throw ToolError("potions are brewed: call get_brewing_recipe with the potion, like night_vision")
      // An exact id first, then whatever the catalog makes of a name in any language.
      val exact = Material.matchMaterial(item)?.takeIf { !it.name.startsWith("LEGACY_") && it.isItem && !it.isAir }
      val entry = exact?.let { catalog.find(it.key.key, Catalog.Kind.ITEM) } ?: catalog.find(item, Catalog.Kind.CUSTOM)
        ?: catalog.resolve(asked, locale)
        ?: throw ToolError(if ("potion" in item) "potions are brewed: call get_brewing_recipe with the potion, like night_vision" else catalog.missing(asked, locale))
      if (entry.kind == Catalog.Kind.POTION) throw ToolError("potions are brewed: call get_brewing_recipe with ${entry.id}")
      if (entry.made == setOf("brewing")) throw ToolError("potions are brewed: ${entry.id} comes from a brewing stand; call get_brewing_recipe with ${entry.id}")
      val base = Material.matchMaterial(if (entry.kind == Catalog.Kind.CUSTOM) entry.base ?: "air" else entry.id)
      // Recipes by what comes out: a plugin's can is a potion underneath, but no potion's recipe makes it, nor it a potion.
      val own = base?.takeUnless { it.isAir }?.let { Bukkit.getRecipesFor(ItemStack(it)) }.orEmpty()
        .filter { Catalogs.id(it.result) == entry.id }.mapNotNull(::describe)
      val described = context.features.expansions?.items().orEmpty().filter { it.second.id() == entry.id }.flatMap { it.second.recipes().mapNotNull(Catalog::describe) }
      // Fireworks, copied books, dyed leather: recipes no fixed grid shows, in words.
      val special = SpecialRecipes.forItem(entry.id, specialKeys(context))
      val all = own + described + special
      val found = all.take(most)
      return buildJsonObject {
        put("item", entry.id)
        catalog.name(entry, locale)?.let { put("name", it) }
        if (entry.source != Catalog.MINECRAFT) put("source", entry.source)
        put("recipes", JsonArray(found))
        if (all.size > found.size) put("more_recipes", all.size - found.size)
        if (entry.id in SpecialRecipes.FIREWORK_ITEMS && special.isNotEmpty()) put("fireworks", SpecialRecipes.fireworks())
        if (plan != null) put("plan", try { chat.allay.spigot.automation.Crafting.look(context, entry.id, plan, spent) } catch (error: ToolError) { buildJsonObject { put("error", error.message.orEmpty()) } })
      }
    }

    /** The game's own recipe keys on this server, which say which special recipes it has. */
    private fun specialKeys(context: ToolContext): Set<String> = Bukkit.recipeIterator().asSequence()
      .mapNotNull { (it as? org.bukkit.Keyed)?.key?.takeIf { key -> key.namespace == org.bukkit.NamespacedKey.MINECRAFT }?.key }.toSet()

    /** Item tags by what they hold, so a slot that takes any planks reads `#planks`, not three of them. */
    private val tags: Map<Set<Material>, String> by lazy {
      Bukkit.getTags(Tag.REGISTRY_ITEMS, Material::class.java).groupBy({ it.values }, { it.key.key }).mapValues { (_, keys) -> keys.minBy { it.length } }
    }

    private fun choice(choice: RecipeChoice?): String = when (choice) {
      is RecipeChoice.MaterialChoice -> choice.choices.toSet().let { kinds ->
        tags[kinds].takeIf { kinds.size > 1 }?.let { "#$it (any of ${kinds.size})" } ?: alternatives(kinds.map { it.key.key })
      }
      // A bottle reads as its potion (`potion water`), a plugin's item by its own id.
      is RecipeChoice.ExactChoice -> alternatives(choice.choices.map { stack -> if (stack.type in POTION_ITEMS) Catalogs.bottle(stack).label else Catalogs.id(stack) }.distinct())
      null -> ""
      else -> choice.itemStack.type.key.key
    }

    /** Every alternative, or the first ones and how many more, so a long list never reads as complete. */
    private fun alternatives(ids: List<String>): String =
      if (ids.size <= 8) ids.joinToString(" | ") else ids.take(6).joinToString(" | ") + " | … ${ids.size - 6} more"

    private fun describe(recipe: Recipe): JsonObject? = buildJsonObject {
      if (recipe.result.amount > 1) put("makes", recipe.result.amount)
      when (recipe) {
        is ShapedRecipe -> {
          put("type", "crafting_shaped")
          put("shape", buildJsonArray { recipe.shape.forEach { add(JsonPrimitive(it)) } })
          putJsonObject("key") { recipe.choiceMap.forEach { (symbol, value) -> if (value != null) put(symbol.toString(), choice(value)) } }
        }
        is ShapelessRecipe -> {
          put("type", "crafting_shapeless")
          put("ingredients", buildJsonArray { recipe.choiceList.forEach { add(JsonPrimitive(choice(it))) } })
        }
        is CookingRecipe<*> -> {
          put("type", recipe.javaClass.simpleName.removePrefix("Craft").removeSuffix("Recipe").lowercase())
          put("input", choice(recipe.inputChoice))
          put("seconds", recipe.cookingTime / 20.0)
          put("experience", recipe.experience)
        }
        is StonecuttingRecipe -> {
          put("type", "stonecutting")
          put("input", choice(recipe.inputChoice))
        }
        is org.bukkit.inventory.TransmuteRecipe -> {
          put("type", "crafting_transmute")
          put("input", choice(recipe.input))
          put("material", choice(recipe.material))
          put("does", SpecialRecipes.TRANSMUTE)
        }
        is SmithingTransformRecipe -> {
          put("type", "smithing")
          put("template", choice(recipe.template))
          put("base", choice(recipe.base))
          put("addition", choice(recipe.addition))
        }
        else -> return null
      }
    }
  }

  private val advancements = object : Tool {
    override val name = "get_advancements"

    override fun run(context: ToolContext, args: JsonObject): ToolOutput {
      val player = context.requirePlayer()
      val filter = (args["filter"] as? JsonPrimitive)?.contentOrNull?.lowercase() ?: "todo"
      if (filter !in setOf("todo", "done", "all")) throw ToolError("filter must be todo, done, or all")
      val done = mutableListOf<JsonObject>()
      val todo = mutableListOf<JsonObject>()
      Bukkit.advancementIterator().forEach { advancement ->
        val key = advancement.key.key
        val display = advancement.display ?: return@forEach
        if (key.startsWith("recipes/")) return@forEach
        val progress = player.getAdvancementProgress(advancement)
        // A hidden one stays hidden until it's done, as on the game's own screen, which then counts it too.
        if (display.isHidden && !progress.isDone) return@forEach
        val entry = buildJsonObject {
          // The key set_goal takes for an advancement goal, such as story/mine_diamond.
          put("key", key)
          put("title", display.title)
          // What's done needs only its name, unless asked: descriptions are most of the tokens.
          if (!progress.isDone || args.bool("detail") == true) put("description", display.description)
        }
        when {
          progress.isDone -> done += entry
          // Started ones first: they are the nearest next steps. Spigot has no parent links to go by.
          progress.awardedCriteria.isNotEmpty() -> todo.add(0, buildJsonObject { entry.forEach { (k, v) -> put(k, v) }; put("started", true) })
          else -> todo += entry
        }
      }
      val result = buildJsonObject {
        put("done_count", done.size)
        put("todo_count", todo.size)
        if (filter != "todo") put("done", JsonArray(done.take(MAX_ADVANCEMENTS)))
        // The nearest next steps, started ones first; more would only cost tokens on every call.
        if (filter != "done") put("todo", JsonArray(todo.take(MAX_TODO)))
      }
      return ToolOutput(result, context.lang.text("summary.advancements", "done" to done.size, "todo" to todo.size))
    }
  }

  /** Done advancements listed at most, and next steps; the counts cover them all. */
  private const val MAX_ADVANCEMENTS = 40
  private const val MAX_TODO = 15

  /** Items one get_recipes call takes, and recipes shown for one item and for each of several. */
  private const val MAX_ITEMS = 5
  private const val MAX_RECIPES = 5
  private const val MAX_RECIPES_EACH = 3

  /** Names one search_items call takes, and hits for one name (unless limit says otherwise) and for each of several. */
  private const val MAX_QUERIES = 8
  private const val MAX_HITS = 8
  private const val MAX_HITS_EACH = 5

  /** Anything this server knows by a name in any language or an id: items, potions, and plugins' own. */
  private val search: Tool = object : Tool {
    override val name = "search_items"

    override fun run(context: ToolContext, args: JsonObject): ToolOutput {
      val queries = args.asked("queries", "query", MAX_QUERIES) ?: args.string("item")?.take(64)?.let(::listOf) ?: throw ToolError("say what to look for")
      val catalog = Catalogs.build(context)
      val locale = context.nameLocale
      // One name answers as before 6.13; several (queries) answer each in its place, with fewer hits each.
      val answer = if (args["queries"] == null) catalog.answer(queries.single(), locale, args.int("limit") ?: MAX_HITS)
        else catalog.answers(queries, locale, args.int("limit") ?: if (queries.size == 1) MAX_HITS else MAX_HITS_EACH)
      val results = (answer["results"] as? JsonArray)?.map { it as JsonObject } ?: listOf(answer)
      val count = results.sumOf { (it["matches"] as? JsonArray)?.size ?: 0 }
      return ToolOutput(answer, if (count == 0) context.lang.text("summary.items_none") else context.lang.text("summary.items_found", "count" to count))
    }
  }

  val all: List<Tool> = listOf(recipes, advancements, search)
}
