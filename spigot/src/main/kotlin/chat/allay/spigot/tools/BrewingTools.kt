package chat.allay.spigot.tools

import chat.allay.core.*
import chat.allay.core.tools.*

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.Server
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.PotionMeta
import java.net.JarURLConnection

/**
 * The brewing stand's recipes, read once from the game's data inside the server jar: 26.3 keeps them as files, and
 * Spigot's `BrewingRecipe` tells only what comes out. A datapack's own brews aren't seen.
 */
object BrewingRecipes {
  private const val FOLDER = "data/minecraft/recipe/brewing/"
  // Any file of the folder finds the jar; the folder's own entry may be missing from it.
  private const val PROBE = FOLDER + "potion_awkward_golden_carrot.json"

  @Volatile private var cached: List<Brewing.Brew>? = null

  /** Every brew, or none on a game that keeps brewing in code. */
  fun all(server: Server): List<Brewing.Brew> = cached ?: load(server).also { cached = it }

  private fun load(server: Server): List<Brewing.Brew> = runCatching {
    val url = server.javaClass.classLoader.getResource(PROBE) ?: return emptyList()
    val connection = url.openConnection() as? JarURLConnection ?: return emptyList()
    connection.useCaches = false
    connection.jarFile.use { jar ->
      jar.entries().asSequence().filter { it.name.startsWith(FOLDER) && it.name.endsWith(".json") }
        .flatMap { entry -> jar.getInputStream(entry).use { Brewing.parse(it.readBytes().decodeToString()) } }.toList()
    }
  }.getOrDefault(emptyList())

  /** What the model or a player named: the potion's id and the bottle's form, or a [ToolError] saying what's wrong. */
  fun target(server: Server, potion: String, form: String?): Pair<List<Brewing.Brew>, Brewing.Bottle> {
    val brews = all(server).ifEmpty { throw ToolError("this server's game keeps brewing out of reach; answer from what you know") }
    val id = Brewing.find(brews, potion) ?: throw ToolError("no potion called $potion; the stand makes ${Brewing.potions(brews).joinToString()}")
    // “splash healing” names its form itself; form wins when both are there.
    val bottle = form?.trim()?.ifEmpty { null }?.let { name ->
      Brewing.form(name) ?: throw ToolError("form must be potion, splash_potion, or lingering_potion")
    } ?: Brewing.split(potion).second ?: "potion"
    return brews to Brewing.Bottle(bottle, id)
  }

  /** A bottle as the game draws it, named in the reader's language by their client. */
  fun bottle(bottle: Brewing.Bottle): ItemStack {
    val item = ItemStack(Material.matchMaterial(bottle.item) ?: Material.POTION)
    val meta = item.itemMeta as? PotionMeta ?: return item
    Registry.POTION.get(NamespacedKey.minecraft(bottle.potion))?.let(meta::setBasePotionType)
    item.itemMeta = meta
    return item
  }
}

/** How to brew a potion, from the game's own recipes. */
object BrewingTools {
  private val recipe = object : Tool {
    override val name = "get_brewing_recipe"

    override fun run(context: ToolContext, args: JsonObject): ToolOutput {
      val (brews, target) = BrewingRecipes.target(context.plugin.server, args.string("potion") ?: throw ToolError("say which potion"), args.string("form"))
      val chain = Brewing.chain(brews, target) ?: throw ToolError("the brewing stand can't make ${target.item} of ${target.potion}")
      val result = buildJsonObject {
        put("potion", target.potion)
        put("form", target.item)
        put("steps", buildJsonArray {
          chain.forEach { brew ->
            add(buildJsonObject {
              put("bottle", "${brew.from.item} ${brew.from.potion}")
              put("add", brew.reagent)
              put("makes", "${brew.to.item} ${brew.to.potion}")
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
