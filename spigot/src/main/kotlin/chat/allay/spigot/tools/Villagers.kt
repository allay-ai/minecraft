package chat.allay.spigot.tools

import chat.allay.core.*
import chat.allay.core.Lang
import chat.allay.core.tools.*

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bukkit.Material
import org.bukkit.entity.AbstractVillager
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.entity.WanderingTrader
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.MerchantRecipe

/** Villagers and wandering traders around a player, nearest first, with what they trade. */
object Villagers {
  class Found(val trader: AbstractVillager, val distance: Double, val trades: List<MerchantRecipe>)

  /** [item] keeps only trades that sell or ask for it, and only traders who have one. */
  fun around(player: Player, radius: Int, item: Material? = null): List<Found> =
    player.getNearbyEntities(radius.toDouble(), radius.toDouble(), radius.toDouble())
      .filterIsInstance<AbstractVillager>()
      .map { trader ->
        val trades = trader.recipes.filter { offer -> item == null || offer.result.type == item || offer.ingredients.any { it.type == item } || storesBook(offer.result, item) }
        Found(trader, trader.location.distance(player.location), trades)
      }
      .filter { item == null || it.trades.isNotEmpty() }
      .sortedBy { it.distance }

  /** An enchanted book counts as “the item” when the model asks for enchanted_book. */
  private fun storesBook(result: ItemStack, item: Material) = item == Material.ENCHANTED_BOOK && result.type == Material.ENCHANTED_BOOK

  fun profession(trader: AbstractVillager): String = when (trader) {
    is Villager -> trader.profession.key.key
    is WanderingTrader -> "wandering_trader"
    else -> "trader"
  }

  /** The workstation that gives a profession, as the icon of a villager in the window. */
  fun icon(profession: String): Material = when (profession) {
    "armorer" -> Material.BLAST_FURNACE
    "butcher" -> Material.SMOKER
    "cartographer" -> Material.CARTOGRAPHY_TABLE
    "cleric" -> Material.BREWING_STAND
    "farmer" -> Material.COMPOSTER
    "fisherman" -> Material.BARREL
    "fletcher" -> Material.FLETCHING_TABLE
    "leatherworker" -> Material.CAULDRON
    "librarian" -> Material.LECTERN
    "mason" -> Material.STONECUTTER
    "shepherd" -> Material.LOOM
    "toolsmith" -> Material.SMITHING_TABLE
    "weaponsmith" -> Material.GRINDSTONE
    "wandering_trader" -> Material.WANDERING_TRADER_SPAWN_EGG
    else -> Material.VILLAGER_SPAWN_EGG
  }

  /** “3 × Emerald” in the reader's language; the window's lore can't ask the client to translate, so `items.<id>` in the language file names what villagers usually ask for. */
  fun price(lang: Lang, item: ItemStack): String {
    val id = item.type.key.key
    val name = if (lang.has("items.$id")) lang.text("items.$id") else id.replace('_', ' ')
    return "${item.amount} × $name"
  }

  /** One trade as every tool shows it: what this player pays now, what they get (books name their enchantment), and how many are left. */
  fun trade(offer: MerchantRecipe) = buildJsonObject {
    put("buy", buildJsonArray { prices(offer).forEach { item -> item.compact()?.let(::add) } })
    offer.result.compact()?.let { put("sell", it) }
    put("left", (offer.maxUses - offer.uses).coerceAtLeast(0))
    put("max_uses", offer.maxUses)
  }

  /** The price this player pays now, with demand and their discounts. */
  fun prices(offer: MerchantRecipe): List<ItemStack> = listOfNotNull(offer.getAdjustedIngredient1() ?: offer.ingredients.firstOrNull()) + offer.ingredients.drop(1)
}
