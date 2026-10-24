package chat.allay.modcore.tools

import chat.allay.core.*
import chat.allay.core.tools.*

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** How to brew a potion, from the game's own recipes. */
object BrewingTools {
  private val recipe = object : Tool {
    override val name = "get_brewing_recipe"

    override fun run(context: ToolContext, args: JsonObject): ToolOutput {
      val (brews, target) = BrewingRecipes.target(args.string("potion") ?: throw ToolError("say which potion"), args.string("form"))
      val chain = Brewing.chain(brews, target) ?: throw ToolError("the brewing stand can't make ${target.item} of ${target.potion}")
      val result = buildJsonObject {
        put("potion", target.potion)
        put("form", target.item)
        put("steps", buildJsonArray {
          chain.forEach { brew ->
            add(buildJsonObject {
              put("bottle", brew.from.label)
              put("add", brew.reagent)
              put("makes", brew.to.label)
            })
          }
        })
        put("fuel", "blaze_powder")
      }
      return ToolOutput(result, context.lang.text("summary.brewing_steps", "count" to chain.size))
    }
  }

  val all = listOf<Tool>(recipe)
}
