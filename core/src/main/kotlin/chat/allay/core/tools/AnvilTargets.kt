package chat.allay.core.tools

/**
 * Which item an anvil plan works on. The player rarely holds it: they ask about “my sword” while it lies in the
 * inventory, or about “these books” without naming an item at all. Slots go as the game numbers the player's
 * inventory on every platform: 0–8 the hotbar, 9–35 the rest, 36–39 boots to helmet, 40 the offhand.
 */
object AnvilTargets {
  const val OFFHAND = 40
  val ARMOR = 36..39
  val ALL = 0..OFFHAND

  /** Where a slot is, as a player looks for it: “hand”, “hotbar 3”, “inventory row 1 column 6”, “helmet”. */
  fun label(slot: Int, hand: Int): String = when (slot) {
    hand -> "hand"
    in 0..8 -> "hotbar ${slot + 1}"
    in 9..35 -> "inventory row ${(slot - 9) / 9 + 1} column ${(slot - 9) % 9 + 1}"
    36 -> "boots"
    37 -> "leggings"
    38 -> "chestplate"
    39 -> "helmet"
    OFFHAND -> "offhand"
    else -> "slot $slot"
  }

  /** An item the player has that the anvil could improve: [gain] is how many enchantment levels its best plan adds. */
  data class Option(val slot: Int, val id: String, val gain: Int)

  sealed interface Pick {
    data class One(val slot: Int) : Pick

    /** Several items gain about as much: the model asks which, best first. */
    data class Choose(val options: List<Option>) : Pick

    data object None : Pick
  }

  /**
   * The item to plan for when the player named none: the one in hand when the anvil improves it, else the only item it
   * improves; several, and the player picks.
   */
  fun pick(options: List<Option>, hand: Int): Pick {
    val useful = options.filter { it.gain > 0 }.sortedWith(compareByDescending<Option> { it.gain }.thenBy { it.slot })
    return when {
      useful.isEmpty() -> Pick.None
      useful.any { it.slot == hand } -> Pick.One(hand)
      useful.size == 1 -> Pick.One(useful.single().slot)
      else -> Pick.Choose(useful.take(MAX_CHOICES))
    }
  }

  /** The best of several slots holding what the player named (“the diamond sword”), or a choice when they tie. */
  fun named(options: List<Option>, hand: Int): Pick {
    if (options.isEmpty()) return Pick.None
    options.firstOrNull { it.slot == hand }?.let { return Pick.One(it.slot) }
    if (options.size == 1) return Pick.One(options.single().slot)
    val best = options.maxOf { it.gain }
    val top = options.filter { it.gain == best }
    return if (top.size == 1) Pick.One(top.single().slot) else Pick.Choose(top.take(MAX_CHOICES))
  }

  private const val MAX_CHOICES = 6

  /**
   * Which of the player's item ids [name] means: the exact id it resolves to, the ids holding it or its singular
   * (`sword` finds `diamond_sword`), or the ids the catalog's names in any language give ([named]: «меч»).
   */
  fun matching(name: String, have: Set<String>, exact: String?, named: (String) -> Set<String>): Set<String> {
    exact?.takeIf { it in have }?.let { return setOf(it) }
    val forms = Names.forms(Names.normalize(name)).filter { it.length >= 3 }
    have.filter { id -> forms.any { it in id } }.toSet().ifEmpty { null }?.let { return it }
    return named(name).intersect(have)
  }

  /** The levels a plan adds to its item: what it ends with beyond what the item had. */
  fun gain(before: Map<String, Int>, after: Map<String, Int>): Int = after.entries.sumOf { (key, level) -> maxOf(0, level - (before[key] ?: 0)) }
}
