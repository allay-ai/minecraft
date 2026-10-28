package chat.allay.core.tools

/**
 * The arithmetic of furnaces and crafting, without touching Bukkit: how much fuel a load needs, how a load splits over
 * furnaces, and how to craft an item from what the player has, intermediate parts included.
 */
object Workshop {
  /** A standard furnace cooks one item in this many ticks; blast furnaces and smokers cook twice as fast and burn fuel twice as fast. */
  const val COOK_TICKS = 200

  /** How long each fuel burns in a standard furnace, in ticks, as the game's fuel values have it. */
  val FUELS: Map<String, Int> = linkedMapOf(
    "coal" to 1600, "charcoal" to 1600, "coal_block" to 16000, "blaze_rod" to 2400, "dried_kelp_block" to 4001, "lava_bucket" to 20000,
    "stick" to 100, "bamboo" to 50, "scaffolding" to 50,
  )

  /** Logs, wood, and planks burn too; the platform knows which ids those are. */
  const val WOOD_TICKS = 300

  /** Burn ticks of [id], or null when it isn't fuel. Crimson and warped wood never burns, as in the game. */
  fun burnTicks(id: String): Int? = FUELS[id] ?: if (wood(id)) WOOD_TICKS else null

  /** Logs, wood, planks, and bamboo blocks that burn. */
  fun wood(id: String): Boolean = !id.startsWith("crimson_") && !id.startsWith("warped_") && !id.startsWith("stripped_crimson_") && !id.startsWith("stripped_warped_") &&
    (id.endsWith("_log") || id.endsWith("_wood") || id.endsWith("_planks") || id == "bamboo_block" || id == "stripped_bamboo_block")

  /** Fuel items for [items] smelts, after [banked] ticks already burning or waiting in the furnace. */
  fun fuelFor(items: Int, fuel: String, banked: Int = 0): Int {
    val ticks = burnTicks(fuel) ?: return 0
    val needed = items.toLong() * COOK_TICKS - banked
    return if (needed <= 0) 0 else ((needed + ticks - 1) / ticks).toInt()
  }

  /** [total] items over [furnaces] as evenly as can be, each at most [cap]: `10 over 3` is `4, 3, 3`. */
  fun spread(total: Int, furnaces: Int, cap: Int = 64): List<Int> {
    if (furnaces <= 0) return emptyList()
    val each = minOf(total, furnaces * cap)
    return (0 until furnaces).map { minOf(cap, each / furnaces + if (it < each % furnaces) 1 else 0) }
  }

  /** [total] items over places with room for [rooms] each, as evenly as the room allows: `10 over room 2, 9, 9` is `2, 4, 4`. */
  fun fill(total: Int, rooms: List<Int>): List<Int> {
    val shares = MutableList(rooms.size) { 0 }
    var left = total
    while (left > 0) {
      val open = rooms.indices.filter { shares[it] < rooms[it] }
      if (open.isEmpty()) break
      for (index in open) if (left > 0) { shares[index]++; left-- }
    }
    return shares
  }

  /** A furnace for [allot]: how fast it cooks each item it can (a blast furnace cooks ores at 2, a furnace at 1). */
  data class Oven(val speeds: Map<String, Int>)

  /** One furnace's part of a load: what it cooks and how many; null for a furnace left out. */
  data class Part(val item: String, val count: Int)

