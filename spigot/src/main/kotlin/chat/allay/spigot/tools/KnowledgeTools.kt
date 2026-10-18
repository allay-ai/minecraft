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

  private val recipes = object : Tool {
    override val name = "get_recipes"

    override fun run(context: ToolContext, args: JsonObject): ToolOutput {
      val item = (args["item"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()?.removePrefix("minecraft:")
        ?.replace(' ', '_') ?: throw ToolError("say which item")
      // Potions come from the brewing stand, which Spigot's recipes don't describe.
      if (item in POTIONS || "potion" in item) throw ToolError("potions are brewed: call get_brewing_recipe with the potion, like night_vision")
      val material = Material.matchMaterial(item)?.takeIf { it.isItem } ?: throw ToolError("no item called $item")
      val all = Bukkit.getRecipesFor(ItemStack(material)).mapNotNull(::describe)
      val found = all.take(5)
      val result = buildJsonObject {
        put("item", material.key.key)
        put("recipes", JsonArray(found))
        if (all.size > found.size) put("more_recipes", all.size - found.size)
      }
      return ToolOutput(result, if (found.isEmpty()) context.lang.text("summary.recipes_none") else context.lang.text("summary.recipes", "count" to all.size))
    }

    /** Item tags by what they hold, so a slot that takes any planks reads `#planks`, not three of them. */
    private val tags: Map<Set<Material>, String> by lazy {
      Bukkit.getTags(Tag.REGISTRY_ITEMS, Material::class.java).groupBy({ it.values }, { it.key.key }).mapValues { (_, keys) -> keys.minBy { it.length } }
    }

    private fun choice(choice: RecipeChoice?): String = when (choice) {
      is RecipeChoice.MaterialChoice -> choice.choices.toSet().let { kinds ->
        tags[kinds].takeIf { kinds.size > 1 }?.let { "#$it (any of ${kinds.size})" } ?: alternatives(kinds.map { it.key.key })
      }
      is RecipeChoice.ExactChoice -> alternatives(choice.choices.map { it.type.key.key }.distinct())
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
        if (key.startsWith("recipes/") || display.isHidden) return@forEach
        val entry = buildJsonObject {
          // The key set_goal takes for an advancement goal, such as story/mine_diamond.
          put("key", key)
          put("title", display.title)
          put("description", display.description)
        }
        val progress = player.getAdvancementProgress(advancement)
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
        if (filter != "done") put("todo", JsonArray(todo.take(MAX_ADVANCEMENTS)))
      }
      return ToolOutput(result, context.lang.text("summary.advancements", "done" to done.size, "todo" to todo.size))
    }
  }

  /** Advancements listed per list at most; the counts cover them all. */
  private const val MAX_ADVANCEMENTS = 40

  val all = listOf(recipes, advancements)
}
