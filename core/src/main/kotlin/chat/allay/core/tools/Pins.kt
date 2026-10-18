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

  /** Bottles a pin may name with their potion, as `potion:healing` or `splash_potion:strong_healing`; tipped arrows too. */
  private val BOTTLES = setOf("potion", "splash_potion", "lingering_potion", "tipped_arrow")

  /** One pin: [slot] is 0–8 for the hotbar or [OFFHAND]; [item] an id, a key of [GROUPS], or `bottle:potion`. */
  data class Pin(val slot: Int, val item: String)

  const val OFFHAND = 40

  /** How good an item is for a pin: material tier, then enchantment levels, then the durability left; food by what it fills. */
  fun score(item: ItemData): Double {
    // A potion's strong form beats its long one, which beats the plain one.
    item.potion?.base?.let { base -> return when { base.startsWith("strong_") -> 2.0; base.startsWith("long_") -> 1.0; else -> 0.0 } }
    item.food?.let { return it.nutrition + it.saturation }
    val tier = tiers.indexOfFirst { item.id.startsWith("${it}_") } + 1
    val enchants = item.enchantments.values.sum()
    val wear = if (item.maxDamage > 0) 1.0 - item.damage.toDouble() / item.maxDamage else 1.0
    return tier * 100.0 + enchants + wear
  }

  fun matches(pin: String, item: ItemData, block: (ItemData) -> Boolean): Boolean = when {
    ':' in pin -> {
      val (bottle, potion) = pin.split(':', limit = 2)
      // healing finds healing, strong_healing, and long_healing alike; strong_healing only itself.
      val base = item.potion?.base
      item.id == bottle && bottle in BOTTLES && base != null && (base == potion || base == "strong_$potion" || base == "long_$potion")
    }
    else -> plain(pin, item, block)
  }

  private fun plain(pin: String, item: ItemData, block: (ItemData) -> Boolean): Boolean = when (pin) {
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
        // Equal scores, as blocks have, go to the bigger stack: “64 blocks” means a full one.
        .maxWithOrNull(compareBy<Map.Entry<Int, ItemData>> { score(it.value) }.thenBy { it.value.count }.thenBy { if (it.key == pin.slot) 1 else 0 })?.key ?: continue
      taken += best
      result[pin.slot] = best
    }
    return result
  }

  /** What a player holds in hand, in the order a sorted hotbar keeps it: weapons, tools, then what's used on the go. */
  private val HAND: List<(ItemData) -> Boolean> = listOf<String>("sword", "axe", "mace", "trident", "spear", "bow", "crossbow", "pickaxe", "shovel", "hoe")
    .map { group -> { item: ItemData -> GROUPS.getValue(group)(item.id) } } + listOf(
    { item -> item.id in setOf("shears", "flint_and_steel", "fishing_rod", "brush", "spyglass") },
    { item -> GROUPS.getValue("bucket")(item.id) },
    { item -> GROUPS.getValue("torch")(item.id) },
    { item -> item.id in setOf("ender_pearl", "wind_charge", "firework_rocket") },
    { item -> item.id in setOf("totem_of_undying", "shield") },
    { item -> item.id in setOf("potion", "splash_potion", "lingering_potion") },
    { item -> item.food != null },
  )

  /** Where [item] stands in [HAND], or null when it isn't held in hand. */
  fun hand(item: ItemData): Int? = HAND.indexOfFirst { it(item) }.takeIf { it >= 0 }

  class Arranged<T>(val slots: Map<Int, T?>, val merged: Int)

  /**
   * A whole inventory sort: what every slot it touches ends up holding. [items] are slots 0–35 and [OFFHAND] (armor
   * never moves), [picked] comes from [pick]. Pinned slots take their items; the main inventory (9–35) takes all the
   * rest, merged and sorted. With [hotbar] the slots no pin takes are emptied into it; without pins the hotbar keeps
   * what it holds that's held in hand, sorted by [HAND] from the first slot. A hotbar slot left empty takes only what
   * the main inventory can't fit, so a sort never fills the hotbar with arrows and bundles. With [offhand], the offhand
   * keeps a hand item and gives up anything else. Null when the items wouldn't fit back.
   */
  fun <T> arrange(
    items: Map<Int, T>,
    picked: Map<Int, Int>,
    hotbar: Boolean,
    offhand: Boolean,
    data: (T) -> ItemData,
    amount: (T) -> Int,
    maxStack: (T) -> Int,
    similar: (T, T) -> Boolean,
    withAmount: (T, Int) -> T,
  ): Arranged<T>? {
    val result = HashMap<Int, T?>()
    // What isn't pinned away from its slot.
    val left = items.filterKeys { it !in picked.values }
    picked.forEach { (slot, from) -> result[slot] = items.getValue(from) }
    picked.values.forEach { if (it !in picked) result[it] = null }
    val pool = mutableListOf<T>()
    val held = mutableListOf<T>()
    // Hotbar slots no pin takes and nothing stays in, in order.
    val open = mutableListOf<Int>()
    for (slot in 0..8) {
      if (slot in picked) { left[slot]?.let(pool::add); continue }
      val item = left[slot]
      when {
        !hotbar -> if (item == null) open += slot
        picked.isEmpty() && item != null && hand(data(item)) != null -> { held += item; open += slot }
        else -> { item?.let(pool::add); open += slot }
      }
    }
    left[OFFHAND]?.let { item ->
      if (OFFHAND in picked) pool += item else if (offhand && hand(data(item)) == null) { pool += item; result[OFFHAND] = null }
    }
    (9..35).forEach { slot -> if (slot !in picked) left[slot]?.let(pool::add) }
    val order = compareBy<T> { data(it).id }.thenByDescending(amount)
    // Held items come first in the hotbar, weapons leading; the slots after them take only what the main inventory can't.
    val hand = Sorter.sort(held, amount, maxStack, similar, compareBy<T> { hand(data(it)) }.then(order), withAmount) ?: return null
    val kept = hand.slots.filterNotNull()
    kept.forEachIndexed { index, stack -> result[open[index]] = stack }
    val room = (9..35).filter { it !in picked } + open.drop(kept.size)
    val sorted = Sorter.sort(pool + List((room.size - pool.size).coerceAtLeast(0)) { null }, amount, maxStack, similar, order, withAmount)
      ?.takeIf { it.slots.drop(room.size).all { slot -> slot == null } } ?: return null
    room.forEachIndexed { index, slot -> result[slot] = sorted.slots[index] }
    return Arranged(result, sorted.merged + hand.merged)
  }

  /** A pin's item as the model wrote it: a group, or an id without the namespace. */
  fun normalize(item: String): String = item.trim().lowercase().replace("minecraft:", "").replace(' ', '_')
}
