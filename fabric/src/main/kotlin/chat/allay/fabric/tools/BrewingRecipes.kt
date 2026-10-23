package chat.allay.fabric.tools

import chat.allay.core.*
import chat.allay.core.tools.*

import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.alchemy.PotionContents
import java.net.JarURLConnection

/**
 * The brewing stand's recipes, read once from the game's data inside its jar: 26.3 keeps them as files, read here as
 * on Bukkit, so both tell the same. A datapack's own brews aren't seen.
 */
object BrewingRecipes {
  private const val FOLDER = "data/minecraft/recipe/brewing/"
  // Any file of the folder finds the jar; the folder's own entry may be missing from it.
  private const val PROBE = FOLDER + "potion_awkward_golden_carrot.json"

  @Volatile private var cached: List<Brewing.Brew>? = null

  /** Every brew, or none on a game that keeps brewing in code. */
  fun all(): List<Brewing.Brew> = cached ?: load().also { cached = it }

  private fun load(): List<Brewing.Brew> = runCatching {
    val url = MinecraftServer::class.java.classLoader.getResource(PROBE) ?: return emptyList()
    val connection = url.openConnection() as? JarURLConnection ?: return emptyList()
    connection.useCaches = false
    connection.jarFile.use { jar ->
      jar.entries().asSequence().filter { it.name.startsWith(FOLDER) && it.name.endsWith(".json") }
        .flatMap { entry -> jar.getInputStream(entry).use { Brewing.parse(it.readBytes().decodeToString()) } }.toList()
    }
  }.getOrDefault(emptyList())

  /** What the model or a player named: the potion's id and the bottle's form, or a [ToolError] saying what's wrong. */
  fun target(potion: String, form: String?): Pair<List<Brewing.Brew>, Brewing.Bottle> {
    val brews = all().ifEmpty { throw ToolError("this server's game keeps brewing out of reach; answer from what you know") }
    val id = Brewing.find(brews, potion) ?: throw ToolError("no potion called $potion; the stand makes ${Brewing.potions(brews).joinToString()}")
    // “splash healing” names its form itself; form wins when both are there.
    val bottle = form?.trim()?.ifEmpty { null }?.let { name ->
      Brewing.form(name) ?: throw ToolError("form must be potion, splash_potion, or lingering_potion")
    } ?: Brewing.split(potion).second ?: "potion"
    return brews to Brewing.Bottle(bottle, id)
  }

  /** A bottle as the game draws it, named in the reader's language by their client. */
  fun bottle(bottle: Brewing.Bottle): ItemStack {
    val item = ItemStack(Identifier.tryParse(bottle.item)?.let { BuiltInRegistries.ITEM.getOptional(it).orElse(null) } ?: Items.POTION)
    Identifier.tryParse(bottle.potion)?.let { BuiltInRegistries.POTION.get(it).orElse(null) }?.let { item.set(DataComponents.POTION_CONTENTS, PotionContents(it)) }
    return item
  }
}
