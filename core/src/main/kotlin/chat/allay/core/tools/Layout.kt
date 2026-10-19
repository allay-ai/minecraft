package chat.allay.core.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * How a player wants a grid of slots laid out, shared by the inventory and chests: “iron first”, “food in the last
 * row”, “bundles in the right column”, “by category, each kind on its own row”, “don't touch the books”. Works on
 * cells of a grid nine wide, without touching Bukkit, so tests reach it.
 */
object Layout {
  enum class By { NAME, CATEGORY, COUNT }

  /** Items that go to [where]: `row:N`, `row:last`, `column:N`, `column:last`, `slots:A-B`, `start`, or `end`. */
  data class Zone(val items: List<String>, val where: String)

  data class Spec(
    /** What comes first, in this order, every stack of each; the rest follows. */
    val order: List<String> = emptyList(),
    val zones: List<Zone> = emptyList(),
    val by: By = By.NAME,
    /** Each category starts a new row while there's room for it. */
    val rows: Boolean = false,
    /** What stays in its slot. */
    val keep: List<String> = emptyList(),
    /** Only merge stacks; nothing else moves. */
    val mergeOnly: Boolean = false,
  ) {
    companion object { val DEFAULT = Spec() }
  }

  /** The new [cells] of the grid, the stacks that found no room ([extra]), and how many stacks merging saved. */
  class Laid<T>(val cells: List<T?>, val extra: List<T>, val merged: Int)

  private const val WIDTH = 9

  /** Words for resources, each matching its raw form, ingot, nugget, block, and ore. */
  private val MATERIALS = mapOf(
    "iron" to setOf("iron"), "gold" to setOf("gold"), "copper" to setOf("copper"), "diamond" to setOf("diamond"),
    "emerald" to setOf("emerald"), "netherite" to setOf("netherite", "debris"), "coal" to setOf("coal", "charcoal"),
    "redstone" to setOf("redstone"), "lapis" to setOf("lapis"), "quartz" to setOf("quartz"), "amethyst" to setOf("amethyst"),
  )

  /**
   * Whether [item] answers [selector]: an id, a group of [Pins.GROUPS], a material of [MATERIALS], a chest category,
   * `enchanted`, `named`, `damaged`, or a potion as `potion:healing`. [block] tells blocks, which the platform knows.
   * `potion:healing` finds splash and lingering healing too, as “my healing potions” means them all; pins stay strict.
   */
  fun matches(selector: String, item: ItemData, block: (ItemData) -> Boolean = { false }): Boolean {
    val wanted = Pins.normalize(selector)
    if (wanted.startsWith("potion:")) {
      val potion = wanted.removePrefix("potion:")
      return listOf("potion", "splash_potion", "lingering_potion").any { Pins.matches("$it:$potion", item, block) }
    }
    MATERIALS[wanted]?.let { words ->
      val parts = item.id.split('_')
      val resource = Storage.category(item.id, item.food != null) == Storage.Category.ORES || item.id == "redstone" || item.id == "redstone_block"
      return resource && parts.any { it in words }
    }
    return when (wanted) {
      "enchanted" -> item.enchantments.isNotEmpty() || item.storedEnchantments.isNotEmpty()
      "named" -> item.name != null
      "damaged" -> item.maxDamage > 0 && item.damage > 0
      in Pins.GROUPS.keys -> Pins.matches(wanted, item, block)
      else -> Storage.Category.parse(wanted)?.let { Storage.category(item.id, item.food != null) == it } ?: Pins.matches(wanted, item, block)
    }
  }

