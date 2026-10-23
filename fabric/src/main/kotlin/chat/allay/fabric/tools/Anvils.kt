package chat.allay.fabric.tools

import chat.allay.core.tools.Mechanics
import chat.allay.core.tools.ToolError

import net.minecraft.core.Holder
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.Enchantment
import net.minecraft.world.item.enchantment.ItemEnchantments
import net.minecraft.core.HolderLookup

/**
 * Merging what the player has on an anvil: the item in hand, enchanted books, and other items of its kind from the
 * inventory, planned by [Mechanics.mergePlan]. Shared by `get_anvil_plan` and its button.
 */
object Anvils {
  /** One thing the plan may use; [slot] is null for the item in hand. */
  class Source(val slot: Int?, val item: ItemStack, val piece: Mechanics.AnvilPiece)

  class Planned(val sources: List<Source>, val plan: Mechanics.MergePlan)

  /** Pieces beyond the item in hand; the planner tries every order, so it stays small. */
  private const val MAX_EXTRA = 7

  /** The game's enchantments, from the server's registry. */
  fun registry(player: ServerPlayer): HolderLookup.RegistryLookup<Enchantment> = player.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)

  fun enchantment(player: ServerPlayer, key: String): Holder<Enchantment>? =
    Identifier.tryParse(key)?.let { id -> registry(player).get(net.minecraft.resources.ResourceKey.create(Registries.ENCHANTMENT, id)).orElse(null) }

  /** The game's rules for the kind of item in hand. */
  class Rules(private val player: ServerPlayer, type: Item) : Mechanics.Enchanting {
    private val sample = ItemStack(type)
    private fun of(key: String): Holder<Enchantment>? = enchantment(player, key)
    override fun maxLevel(key: String) = of(key)?.value()?.maxLevel ?: 1
    override fun fits(key: String) = of(key)?.value()?.canEnchant(sample) ?: false
    override fun compatible(a: String, b: String): Boolean {
      val first = of(a) ?: return true
      val second = of(b) ?: return true
      return Enchantment.areCompatible(first, second)
    }
  }

  fun piece(item: ItemStack): Mechanics.AnvilPiece {
    val book = item.has(DataComponents.STORED_ENCHANTMENTS)
    val enchantments = item.get(if (book) DataComponents.STORED_ENCHANTMENTS else DataComponents.ENCHANTMENTS) ?: ItemEnchantments.EMPTY
    return Mechanics.AnvilPiece(book, enchantments.entrySet().associate { Worlds.key(it.key) to it.intValue }, item.get(DataComponents.REPAIR_COST) ?: 0)
  }

  /**
   * The plan for the item in hand. [slots] picks inventory slots (0–35) to use; without them, every enchanted book
   * with something that fits and every enchanted item of the same kind is a candidate, the richest first.
   */
  fun plan(player: ServerPlayer, slots: List<Int>? = null): Planned? {
    val inventory = player.inventory
    val held = inventory.getSelectedItem().takeUnless { it.isEmpty } ?: throw ToolError("the player holds nothing; ask them to hold the item")
    if (held.item == Items.ENCHANTED_BOOK || held.item == Items.BOOK) throw ToolError("hold the item to enchant, not a book")
    val rules = Rules(player, held.item)
    val hand = inventory.selectedSlot
    val candidates = (slots ?: (0..35).toList()).distinct().filter { it in 0..35 && it != hand }.mapNotNull { slot ->
      val item = inventory.getItem(slot).takeUnless { it.isEmpty } ?: return@mapNotNull null
      val piece = piece(item)
      val useful = when {
        piece.book -> piece.enchantments.keys.any(rules::fits)
        item.item == held.item -> piece.enchantments.isNotEmpty()
        else -> false
      }
      if (!useful) {
        if (slots != null) throw ToolError("slot $slot holds ${Recipes.id(item.item)}, which can't go onto ${Recipes.id(held.item)}")
        null
      } else Source(slot, item, piece)
    }.sortedByDescending { it.piece.levels }.take(MAX_EXTRA)
    if (candidates.isEmpty()) return null
    val sources = listOf(Source(null, held, piece(held))) + candidates
    val plan = Mechanics.mergePlan(sources.map { it.piece }, rules) ?: return null
    return Planned(sources, plan)
  }

  /** A piece as the window draws it: the item of this kind or a book, with the enchantments the step gives it. */
  fun render(player: ServerPlayer, type: Item, piece: Mechanics.AnvilPiece): ItemStack {
    val item = ItemStack(if (piece.book) Items.ENCHANTED_BOOK else type)
    val enchantments = ItemEnchantments.Mutable(ItemEnchantments.EMPTY)
    piece.enchantments.forEach { (key, level) -> enchantment(player, key)?.let { enchantments.set(it, level) } }
    item.set(if (piece.book) DataComponents.STORED_ENCHANTMENTS else DataComponents.ENCHANTMENTS, enchantments.toImmutable())
    return item
  }

  /** Where a source is, as a player looks for it: “hand”, “hotbar 3”, or “inventory row 1 column 6”. */
  fun label(source: Source): String = when (val slot = source.slot) {
    null -> "hand"
    in 0..8 -> "hotbar ${slot + 1}"
    else -> "inventory row ${(slot - 9) / 9 + 1} column ${(slot - 9) % 9 + 1}"
  }
}
