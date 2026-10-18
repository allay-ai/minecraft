package chat.allay.core.tools

/**
 * Pinned slots for `sort_inventory`: “sword first, water second, golden carrot in the last hotbar slot”. A pin names
 * an item id or a group; with several candidates the best takes the slot. Works on slot numbers so tests reach it.
 */
object Pins {
  /** Groups a pin may name instead of an id, matched by the game's ids. */
  val GROUPS: Map<String, (String) -> Boolean> = mapOf(
    "sword" to { id -> id.endsWith("_sword") },
    "pickaxe" to { id -> id.endsWith("_pickaxe") },
    "axe" to { id -> id.endsWith("_axe") && !id.endsWith("_pickaxe") },
    "shovel" to { id -> id.endsWith("_shovel") },
    "hoe" to { id -> id.endsWith("_hoe") },
    "spear" to { id -> id.endsWith("_spear") },
    "bow" to { id -> id == "bow" },
    "crossbow" to { id -> id == "crossbow" },
    "trident" to { id -> id == "trident" },
    "mace" to { id -> id == "mace" },
    "shield" to { id -> id == "shield" },
    "totem" to { id -> id == "totem_of_undying" },
    "torch" to { id -> id.endsWith("torch") && !id.startsWith("redstone") },
    "bucket" to { id -> id.endsWith("bucket") },
    "pearl" to { id -> id == "ender_pearl" },
    "rocket" to { id -> id == "firework_rocket" },
    "arrow" to { id -> id.endsWith("arrow") },
    "block" to { _ -> false },
    "food" to { _ -> false },
  )

  /** Tiers of tool and armor materials, worst to best, so “the best pickaxe” means something. */
  private val tiers = listOf("wooden", "golden", "stone", "copper", "iron", "diamond", "netherite")

  /** One pin: [slot] is 0–8 for the hotbar or [OFFHAND]; [item] an id or a key of [GROUPS]. */
  data class Pin(val slot: Int, val item: String)

  const val OFFHAND = 40

  /** How good an item is for a pin: material tier, then enchantment levels, then the durability left; food by what it fills. */
  fun score(item: ItemData): Double {
    item.food?.let { return it.nutrition + it.saturation }
    val tier = tiers.indexOfFirst { item.id.startsWith("${it}_") } + 1
    val enchants = item.enchantments.values.sum()
    val wear = if (item.maxDamage > 0) 1.0 - item.damage.toDouble() / item.maxDamage else 1.0
    return tier * 100.0 + enchants + wear
  }

  fun matches(pin: String, item: ItemData, block: (ItemData) -> Boolean): Boolean = when (pin) {
    "food" -> item.food != null
    "block" -> block(item)
    else -> GROUPS[pin]?.invoke(item.id) ?: (item.id == pin)
  }

  /**
   * Which slot each pin takes its item from: for each pin in order, the best candidate in [items] (slot to item) that
   * no earlier pin took. A pin nothing matches is left out.
   */
  fun pick(pins: List<Pin>, items: Map<Int, ItemData>, block: (ItemData) -> Boolean = { false }): Map<Int, Int> {
    val taken = mutableSetOf<Int>()
    val result = LinkedHashMap<Int, Int>()
    for (pin in pins) {
      val best = items.filter { (slot, item) -> slot !in taken && matches(pin.item, item, block) }
        .maxWithOrNull(compareBy<Map.Entry<Int, ItemData>> { score(it.value) }.thenBy { if (it.key == pin.slot) 1 else 0 })?.key ?: continue
      taken += best
      result[pin.slot] = best
    }
    return result
  }

  /** A pin's item as the model wrote it: a group, or an id without the namespace. */
  fun normalize(item: String): String = item.trim().lowercase().removePrefix("minecraft:").replace(' ', '_')
}