  /** A tool's `layout` argument; what it leaves out keeps the defaults, [by] among them. */
  fun parse(json: JsonObject?, by: By): Spec {
    if (json == null) return Spec(by = by)
    fun words(value: Any?) = (value as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null } }.orEmpty()
    return Spec(
      order = words(json["order"]),
      zones = (json["zones"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.map { zone ->
        Zone(words(zone["items"]).ifEmpty { throw ToolError("each zone needs items") }, zone.string("where") ?: throw ToolError("each zone needs where"))
      }.orEmpty(),
      by = json.string("by")?.let { name -> By.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: throw ToolError("by is name, category, or count, not $name") } ?: by,
      rows = json.bool("rows") == true,
      keep = words(json["keep"]),
      mergeOnly = json.bool("merge_only") == true,
    )
  }

  /** The cells of a [size]-cell grid that [where] names, or null for `start`, `end`, and anything it can't read. */
  fun cells(where: String, size: Int): List<Int>? {
    val rows = size / WIDTH
    val (kind, value) = where.trim().lowercase().split(':', limit = 2).let { it[0] to it.getOrNull(1)?.trim() }
    fun number(max: Int) = if (value == "last") max else value?.toIntOrNull()?.takeIf { it in 1..max }
    return when (kind) {
      "row" -> number(rows)?.let { row -> (0 until WIDTH).map { (row - 1) * WIDTH + it } }
      "column" -> number(WIDTH)?.let { column -> (0 until rows).map { it * WIDTH + column - 1 } }
      "slots" -> value?.split('-')?.mapNotNull { it.trim().toIntOrNull() }?.let { ends ->
        val (from, to) = (ends.getOrNull(0) ?: return null) to (ends.getOrNull(1) ?: ends[0])
        if (from !in 1..size || to !in from..size) null else (from - 1 until to).toList()
      }
      else -> null
    }
  }

  /** Why [spec] can't be used, or null when it can: zones that name no cells. */
  fun problem(spec: Spec, size: Int): String? = spec.zones.firstOrNull { zone ->
    zone.where.trim().lowercase() !in setOf("start", "end") && cells(zone.where, size) == null
  }?.let { "a zone's where is row:1–${size / WIDTH}, row:last, column:1–9, column:last, slots:A-B within 1–$size, start, or end; not ${it.where}" }

  /**
   * Lays [cells] out by [spec], with [extra] stacks from outside the grid joining them. [blocked] cells stay as they
   * are and take nothing, as do cells holding what [Spec.keep] names. Null when items are overstacked.
   */
  fun <T> lay(
    cells: List<T?>,
    extra: List<T>,
    blocked: Set<Int>,
    spec: Spec,
    stacks: Storage.Stacks<T>,
    block: (ItemData) -> Boolean = { false },
  ): Laid<T>? {
    val data = stacks::data
    val answers = { selectors: List<String>, item: T -> selectors.any { matches(it, data(item), block) } }
    val fixed = blocked + cells.indices.filter { cells[it] != null && answers(spec.keep, cells[it]!!) }
    if (spec.mergeOnly) return merge(cells, extra, fixed, stacks)

    val starts = spec.zones.filter { it.where.trim().lowercase() == "start" }.flatMap { it.items }
    val ends = spec.zones.filter { it.where.trim().lowercase() == "end" }.flatMap { it.items }
    // Where an item stands in the flow: start zones, then the order, then the rest, then end zones.
    val rank = { item: T ->
      val first = starts.indexOfFirst { matches(it, data(item), block) }
      val listed = spec.order.indexOfFirst { matches(it, data(item), block) }
      val last = ends.indexOfFirst { matches(it, data(item), block) }
      when {
        first >= 0 -> first
        listed >= 0 -> starts.size + listed
        last >= 0 -> starts.size + spec.order.size + 1 + last
        else -> starts.size + spec.order.size
      }
    }
    val kind = { item: T -> Storage.category(stacks.id(item), stacks.edible(item)).ordinal }
    val pool = cells.indices.filter { it !in fixed }.mapNotNull { cells[it] } + extra
    val totals = pool.groupBy(stacks::id).mapValues { (_, list) -> list.sumOf(stacks::amount) }
    var order = compareBy<T>(rank)
    if (spec.by == By.CATEGORY || spec.rows) order = order.thenBy(kind)
    if (spec.by == By.COUNT) order = order.thenByDescending { totals.getValue(stacks.id(it)) }
    order = order.thenBy(stacks::id).thenByDescending(stacks::amount)
    val sorted = Sorter.sort(pool, stacks::amount, stacks::max, stacks::similar, order, stacks::withAmount) ?: return null
    val queue = sorted.slots.filterNotNull().toMutableList()

    val result = cells.toMutableList()
    cells.indices.filter { it !in fixed }.forEach { result[it] = null }
    // Zones claim their cells in the order given and take their stacks first; what doesn't fit flows on with the rest.
    val claimed = mutableSetOf<Int>()
    val spare = mutableListOf<Int>()
    for (zone in spec.zones) {
      val own = cells(zone.where, cells.size)?.filter { it !in fixed && it !in claimed } ?: continue
      claimed += own
      // By position: equal stacks are equal, and removing by value would lose the ones that don't fit.
      val picks = queue.indices.filter { answers(zone.items, queue[it]) }.take(own.size)
      val taken = picks.map { queue[it] }
      picks.asReversed().forEach { queue.removeAt(it) }
      taken.forEachIndexed { index, stack -> result[own[index]] = stack }
      spare += own.drop(taken.size)
    }
    // The flow fills the free cells in order; a zone's empty cells come last, so a zone stays its items' place.
    val flow = cells.indices.filter { it !in fixed && it !in claimed }
    val room = (flow + spare.sorted()).toMutableList()
    var previous: Int? = null
    while (queue.isNotEmpty() && room.isNotEmpty()) {
      val stack = queue.first()
      val now = kind(stack)
      if (spec.rows && previous != null && now != previous && room.first() % WIDTH != 0) {
        // A new kind takes a new row, while the skip leaves room for everything still waiting.
        val skip = room.indexOfFirst { it % WIDTH == 0 }
        if (skip > 0 && room.size - skip >= queue.size) repeat(skip) { room.removeAt(0) }
      }
      result[room.removeAt(0)] = queue.removeAt(0)
      previous = now
    }
    // Nothing may vanish or appear: each item's total stays what it was, or nothing moves.
    val after = (cells.indices.filter { it !in fixed }.mapNotNull { result[it] } + queue).groupBy(stacks::id).mapValues { (_, list) -> list.sumOf(stacks::amount) }
    if (after != totals) return null
    return Laid(result, queue, sorted.merged)
  }

  /** Merges stacks in place: each pours into the first like stack with room, and [extra] into those, then empty cells. */
  private fun <T> merge(cells: List<T?>, extra: List<T>, fixed: Set<Int>, stacks: Storage.Stacks<T>): Laid<T> {
    val result = cells.toMutableList()
    val before = cells.indices.count { it !in fixed && cells[it] != null } + extra.size
    fun pour(item: T, until: Int): T? {
      var amount = stacks.amount(item)
      for (index in 0 until until) {
        if (amount == 0) break
        val there = result[index] ?: continue
        if (index in fixed || !stacks.similar(there, item) || stacks.amount(there) >= stacks.max(there)) continue
        val moved = minOf(amount, stacks.max(there) - stacks.amount(there))
        result[index] = stacks.withAmount(there, stacks.amount(there) + moved)
        amount -= moved
      }
      return if (amount > 0) stacks.withAmount(item, amount) else null
    }
    for (index in cells.indices) {
      if (index in fixed) continue
      val item = result[index] ?: continue
      result[index] = pour(item, index)
    }
    val left = extra.mapNotNull { pour(it, cells.size) }.toMutableList()
    for (index in cells.indices) if (left.isNotEmpty() && index !in fixed && result[index] == null) result[index] = left.removeAt(0)
    val after = cells.indices.count { it !in fixed && result[it] != null } + left.size
    return Laid(result, left, before - after)
  }
}
