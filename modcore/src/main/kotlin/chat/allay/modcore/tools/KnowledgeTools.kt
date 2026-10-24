package chat.allay.modcore.tools

import chat.allay.core.*
import chat.allay.core.tools.*
import chat.allay.modcore.Texts

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.Item
import net.minecraft.world.item.crafting.AbstractCookingRecipe
import net.minecraft.world.item.crafting.BlastingRecipe
import net.minecraft.world.item.crafting.CampfireCookingRecipe
import net.minecraft.world.item.crafting.Ingredient
import net.minecraft.world.item.crafting.Recipe
import net.minecraft.world.item.crafting.ShapedRecipe
import net.minecraft.world.item.crafting.ShapelessRecipe
import net.minecraft.world.item.crafting.SmithingTransformRecipe
import net.minecraft.world.item.crafting.SmokingRecipe
import net.minecraft.world.item.crafting.StonecutterRecipe

/** What this server knows that the model might not: its exact recipes, and the asker's advancements. */
object KnowledgeTools {
  private val POTIONS = setOf("potion", "splash_potion", "lingering_potion")

  private val recipes = object : Tool {
    override val name = "get_recipes"

    override fun run(context: ToolContext, args: JsonObject): ToolOutput {
      val asked = (args["item"] as? JsonPrimitive)?.contentOrNull ?: throw ToolError("say which item")
      val item = Names.normalize(asked)
      // Potions come from the brewing stand: get_brewing_recipe tells its steps.
      if (item in POTIONS) throw ToolError("potions are brewed: call get_brewing_recipe with the potion, like night_vision")
      val catalog = Catalogs.build(context)
      val locale = (context.asker as? PlayerAudience)?.locale
      // An exact id first, then whatever the catalog makes of a name in any language.
      val entry = InteractTools.known(item)?.let { catalog.find(Recipes.id(it), Catalog.Kind.ITEM) } ?: catalog.find(item, Catalog.Kind.CUSTOM)
        ?: catalog.resolve(asked, locale)
        ?: throw ToolError(if ("potion" in item) "potions are brewed: call get_brewing_recipe with the potion, like night_vision" else catalog.missing(asked, locale))
      if (entry.kind == Catalog.Kind.POTION) throw ToolError("potions are brewed: call get_brewing_recipe with ${entry.id}")
      if (entry.made == setOf("brewing")) throw ToolError("potions are brewed: ${entry.id} comes from a brewing stand; call get_brewing_recipe with ${entry.id}")
      tags = tags()
      val own = Recipes.all(context.server).filter { holder ->
        val made = Recipes.result(holder.value())
        made != null && !made.isEmpty && Catalogs.id(made) == entry.id
      }.mapNotNull { describe(it.value()) }
      val described = context.features.expansions?.items().orEmpty().filter { it.second.id() == entry.id }.flatMap { it.second.recipes().mapNotNull(Catalog::describe) }
      val all = own + described
      val found = all.take(5)
      val result = buildJsonObject {
        put("item", entry.id)
        catalog.name(entry, locale)?.let { put("name", it) }
        if (entry.source != Catalog.MINECRAFT) put("source", entry.source)
        put("recipes", JsonArray(found))
        if (all.size > found.size) put("more_recipes", all.size - found.size)
      }
      return ToolOutput(result, if (found.isEmpty()) context.lang.text("summary.recipes_none") else context.lang.text("summary.recipes", "count" to all.size))
    }

    /**
     * Item tags by what they hold, so a slot that takes any planks reads `#planks`, not three of them. Read again on
     * every call: data packs change them, and a singleplayer game keeps this object from one world to the next.
     * Server thread only.
     */
    private var tags: Map<Set<Item>, String> = emptyMap()

    private fun tags(): Map<Set<Item>, String> =
      BuiltInRegistries.ITEM.getTags().toList().groupBy({ tag -> tag.stream().map { it.value() }.toList().toSet() }, { it.key().location().path })
        .mapValues { (_, keys) -> keys.minBy { it.length } }

    private fun choice(choice: Ingredient?): String {
      choice ?: return ""
      val kinds = choice.items().map { it.value() }.toList()
      val set = kinds.toSet()
      return tags[set].takeIf { set.size > 1 }?.let { "#$it (any of ${set.size})" } ?: alternatives(kinds.map(Recipes::id).distinct())
    }

    /** Every alternative, or the first ones and how many more, so a long list never reads as complete. */
    private fun alternatives(ids: List<String>): String =
      if (ids.size <= 8) ids.joinToString(" | ") else ids.take(6).joinToString(" | ") + " | … ${ids.size - 6} more"

    /** The grid's rows as Bukkit's `ShapedRecipe.getShape` letters them: a cell a letter, `a` to `i`, empty ones too. */
    private fun shape(recipe: ShapedRecipe): List<String> =
      (0 until recipe.height).map { row -> (0 until recipe.width).map { column -> 'a' + row * recipe.width + column }.joinToString("") }

    private fun describe(recipe: Recipe<*>): JsonObject? = buildJsonObject {
      val made = Recipes.result(recipe) ?: return null
      if (made.count > 1) put("makes", made.count)
      when (recipe) {
        is ShapedRecipe -> {
          put("type", "crafting_shaped")
          put("shape", buildJsonArray { shape(recipe).forEach { add(JsonPrimitive(it)) } })
          putJsonObject("key") { Recipes.grid(recipe).forEachIndexed { cell, value -> if (value != null) put(('a' + cell).toString(), choice(value)) } }
        }
        is ShapelessRecipe -> {
          put("type", "crafting_shapeless")
          put("ingredients", buildJsonArray { Recipes.ingredients(recipe).forEach { add(JsonPrimitive(choice(it))) } })
        }
        is AbstractCookingRecipe -> {
          put("type", cooking(recipe))
          put("input", choice(recipe.input()))
          put("seconds", recipe.cookingTime() / 20.0)
          put("experience", recipe.experience())
        }
        is StonecutterRecipe -> {
          put("type", "stonecutting")
          put("input", choice(recipe.input()))
        }
        is SmithingTransformRecipe -> {
          put("type", "smithing")
          put("template", choice(recipe.templateIngredient().orElse(null)))
          put("base", choice(recipe.baseIngredient()))
          put("addition", choice(recipe.additionIngredient().orElse(null)))
        }
        else -> return null
      }
    }

    /** The cooking kind as Bukkit names its recipe classes: `furnace`, `blasting`, `smoking`, `campfire`. */
    private fun cooking(recipe: AbstractCookingRecipe) = when (recipe) {
      is BlastingRecipe -> "blasting"
      is SmokingRecipe -> "smoking"
      is CampfireCookingRecipe -> "campfire"
      else -> "furnace"
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
      context.server.advancements.allAdvancements.forEach { advancement ->
        val key = advancement.id().path
        val display = advancement.value().display().orElse(null) ?: return@forEach
        if (key.startsWith("recipes/") || display.hidden()) return@forEach
        val entry = buildJsonObject {
          // The key set_goal takes for an advancement goal, such as story/mine_diamond.
          put("key", key)
          put("title", Texts.legacyOf(display.title()))
          put("description", Texts.legacyOf(display.description()))
        }
        val progress = player.advancements.getOrStartProgress(advancement)
        when {
          progress.isDone -> done += entry
          // Started ones first: they are the nearest next steps. Bukkit has no parent links to go by, so neither do we.
          progress.completedCriteria.iterator().hasNext() -> todo.add(0, buildJsonObject { entry.forEach { (k, v) -> put(k, v) }; put("started", true) })
          else -> todo += entry
        }
      }
      val result = buildJsonObject {
        put("done_count", done.size)
        put("todo_count", todo.size)
        if (filter != "todo") put("done", JsonArray(done.take(MAX_ADVANCEMENTS)))
        if (filter != "done") put("todo", JsonArray(todo.take(MAX_ADVANCEMENTS)))
      }
      return ToolOutput(result, context.lang.text("summary.advancements", "done" to done.size, "todo" to todo.size))
    }
  }

  /** Advancements listed per list at most; the counts cover them all. */
  private const val MAX_ADVANCEMENTS = 40

  /** Anything this server knows by a name in any language or an id: items, potions, and plugins' and mods' own. */
  private val search: Tool = object : Tool {
    override val name = "search_items"

    override fun run(context: ToolContext, args: JsonObject): ToolOutput {
      val query = args.string("query") ?: args.string("item") ?: throw ToolError("say what to look for")
      val locale = (context.asker as? PlayerAudience)?.locale
      val answer = Catalogs.build(context).answer(query.take(64), locale, args.int("limit") ?: 8)
      val count = (answer["matches"] as? JsonArray)?.size ?: 0
      return ToolOutput(answer, if (count == 0) context.lang.text("summary.items_none") else context.lang.text("summary.items_found", "count" to count))
    }
  }

  val all: List<Tool> = listOf(recipes, advancements, search)
}
