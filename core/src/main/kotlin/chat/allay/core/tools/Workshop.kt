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
  )

  /** Logs, wood, and planks burn too; the platform knows which ids those are. */
  const val WOOD_TICKS = 300

  fun burnTicks(id: String): Int? = FUELS[id] ?: if (id.endsWith("_log") || id.endsWith("_wood") || id.endsWith("_planks") || id.endsWith("_stem") || id.endsWith("_hyphae")) WOOD_TICKS else null

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

  /** A crafting recipe as data: [slots] holds the choices for each ingredient slot, empty slots left out. */
  data class Recipe(val result: String, val count: Int, val slots: List<Set<String>>)

  /** One craft of [recipe], [times] over, with the ids it used for each slot. */
  data class Step(val recipe: Recipe, val times: Int, val used: Map<String, Int>)

  /** What [plan] found: the steps, intermediate parts first, and what they take from the player. */
  data class Plan(val steps: List<Step>, val takes: Map<String, Int>, val makes: Map<String, Int>)

  /** Why nothing could be planned: the items missing for the cheapest way the planner saw. */
  data class Missing(val items: Map<String, Int>)

  /**
   * How to craft [count] of [item] from [have] (id to count), crafting parts it lacks from [recipes] up to [depth]
   * levels down: sticks from planks, planks from logs. Each slot takes the choice the player has most of. Returns
   * a [Plan], or [Missing] with what's short.
   */
  fun plan(item: String, count: Int, have: Map<String, Int>, recipes: (String) -> List<Recipe>, depth: Int = 3): Any {
    val planner = Planner(have, recipes, depth)
    if (!planner.craft(item, count, 0)) return Missing(planner.missing)
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

    private fun available(id: String) = (stock[id] ?: 0) + (spare[id] ?: 0)

    /** Takes [amount] of [id]: spare parts first, then the player's, then crafts the rest. */
    fun need(id: String, amount: Int, level: Int): Boolean {
      var short = amount
      val fromSpare = minOf(short, spare[id] ?: 0)
      spare[id] = (spare[id] ?: 0) - fromSpare
      short -= fromSpare
      val fromStock = minOf(short, stock[id] ?: 0)
      stock[id] = (stock[id] ?: 0) - fromStock
      short -= fromStock
      return short == 0 || craft(id, short, level)
    }

    fun craft(id: String, short: Int, level: Int): Boolean {
      val recipe = if (level < depth) recipes(id).maxByOrNull { recipe -> recipe.slots.sumOf { slot -> slot.maxOf(::available) } } else null
      if (recipe == null) {
        missing.merge(id, short, Int::plus)
        return false
      }
      val times = (short + recipe.count - 1) / recipe.count
      val used = mutableMapOf<String, Int>()
      var ok = true
      // Slots with the same choices take one kind: a pickaxe's three planks all of the kind there is most of.
      recipe.slots.groupBy { it }.forEach { (choices, same) ->
        val pick = choices.maxBy(::available)
        val wanted = same.size * times
        used.merge(pick, wanted, Int::plus)
        if (!need(pick, wanted, level + 1)) ok = false
      }
      if (!ok) return false
      steps += Step(recipe, times, used)
      spare.merge(id, times * recipe.count - short, Int::plus)
      return true
    }
  }
}
