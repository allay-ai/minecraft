package chat.allay.spigot.tools

import chat.allay.core.tools.AnvilTargets
import chat.allay.core.tools.Mechanics
import chat.allay.core.tools.Names
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
 * Merging what the player has on an anvil: an item from anywhere in the inventory (the hand, the armor, the offhand),
 * enchanted books, and other items of its kind, planned by [Mechanics.mergePlan]. Shared by `get_anvil_plan` and its
 * button. Slots are [AnvilTargets]'.
 */
object Anvils {
  /** One thing the plan may use, by its slot. */
  class Source(val slot: Int, val item: ItemStack, val piece: Mechanics.AnvilPiece)

  /** The plan; the first source is the item it works on. */
  class Planned(val sources: List<Source>, val plan: Mechanics.MergePlan, val hand: Int) {
    val target get() = sources.first()
  }

  /** Pieces beyond the item worked on; the planner tries every order, so it stays small. */
  private const val MAX_EXTRA = 7

  /** Items weighed when the player names none: each costs a plan. */
  private const val MAX_CANDIDATES = 8

  /** An enchantment by its id, a data pack's own too (`mypack:frost`). */
  fun enchantment(key: String): Enchantment? = NamespacedKey.fromString(key)?.let(Registry.ENCHANTMENT::get)

  /** The game's rules for the kind of item worked on, from Bukkit's registry. */
  class Rules(type: Material) : Mechanics.Enchanting {
    private val sample = ItemStack(type)
    override fun maxLevel(key: String) = enchantment(key)?.maxLevel ?: 1
    override fun fits(key: String) = enchantment(key)?.canEnchantItem(sample) ?: false
    override fun compatible(a: String, b: String): Boolean {
      val first = enchantment(a) ?: return true
      val second = enchantment(b) ?: return true
      return !first.conflictsWith(second) && !second.conflictsWith(first)
    }
  }

  /** An enchantment's id as tools write it: the game's without its namespace, a data pack's with it. */
  fun id(enchantment: Enchantment): String = enchantment.key.let { if (it.namespace == NamespacedKey.MINECRAFT) it.key else it.toString() }

  fun piece(item: ItemStack): Mechanics.AnvilPiece {
    val meta = item.itemMeta
    val book = meta is EnchantmentStorageMeta
    val enchantments = if (meta is EnchantmentStorageMeta) meta.storedEnchants else item.enchantments
    return Mechanics.AnvilPiece(book, enchantments.mapKeys { id(it.key) }, (meta as? Repairable)?.repairCost ?: 0)
  }

  private fun isBook(item: ItemStack) = item.type == Material.ENCHANTED_BOOK || item.type == Material.BOOK

  private fun item(player: Player, slot: Int): ItemStack? = player.inventory.getItem(slot)?.takeUnless { it.type.isAir }

  /**
   * The plan for the item in [target] (a slot 0–40). [slots] picks inventory slots (0–35) to use; without them, every
   * enchanted book with something that fits and every enchanted item of the same kind is a candidate, the richest first.
   */
  fun plan(player: Player, slots: List<Int>? = null, target: Int = player.inventory.heldItemSlot): Planned? {
    val hand = player.inventory.heldItemSlot
    val held = item(player, target) ?: throw ToolError(if (target == hand) "the player holds nothing; name the item to enchant, or leave item out to let the tool find it" else "${AnvilTargets.label(target, hand)} is empty")
    if (isBook(held)) throw ToolError("${AnvilTargets.label(target, hand)} holds a book; name the item to enchant, not a book")
    val rules = Rules(held.type)
    val candidates = (slots ?: ((0..35) + AnvilTargets.OFFHAND)).distinct().filter { it in AnvilTargets.ALL && it != target }.mapNotNull { slot ->
      val item = item(player, slot) ?: return@mapNotNull null
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
    val sources = listOf(Source(target, held, piece(held))) + candidates
    val plan = Mechanics.mergePlan(sources.map { it.piece }, rules) ?: return null
    return Planned(sources, plan, hand)
  }

  /** How much [planned] adds to its item. */
  private fun gain(planned: Planned?) = planned?.let { AnvilTargets.gain(it.target.piece.enchantments, it.plan.result.enchantments) } ?: 0

  /**
   * The item to work on: the [slot] named, the slots holding what [item] names (`hand` is the hand; an id, a part of
   * one like `sword`, or a name [named] finds in any language), or, with neither, the one the player's books improve
   * ([AnvilTargets.pick]).
   */
  fun target(player: Player, item: String?, slot: Int?, slots: List<Int>?, named: (String) -> Set<String> = { emptySet() }): AnvilTargets.Pick {
    val hand = player.inventory.heldItemSlot
    if (slot != null) {
      if (slot !in AnvilTargets.ALL) throw ToolError("slot must be 0–40: 0–35 the inventory, 36–39 boots to helmet, 40 the offhand")
      return AnvilTargets.Pick.One(slot)
    }
    if (item != null && Names.normalize(item) == "hand") {
      // An empty hand means the tool finds the item, as if none were named.
      return if (item(player, hand)?.takeUnless(::isBook) != null) AnvilTargets.Pick.One(hand) else target(player, null, null, slots, named)
    }
    val all = AnvilTargets.ALL.mapNotNull { at -> item(player, at)?.takeUnless(::isBook)?.let { at to it } }
    if (item != null) {
      val ids = AnvilTargets.matching(item, all.map { it.second.type.key.key }.toSet(), runCatching { InteractTools.material(item).key.key }.getOrNull(), named)
      val options = all.filter { it.second.type.key.key in ids }
      if (options.isEmpty()) throw ToolError("the player has nothing called $item in the inventory, armor, or offhand; leave item out and the tool finds what their books improve")
      return AnvilTargets.named(options.map { (at, stack) -> AnvilTargets.Option(at, stack.type.key.key, gain(runCatching { plan(player, slots, at) }.getOrNull())) }, hand)
    }
    val options = all
    // Only what the books could reach: an item no book fits and with no twin costs no plan.
    val books = (0..35).mapNotNull { item(player, it) }.map(::piece).filter { it.book }.flatMap { it.enchantments.keys }.toSet()
    val weighed = options.filter { (_, stack) -> books.any { Rules(stack.type).fits(it) } || options.count { it.second.type == stack.type } > 1 }
      .sortedBy { (at, _) -> if (at == hand) -1 else at }.take(MAX_CANDIDATES)
    return AnvilTargets.pick(weighed.map { (at, stack) -> AnvilTargets.Option(at, stack.type.key.key, gain(runCatching { plan(player, slots, at) }.getOrNull())) }, hand)
  }

  /** A piece as the window draws it: the item of this kind or a book, with the enchantments the step gives it. */
  fun render(type: Material, piece: Mechanics.AnvilPiece): ItemStack {
    val item = ItemStack(if (piece.book) Material.ENCHANTED_BOOK else type)
    val meta = item.itemMeta ?: return item
    piece.enchantments.forEach { (key, level) ->
      val enchantment = enchantment(key) ?: return@forEach
      if (meta is EnchantmentStorageMeta) meta.addStoredEnchant(enchantment, level, true) else meta.addEnchant(enchantment, level, true)
    }
    item.itemMeta = meta
    return item
  }

  /** Where a source is, as a player looks for it: “hand”, “hotbar 3”, “inventory row 1 column 6”, “helmet”. */
  fun label(source: Source, hand: Int): String = AnvilTargets.label(source.slot, hand)
}
