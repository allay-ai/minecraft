package chat.allay.modcore.tools

import chat.allay.core.*
import chat.allay.core.tools.*

import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.alchemy.PotionContents

/**
 * The brewing stand's recipes on this server: since 26.3 brewing is a recipe like any other, so the server's recipes
 * hold the game's brews, data packs', and mods' alike, and expansions add the ones a mod handles in code. Read on the
 * server thread from the features [bind] hands in.
 */
object BrewingRecipes {
  @Volatile private var features: Features? = null

  /** The server's features while it runs; null when it stops. */
  fun bind(features: Features?) {
    this.features = features
  }

  /** Every brew, or none before a server runs. */
  fun all(): List<Brewing.Brew> {
    val features = features ?: return emptyList()
    return Catalogs.brews(features.server, features.expansions)
  }

  /** What the model or a player named: the potion's id and the bottle's form, or a [ToolError] saying what's wrong. */
  fun target(potion: String, form: String?): Pair<List<Brewing.Brew>, Brewing.Bottle> {
    val brews = all().ifEmpty { throw ToolError("this server's game keeps brewing out of reach; answer from what you know") }
    val catalog by lazy { features?.let { Catalogs.build(it.server, it.expansions, it.names()) } }
    // Names in any language, “Potion of Infestation” and «зелье заражения» alike, through the catalog.
    val named = { name: String -> catalog?.resolve(name, null, setOf(Catalog.Kind.POTION, Catalog.Kind.CUSTOM))?.id }
    val id = Brewing.find(brews, potion, named) ?: Brewing.find(brews, Brewing.split(potion).first, named)
      ?: throw ToolError(catalog?.missing(potion, null, setOf(Catalog.Kind.POTION, Catalog.Kind.CUSTOM))?.replace("no item called", "no potion called")
        ?: "no potion called $potion on this server")
    // “splash healing” names its form itself; form wins when both are there.
    val bottle = form?.trim()?.ifEmpty { null }?.let { name ->
      Brewing.form(name) ?: throw ToolError("form must be potion, splash_potion, or lingering_potion")
    } ?: Brewing.split(potion).second ?: "potion"
    // A plugin's or mod's own bottle has no forms.
    if (brews.any { it.to == Brewing.Bottle(id, id) }) return brews to Brewing.Bottle(id, id)
    return brews to Brewing.Bottle(bottle, id)
  }

  /** A bottle as the game draws it, named in the reader's language by their client. */
  fun bottle(bottle: Brewing.Bottle): ItemStack {
    val item = ItemStack(Identifier.tryParse(bottle.item)?.let { BuiltInRegistries.ITEM.getOptional(it).orElse(null) } ?: Items.POTION)
    Identifier.tryParse(bottle.potion)?.let { BuiltInRegistries.POTION.get(it).orElse(null) }?.let { item.set(DataComponents.POTION_CONTENTS, PotionContents(it)) }
    return item
  }
}
