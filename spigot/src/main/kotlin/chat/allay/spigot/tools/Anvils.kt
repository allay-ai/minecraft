package chat.allay.spigot.tools

import chat.allay.core.tools.Mechanics
import chat.allay.core.tools.ToolError

import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.EnchantmentStorageMeta
import org.bukkit.inventory.meta.Repairable

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

  /** The game's rules for the kind of item in hand, from Bukkit's registry. */
  class Rules(type: Material) : Mechanics.Enchanting {
    private val sample = ItemStack(type)
    private fun of(key: String): Enchantment? = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(key))
    override fun maxLevel(key: String) = of(key)?.maxLevel ?: 1
    override fun fits(key: String) = of(key)?.canEnchantItem(sample) ?: false
    override fun compatible(a: String, b: String): Boolean {
      val first = of(a) ?: return true
      val second = of(b) ?: return true
      return !first.conflictsWith(second) && !second.conflictsWith(first)
    }
  }

  fun piece(item: ItemStack): Mechanics.AnvilPiece {
    val meta = item.itemMeta
    val book = meta is EnchantmentStorageMeta
    val enchantments = if (meta is EnchantmentStorageMeta) meta.storedEnchants else item.enchantments
    return Mechanics.AnvilPiece(book, enchantments.mapKeys { it.key.key.key }, (meta as? Repairable)?.repairCost ?: 0)
  }

  /**
   * The plan for the item in hand. [slots] picks inventory slots (0–35) to use; without them, every enchanted book
   * with something that fits and every enchanted item of the same kind is a candidate, the richest first.
   */
  fun plan(player: Player, slots: List<Int>? = null): Planned? {
    val held = player.inventory.itemInMainHand.takeUnless { it.type.isAir } ?: throw ToolError("the player holds nothing; ask them to hold the item")
    if (held.type == Material.ENCHANTED_BOOK || held.type == Material.BOOK) throw ToolError("hold the item to enchant, not a book")
    val rules = Rules(held.type)
    val hand = player.inventory.heldItemSlot
    val candidates = (slots ?: (0..35).toList()).distinct().filter { it in 0..35 && it != hand }.mapNotNull { slot ->
      val item = player.inventory.getItem(slot)?.takeUnless { it.type.isAir } ?: return@mapNotNull null
      val piece = piece(item)
      val useful = when {
        piece.book -> piece.enchantments.keys.any(rules::fits)
        item.type == held.type -> piece.enchantments.isNotEmpty()
        else -> false
      }
      if (!useful) {
        if (slots != null) throw ToolError("slot $slot holds ${item.type.key.key}, which can't go onto ${held.type.key.key}")
        null
      } else Source(slot, item, piece)
    }.sortedByDescending { it.piece.levels }.take(MAX_EXTRA)
    if (candidates.isEmpty()) return null
    val sources = listOf(Source(null, held, piece(held))) + candidates
    val plan = Mechanics.mergePlan(sources.map { it.piece }, rules) ?: return null
    return Planned(sources, plan)
  }

  /** A piece as the window draws it: the item of this kind or a book, with the enchantments the step gives it. */
  fun render(type: Material, piece: Mechanics.AnvilPiece): ItemStack {
    val item = ItemStack(if (piece.book) Material.ENCHANTED_BOOK else type)
    val meta = item.itemMeta ?: return item
    piece.enchantments.forEach { (key, level) ->
      val enchantment = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(key)) ?: return@forEach
      if (meta is EnchantmentStorageMeta) meta.addStoredEnchant(enchantment, level, true) else meta.addEnchant(enchantment, level, true)
    }
    item.itemMeta = meta
    return item
  }

  /** Where a source is, as a player looks for it: “hand”, “hotbar 3”, or “inventory row 1 column 6”. */
  fun label(source: Source): String = when (val slot = source.slot) {
    null -> "hand"
    in 0..8 -> "hotbar ${slot + 1}"
    else -> "inventory row ${(slot - 9) / 9 + 1} column ${(slot - 9) % 9 + 1}"
  }
}
