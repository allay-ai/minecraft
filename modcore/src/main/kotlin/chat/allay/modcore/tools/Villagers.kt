package chat.allay.modcore.tools

import chat.allay.core.*
import chat.allay.core.tools.*

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.npc.villager.AbstractVillager
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.trading.MerchantOffer

/** Villagers and wandering traders around a player, nearest first, with what they trade. */
object Villagers {
  class Found(val trader: AbstractVillager, val distance: Double, val trades: List<MerchantOffer>)

  /** [item] keeps only trades that sell or ask for it, and only traders who have one. */
  fun around(player: ServerPlayer, radius: Int, item: Item? = null): List<Found> =
    player.level().getEntitiesOfClass(AbstractVillager::class.java, player.boundingBox.inflate(radius.toDouble()))
      .map { trader ->
        val trades = trader.offers.filter { offer -> item == null || offer.result.item == item || offer.costA.item == item || (!offer.costB.isEmpty && offer.costB.item == item) || storesBook(offer.result, item) }
        Found(trader, trader.position().distanceTo(player.position()), trades)
      }
      .filter { item == null || it.trades.isNotEmpty() }
      .sortedBy { it.distance }

  /** An enchanted book counts as “the item” when the model asks for enchanted_book. */
  private fun storesBook(result: ItemStack, item: Item) = item == Items.ENCHANTED_BOOK && result.item == Items.ENCHANTED_BOOK

  fun profession(trader: AbstractVillager): String = when (trader) {
    is Villager -> trader.villagerData.profession().unwrapKey().map { Worlds.key(it.identifier()) }.orElse("none")
    is WanderingTrader -> "wandering_trader"
    else -> "trader"
  }

  /** The workstation that gives a profession, as the icon of a villager in the window. */
  fun icon(profession: String): Item = when (profession) {
    "armorer" -> Items.BLAST_FURNACE
    "butcher" -> Items.SMOKER
    "cartographer" -> Items.CARTOGRAPHY_TABLE
    "cleric" -> Items.BREWING_STAND
    "farmer" -> Items.COMPOSTER
    "fisherman" -> Items.BARREL
    "fletcher" -> Items.FLETCHING_TABLE
    "leatherworker" -> Items.CAULDRON
    "librarian" -> Items.LECTERN
    "mason" -> Items.STONECUTTER
    "shepherd" -> Items.LOOM
    "toolsmith" -> Items.SMITHING_TABLE
    "weaponsmith" -> Items.GRINDSTONE
    "wandering_trader" -> Items.WANDERING_TRADER_SPAWN_EGG
    else -> Items.VILLAGER_SPAWN_EGG
  }

  /** “3 × Emerald” in the reader's language; the window's lore can't ask the client to translate, so `items.<id>` in the language file names what villagers usually ask for. */
  fun price(lang: Lang, item: ItemStack): String {
    val id = Recipes.id(item.item)
    val name = if (lang.has("items.$id")) lang.text("items.$id") else id.replace('_', ' ')
    return "${item.count} × $name"
  }

  /** One trade as every tool shows it: what this player pays now, what they get (books name their enchantment), and how many are left. */
  fun trade(offer: MerchantOffer) = buildJsonObject {
    put("buy", buildJsonArray { prices(offer).forEach { item -> item.compact()?.let(::add) } })
    offer.result.compact()?.let { put("sell", it) }
    put("left", (offer.maxUses - offer.uses).coerceAtLeast(0))
    put("max_uses", offer.maxUses)
  }

  /** The price this player pays now, with demand and their discounts. */
  fun prices(offer: MerchantOffer): List<ItemStack> = listOf(offer.costA) + listOfNotNull(offer.costB.takeUnless { it.isEmpty })
}
