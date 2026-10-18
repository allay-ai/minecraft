package chat.allay.core.tools

/**
 * Laying chests out, without touching Bukkit so tests reach it. Everything is planned in memory and checked:
 * each item's total stays what it was, and a plan that doesn't fit is no plan. The platform writes it in one tick.
 */
object Storage {
  /** What a chest holds; frequent ones get the chests nearest the player. */
  enum class Category(val wire: String) {
    BUILDING("building"), WOOD("wood"), STONE("stone"), ORES("ores"), REDSTONE("redstone"), FOOD("food"), FARMING("farming"),
    MOB_DROPS("mob_drops"), TOOLS("tools"), POTIONS("potions"), OTHER("other");

    companion object {
      fun parse(name: String?): Category? = name?.trim()?.lowercase()?.let { wanted -> entries.firstOrNull { it.wire == wanted } }
    }
  }

  /** How a platform's items behave, so the planner can pool, split, and order them. */
  interface Stacks<T> {
    fun id(item: T): String
    fun amount(item: T): Int
    fun max(item: T): Int
    fun similar(a: T, b: T): Boolean
    fun withAmount(item: T, amount: Int): T
    /** Food the player can eat; the platform knows. */
    fun edible(item: T): Boolean = false
  }

  /** A chest (a double chest is one) with its slots; [assign] is what belongs in it, if anything. */
  class Chest<T>(val key: String, val slots: List<T?>, val assign: Assign? = null)

  /** What the player, a sign, or an item frame says belongs in a chest: these items first, then the category. */
  data class Assign(val category: Category? = null, val items: Set<String> = emptySet())

  /** The new slots of each chest, by key, and what each now holds. */
  class Plan<T>(val chests: Map<String, List<T?>>, val labels: Map<String, Category?>, val left: Map<String, Int> = emptyMap())