  /**
   * Splits [counts] (item to how many) over [ovens] so the last furnace finishes as early as it can: each furnace cooks
   * one item, the fastest furnaces are handed out first, each to the item that would take longest with what it has so
   * far, and each item's count splits by its furnaces' speed. Null when some item has no furnace that cooks it.
   */
  fun allot(counts: Map<String, Int>, ovens: List<Oven>): List<Part?>? {
    val wanted = counts.filterValues { it > 0 }
    val owner = arrayOfNulls<String>(ovens.size)
    val speed = wanted.keys.associateWith { 0 }.toMutableMap()
    val order = ovens.indices.sortedByDescending { index -> ovens[index].speeds.values.maxOrNull() ?: 0 }
    // An item nothing else can cook gets its furnaces first, so a furnace that cooks everything isn't spent elsewhere.
    for (index in order) {
      val choices = wanted.keys.filter { (ovens[index].speeds[it] ?: 0) > 0 }
      val pick = choices.maxByOrNull { item ->
        val mine = speed[item]!!
        if (mine == 0) Double.MAX_VALUE / (1 + ovens.count { (it.speeds[item] ?: 0) > 0 }) else wanted[item]!!.toDouble() / mine
      } ?: continue
      owner[index] = pick
      speed[pick] = speed[pick]!! + ovens[index].speeds[pick]!!
    }
    if (wanted.keys.any { speed[it] == 0 }) return null
    val parts = arrayOfNulls<Part>(ovens.size)
    for ((item, total) in wanted) {
      val mine = ovens.indices.filter { owner[it] == item }
      val weights = mine.map { ovens[it].speeds[item]!! }
      val sum = weights.sum()
      val shares = weights.map { total * it / sum }.toMutableList()
      // What the integer split leaves goes to the fastest furnaces first.
      var left = total - shares.sum()
      for (i in weights.indices.sortedByDescending { weights[it] }) if (left > 0) { shares[i]++; left-- }
      mine.forEachIndexed { i, index -> if (shares[i] > 0) parts[index] = Part(item, shares[i]) }
    }
    return parts.toList()
  }

  /**
   * Where a recipe is made; [WATER] is a glass bottle filled at water or a cauldron near, a level a bottle, and
   * [FURNACE] a smelt, which a furnaces agent does: crafting only plans it.
   */
  enum class Station { INVENTORY, TABLE, STONECUTTER, SMITHING, WATER, FURNACE }

  /** What crafting calls a water bottle, as [Brewing.Bottle.label] writes potions. */
  val WATER_BOTTLE = Brewing.WATER.label

  /** Filling a glass bottle at water, as a recipe the planner can use when water is near. */
  val FILL = Recipe(WATER_BOTTLE, 1, listOf(setOf("glass_bottle")), Station.WATER)

  /** Whether [name] means a water bottle, as the model and players write it: `water_bottle`, `potion:water`, `potion water`. */
  fun water(name: String): Boolean = name.trim().lowercase().removePrefix("minecraft:").replace(Regex("[ :]+"), "_") in WATER_NAMES

  private val WATER_NAMES = setOf("water_bottle", "potion_water", "water_potion", "bottle_of_water", "water")

  /**
   * A recipe as data: [slots] holds the choices for each ingredient slot, empty slots left out. A crafting recipe
   * that fits the player's own 2×2 grid needs no crafting table ([Station.INVENTORY]): planks, sticks, the table itself.
   */
  data class Recipe(val result: String, val count: Int, val slots: List<Set<String>>, val station: Station = Station.TABLE) {
    constructor(result: String, count: Int, slots: List<Set<String>>, small: Boolean) : this(result, count, slots, if (small) Station.INVENTORY else Station.TABLE)
  }

  /** One craft of [recipe], [times] over, with the ids it used for each slot. */
  data class Step(val recipe: Recipe, val times: Int, val used: Map<String, Int>)

  /** What [plan] found: the steps, intermediate parts first, and what they take from the player. */
  data class Plan(val steps: List<Step>, val takes: Map<String, Int>, val makes: Map<String, Int>) {
    /** The stations its steps need, besides the player's own grid. */
    val stations get() = steps.map { it.recipe.station }.toSet() - Station.INVENTORY

    /** Whether some step needs a 3×3 grid, so a crafting table. */
    val needsTable get() = Station.TABLE in stations

    /** How many glass bottles its steps fill at water. */
    val fills get() = steps.filter { it.recipe.station == Station.WATER }.sumOf { it.times }

    /** What goes into furnaces first, by id: raw iron for the ingots. */
    val smelts: Map<String, Int> get() = steps.filter { it.recipe.station == Station.FURNACE }.flatMap { it.used.entries }.groupingBy { it.key }.fold(0) { sum, it -> sum + it.value }

    /**
     * The crafts the furnaces wait for, with what they take and make together: raw iron from its block, which no
     * furnace smelts. They can be crafted now; the rest of the plan waits for the smelting.
     */
    fun beforeSmelting(): Plan {
      val wanted = smelts.keys.toMutableSet()
      val first = mutableListOf<Step>()
      for (step in steps.asReversed()) if (step.recipe.station != Station.FURNACE && step.recipe.result in wanted) {
        first.add(0, step)
        wanted += step.used.keys
      }
      val balance = mutableMapOf<String, Int>()
      first.forEach { step ->
        step.used.forEach { (id, n) -> balance.merge(id, -n, Int::plus) }
        balance.merge(step.recipe.result, step.times * step.recipe.count, Int::plus)
      }
      return Plan(first, balance.filterValues { it < 0 }.mapValues { -it.value }, balance.filterValues { it > 0 })
    }
  }

  /** What a plan calls the piece a smithing table upgrades into [result]: the player's own, or one crafted on the way. */
  fun base(result: String) = "base:$result"

  /**
   * An upgrade on a smithing table as recipes the planner reads: [result] from the template, the addition, and the
   * piece to upgrade, which is one of the player's own (counted under [base]) or one crafted on the way of [kinds], so a
   * missing diamond chestplate reads as the diamonds it takes.
   */
  fun smithing(result: String, template: Set<String>, addition: Set<String>, kinds: Set<String>): List<Recipe> = listOf(
    Recipe("smithing:$result", 1, listOf(template, addition, setOf(base(result))), Station.SMITHING),
    Recipe(base(result), 1, listOf(kinds), Station.INVENTORY),
  )

  /** Levels [plan] crafts at most, the item itself the first: a chest from logs takes two, planks and the chest. */
  const val DEPTH = 4

  /**
   * Why nothing could be planned: the items missing for the cheapest way the planner saw, and [parts], what each was
   * for when that's a part on the way: diamonds for a diamond chestplate to upgrade.
   */
  data class Missing(val items: Map<String, Int>, val parts: Map<String, String> = emptyMap())

  /**
   * How to craft [count] of [item] from [have] (id to count), crafting parts it lacks from [recipes] up to [depth]
   * levels down: sticks from planks, planks from logs. A slot that takes any of several items (any planks) takes what
   * the player has first, then crafts the rest from whichever kind they can make it of: jungle planks from jungle logs
   * when that's the wood they carry. Returns a [Plan], or [Missing] with what's short.
   */
  fun plan(item: String, count: Int, have: Map<String, Int>, recipes: (String) -> List<Recipe>, depth: Int = DEPTH): Any {
    val planner = Planner(have, recipes, depth)
    if (!planner.craft(item, count, 0)) return Missing(planner.missing, planner.parents.filter { (id, part) -> id in planner.missing && part != item })
    val takes = have.mapNotNull { (id, n) -> (n - (planner.stock[id] ?: 0)).takeIf { it > 0 }?.let { id to it } }.toMap()
    val makes = planner.spare.filterValues { it > 0 }.toMutableMap().apply { merge(item, count, Int::plus) }
    return Plan(planner.steps, takes, makes)
  }

  private class Planner(have: Map<String, Int>, private val recipes: (String) -> List<Recipe>, private val depth: Int) {
    val stock = have.toMutableMap()
    /** What crafting a part made beyond what was needed: four planks from a log when three were wanted. */
    val spare = mutableMapOf<String, Int>()
    val steps = mutableListOf<Step>()
    val missing = mutableMapOf<String, Int>()
    /** What each ingredient was first wanted for. */
    val parents = mutableMapOf<String, String>()

    private class Saved(val stock: Map<String, Int>, val spare: Map<String, Int>, val steps: Int, val missing: Map<String, Int>, val parents: Map<String, String>)

    private fun save() = Saved(stock.toMap(), spare.toMap(), steps.size, missing.toMap(), parents.toMap())

    private fun restore(saved: Saved) {
      stock.clear(); stock.putAll(saved.stock)
      spare.clear(); spare.putAll(saved.spare)
      while (steps.size > saved.steps) steps.removeAt(steps.lastIndex)
      missing.clear(); missing.putAll(saved.missing)
      parents.clear(); parents.putAll(saved.parents)
    }