  private val woods = listOf("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "pale_oak", "bamboo", "crimson", "warped")
  private val ores = setOf("coal", "charcoal", "raw_iron", "raw_gold", "raw_copper", "iron_ingot", "gold_ingot", "copper_ingot", "netherite_ingot", "netherite_scrap",
    "diamond", "emerald", "lapis_lazuli", "quartz", "amethyst_shard", "iron_nugget", "gold_nugget", "copper_nugget", "ancient_debris", "resin_clump", "resin_brick")
  private val redstone = setOf("redstone", "redstone_torch", "repeater", "comparator", "observer", "piston", "sticky_piston", "hopper", "dropper", "dispenser",
    "lever", "daylight_detector", "target", "tripwire_hook", "note_block", "redstone_lamp", "rail", "powered_rail", "detector_rail", "activator_rail", "tnt",
    "slime_block", "honey_block", "crafter", "sculk_sensor", "calibrated_sculk_sensor", "lightning_rod", "redstone_block")
  private val farming = setOf("wheat", "wheat_seeds", "beetroot_seeds", "melon_seeds", "pumpkin_seeds", "torchflower_seeds", "pitcher_pod", "sugar_cane", "cactus",
    "bamboo", "kelp", "bone_meal", "pumpkin", "melon", "hay_block", "cocoa_beans", "nether_wart", "sweet_berries", "glow_berries", "egg", "blue_egg", "brown_egg",
    "leather", "feather", "ink_sac", "glow_ink_sac", "wool", "string", "honeycomb", "turtle_scute", "armadillo_scute", "sapling", "moss_block", "dirt", "farmland")
  private val drops = setOf("rotten_flesh", "bone", "spider_eye", "gunpowder", "ender_pearl", "blaze_rod", "slime_ball", "phantom_membrane", "ghast_tear",
    "magma_cream", "shulker_shell", "prismarine_shard", "prismarine_crystals", "breeze_rod", "nautilus_shell", "echo_shard", "arrow", "nether_star", "rabbit_hide",
    "rabbit_foot", "fermented_spider_eye", "heavy_core", "dragon_breath", "blaze_powder", "ender_eye")
  private val potions = setOf("potion", "splash_potion", "lingering_potion", "glass_bottle", "experience_bottle", "enchanted_book", "brewing_stand", "cauldron",
    "glistering_melon_slice", "golden_carrot", "rabbit_foot", "lapis_block", "book", "writable_book", "written_book", "bookshelf", "chiseled_bookshelf")
  private val toolSuffixes = listOf("_sword", "_pickaxe", "_axe", "_shovel", "_hoe", "_helmet", "_chestplate", "_leggings", "_boots", "_spear")
  private val tools = setOf("bow", "crossbow", "trident", "mace", "shield", "fishing_rod", "flint_and_steel", "shears", "elytra", "brush", "spyglass", "compass",
    "recovery_compass", "clock", "lead", "name_tag", "saddle", "totem_of_undying", "turtle_helmet", "wolf_armor", "carrot_on_a_stick", "warped_fungus_on_a_stick",
    "bucket", "water_bucket", "lava_bucket", "milk_bucket", "powder_snow_bucket", "map", "filled_map", "firework_rocket", "wind_charge")
  private val stones = listOf("stone", "cobblestone", "deepslate", "andesite", "diorite", "granite", "tuff", "calcite", "blackstone", "basalt", "netherrack",
    "end_stone", "sandstone", "gravel", "sand", "flint", "clay", "brick", "terracotta", "obsidian", "dripstone", "mud", "prismarine", "purpur", "quartz_block")

  /** The category of an item id, by the game's naming. [edible] is the platform's word on food. */
  fun category(id: String, edible: Boolean = false): Category = when {
    edible && id !in setOf("spider_eye", "rotten_flesh", "golden_carrot", "glistering_melon_slice") -> Category.FOOD
    id in potions || id.endsWith("_potion") -> Category.POTIONS
    id in tools || toolSuffixes.any(id::endsWith) || id.endsWith("_horse_armor") || id.endsWith("_harness") -> Category.TOOLS
    id in redstone || id.endsWith("_pressure_plate") || id.endsWith("_button") || id.endsWith("_trapdoor") || id.endsWith("_door") -> Category.REDSTONE
    id in ores || id.endsWith("_ore") || (id.endsWith("_block") && ores.any { id == "${it}_block" || id == "${it.removeSuffix("_ingot")}_block" }) -> Category.ORES
    id in drops -> Category.MOB_DROPS
    id in farming || id.endsWith("_seeds") || id.endsWith("_sapling") || id.endsWith("_wool") || id.endsWith("_leaves") -> Category.FARMING
    woods.any { id.startsWith("${it}_") } || id.endsWith("_log") || id.endsWith("_planks") || id.endsWith("_wood") || id.endsWith("_stem") || id.endsWith("_hyphae") || id == "stick" -> Category.WOOD
    stones.any { id == it || id.startsWith("${it}_") || id.endsWith("_$it") || id.contains("_${it}_") } || id.startsWith("cobbled_") || id.startsWith("polished_") -> Category.STONE
    id.endsWith("_glass") || id == "glass" || id.endsWith("_glass_pane") || id.endsWith("_concrete") || id.endsWith("_concrete_powder") || id.endsWith("_slab") ||
      id.endsWith("_stairs") || id.endsWith("_wall") || id.endsWith("_carpet") || id.endsWith("_bricks") || id.endsWith("_tiles") || id.endsWith("_block") -> Category.BUILDING
    else -> Category.OTHER
  }

  private class Pool<T>(val item: T, var total: Int)

  private fun <T> pool(items: Iterable<T?>, stacks: Stacks<T>): MutableList<Pool<T>> {
    val pools = mutableListOf<Pool<T>>()
    items.filterNotNull().forEach { item ->
      val same = pools.firstOrNull { stacks.similar(it.item, item) }
      if (same == null) pools += Pool(item, stacks.amount(item)) else same.total += stacks.amount(item)
    }
    return pools
  }

  private fun <T> split(pool: Pool<T>, stacks: Stacks<T>): List<T> {
    val max = stacks.max(pool.item).coerceAtLeast(1)
    return (0 until (pool.total + max - 1) / max).map { stacks.withAmount(pool.item, minOf(max, pool.total - it * max)) }
  }

  private fun <T> totals(chests: Iterable<List<T?>>, stacks: Stacks<T>): Map<String, Int> =
    chests.flatten().filterNotNull().groupBy { stacks.id(it) }.mapValues { (_, list) -> list.sumOf(stacks::amount) }

  /**
   * Groups everything in [chests], given nearest first: each item goes to a chest assigned to it, then to one of its
   * category, and the categories nobody assigned take the free chests in order, the most stacks first. A category
   * that doesn't fit flows into the next chest. Null when everything together doesn't fit.
   */
  fun <T> group(chests: List<Chest<T>>, stacks: Stacks<T>): Plan<T>? {
    val pools = pool(chests.flatMap { it.slots }, stacks)
    val category = { pool: Pool<T> -> category(stacks.id(pool.item), stacks.edible(pool.item)) }
    val free = chests.map { it.slots.size }.toMutableList()
    val placed = chests.map { mutableListOf<T>() }
    val labels = chests.map { it.assign?.category ?: it.assign?.items?.firstOrNull()?.let { id -> category(id) } }.toMutableList()

    fun put(index: Int, stack: T): Boolean {
      if (free[index] <= 0) return false
      placed[index] += stack
      free[index]--
      return true
    }

    // Each pool's stacks, placed in order of preference; what's left goes on to the next choice.
    val waiting = pools.associateWith { split(it, stacks).toMutableList() }.toMutableMap()
    fun fill(pool: Pool<T>, targets: List<Int>) {
      val queue = waiting[pool] ?: return
      for (index in targets) while (queue.isNotEmpty() && put(index, queue.first())) queue.removeAt(0)
    }

    // Items a chest names come first, then its category.
    pools.forEach { pool -> fill(pool, chests.indices.filter { stacks.id(pool.item) in (chests[it].assign?.items ?: emptySet()) }) }
    pools.forEach { pool -> fill(pool, chests.indices.filter { chests[it].assign?.category == category(pool) }) }

    // The rest by category, the biggest first, into chests nobody assigned, continuing into the next one.
    val unassigned = chests.indices.filter { chests[it].assign == null }.toMutableList()
    val byCategory = pools.filter { waiting[it]!!.isNotEmpty() }.groupBy(category)
      .entries.sortedByDescending { (_, list) -> list.sumOf { waiting[it]!!.size } }
    for ((kind, list) in byCategory) {
      val start = unassigned.firstOrNull { free[it] > 0 && (labels[it] == null || labels[it] == kind) } ?: break
      val order = unassigned.dropWhile { it != start }
      list.sortedBy { stacks.id(it.item) }.forEach { pool -> fill(pool, order) }
      order.filter { placed[it].isNotEmpty() && labels[it] == null }.forEach { labels[it] = kind }
    }

    // Whatever still waits squeezes into any chest with room, nearest first.
    pools.forEach { pool -> fill(pool, chests.indices.toList()) }
    if (waiting.values.any { it.isNotEmpty() }) return null

    val result = chests.indices.associate { index ->
      val sorted = placed[index].sortedWith(compareBy<T>({ category(stacks.id(it), stacks.edible(it)).ordinal }, { stacks.id(it) }).thenByDescending { stacks.amount(it) })
      chests[index].key to sorted + List(chests[index].slots.size - sorted.size) { null }
    }
    if (totals(result.values, stacks) != totals(chests.map { it.slots }, stacks)) return null
    return Plan(result, chests.indices.associate { chests[it].key to labels[it] })
  }

  /** Sorts each chest on its own, as [Sorter] does an inventory. Null when one doesn't fit back. */
  fun <T> sortEach(chests: List<Chest<T>>, stacks: Stacks<T>, order: Comparator<T>): Plan<T>? {
    val result = chests.associate { chest ->
      chest.key to (Sorter.sort(chest.slots, stacks::amount, stacks::max, stacks::similar, order, stacks::withAmount)?.slots ?: return null)
    }
    return Plan(result, chests.associate { it.key to it.assign?.category })
  }

  /**
   * Moves what [wanted] matches out of every chest but [targets] into the targets, merging into their stacks first.
   * What doesn't fit stays where it was and comes back in [Plan.left]; the rest of each chest stays put.
   */
  fun <T> move(chests: List<Chest<T>>, targets: Set<String>, wanted: (T) -> Boolean, stacks: Stacks<T>): Plan<T> {
    val slots = chests.associate { it.key to it.slots.toMutableList() }
    val left = mutableMapOf<String, Int>()
    for (source in chests.filter { it.key !in targets }) {
      val from = slots[source.key]!!
      for (index in from.indices) {
        val item = from[index] ?: continue
        if (!wanted(item)) continue
        var amount = stacks.amount(item)
        for (target in chests.filter { it.key in targets }) {
          val into = slots[target.key]!!
          for (slot in into.indices) {
            if (amount == 0) break
            val there = into[slot]
            if (there == null) {
              val put = minOf(amount, stacks.max(item))
              into[slot] = stacks.withAmount(item, put)
              amount -= put
            } else if (stacks.similar(there, item) && stacks.amount(there) < stacks.max(there)) {
              val put = minOf(amount, stacks.max(there) - stacks.amount(there))
              into[slot] = stacks.withAmount(there, stacks.amount(there) + put)
              amount -= put
            }
          }
        }
        from[index] = if (amount > 0) stacks.withAmount(item, amount) else null
        if (amount > 0) left.merge(stacks.id(item), amount, Int::plus)
      }
    }
    return Plan(slots, chests.associate { it.key to it.assign?.category }, left)
  }

  /** The main contents of a chest, biggest first: “iron_ingot” to 192. */
  fun <T> top(slots: List<T?>, stacks: Stacks<T>, limit: Int = 4): List<Pair<String, Int>> =
    totals(listOf(slots), stacks).entries.sortedByDescending { it.value }.take(limit).map { it.key to it.value }
}