    /** Runs [block] and keeps what it did only when it worked. */
    private fun attempt(block: () -> Boolean): Boolean {
      val saved = save()
      if (block()) return true
      restore(saved)
      return false
    }

    private fun available(id: String) = (stock[id] ?: 0) + (spare[id] ?: 0)

    /** Takes [amount] of [id] the player has or a craft left over, spare parts first. */
    private fun take(id: String, amount: Int) {
      val fromSpare = minOf(amount, spare[id] ?: 0)
      spare[id] = (spare[id] ?: 0) - fromSpare
      stock[id] = (stock[id] ?: 0) - (amount - fromSpare)
    }

    /** Crafts [short] of [id], trying its recipes from the one the player has most for; a failure leaves what's missing. */
    fun craft(id: String, short: Int, level: Int): Boolean {
      // A stonecutter first when there's one: stairs come one from a block there, four from six on a table. A furnace
      // last: smelting takes time, so ingots come from iron blocks first when the player has some.
      val options = if (level < depth) recipes(id).sortedWith(compareBy<Recipe> { it.station == Station.FURNACE }.thenBy { it.station != Station.STONECUTTER }.thenByDescending { recipe -> recipe.slots.sumOf { slot -> slot.maxOf(::available) } }) else emptyList()
      if (options.isEmpty()) {
        missing.merge(id, short, Int::plus)
        return false
      }
      for (recipe in options) if (attempt { use(recipe, id, short, level) }) return true
      // Nothing works: the likeliest way says what's missing, but never through a block made of the item itself, so
      // three diamonds short reads as diamonds, not a diamond block, nor through ore, which is mined: diamonds, not the
      // diamond ore a silk touch pickaxe brings. Raw iron for ingots and sand for glass do say what to get.
      val likeliest = options.firstOrNull { recipe ->
        recipe.slots.none { slot -> slot.any { part -> recipes(part).any { made -> made.slots.any { id in it } } } } &&
          !(recipe.station == Station.FURNACE && recipe.slots.all { slot -> slot.all { it.endsWith("_ore") } })
      }
      if (likeliest == null) missing.merge(id, short, Int::plus) else use(likeliest, id, short, level)
      return false
    }

    private fun use(recipe: Recipe, id: String, short: Int, level: Int): Boolean {
      val times = (short + recipe.count - 1) / recipe.count
      val used = mutableMapOf<String, Int>()
      recipe.slots.forEach { slot -> slot.forEach { parents.putIfAbsent(it, id) } }
      var ok = true
      recipe.slots.groupBy { it }.forEach { (choices, same) -> if (!fill(choices, same.size * times, level + 1, used)) ok = false }
      if (!ok) return false
      steps += Step(recipe, times, used)
      spare.merge(id, times * recipe.count - short, Int::plus)
      return true
    }

    /** Finds [wanted] items for slots that take any of [choices]: what's there first, then crafted from one kind or several. */
    private fun fill(choices: Set<String>, wanted: Int, level: Int, used: MutableMap<String, Int>): Boolean {
      var left = wanted
      for (choice in choices.sortedByDescending(::available)) {
        val n = minOf(left, available(choice))
        if (n == 0) continue
        take(choice, n)
        used.merge(choice, n, Int::plus)
        left -= n
      }
      if (left == 0) return true
      for (choice in choices) if (attempt { craft(choice, left, level) }) {
        used.merge(choice, left, Int::plus)
        return true
      }
      // No one kind makes it all: as much of each kind as it can make, like 6 oak logs and 10 jungle logs for 64 planks.
      val short = left
      val before = save()
      for (choice in choices) {
        val one = save()
        val any = craft(choice, 1, level)
        restore(one)
        if (!any) continue
        var low = 1
        var high = left
        while (low < high) {
          val middle = (low + high + 1) / 2
          val saved = save()
          if (craft(choice, middle, level)) low = middle else high = middle - 1
          restore(saved)
        }
        if (low == 0) continue
        craft(choice, low, level)
        used.merge(choice, low, Int::plus)
        left -= low
        if (left == 0) return true
      }
      // Undone, it says what's missing for all that was short, not for what's left after the kinds it undid.
      restore(before)
      craft(choices.first(), short, level)
      return false
    }
  }
}
