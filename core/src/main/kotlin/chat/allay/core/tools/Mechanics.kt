package chat.allay.core.tools

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Vanilla formulas the tools answer with, so the model gets exact numbers instead of guessing. */
object Mechanics {
  /** Experience points needed to go from this level to the next one. */
  fun pointsToNext(level: Int): Int = when {
    level <= 15 -> 2 * level + 7
    level <= 30 -> 5 * level - 38
    else -> 9 * level - 158
  }

  /** Experience points it takes to reach this level from zero, as whole numbers. */
  fun pointsAt(level: Int): Long {
    val l = level.toLong()
    return when {
      level <= 16 -> l * l + 6 * l
      level <= 31 -> (5 * l * l - 81 * l + 720) / 2
      else -> (9 * l * l - 325 * l + 4440) / 2
    }
  }

  /** The player's points: everything up to the level plus the part of the bar already filled. */
  fun points(level: Int, progress: Float): Long = pointsAt(level) + (progress.coerceIn(0f, 1f) * pointsToNext(level)).toLong()

  /** Where bookshelves count around an enchanting table: a ring two blocks out, on the table's level and one above. */
  val BOOKSHELF_OFFSETS: List<Triple<Int, Int, Int>> = buildList {
    for (dx in -2..2) for (dz in -2..2) for (dy in 0..1) {
      if (Math.abs(dx) == 2 || Math.abs(dz) == 2) add(Triple(dx, dy, dz))
    }
  }

  /** Counts the shelves that power a table: a shelf works only when the block between it and the table lets power through. */
  fun bookshelves(provider: (Int, Int, Int) -> Boolean, transmitter: (Int, Int, Int) -> Boolean): Int =
    BOOKSHELF_OFFSETS.count { (dx, dy, dz) -> provider(dx, dy, dz) && transmitter(dx / 2, dy, dz / 2) }

  /** The bottom and top of the third (best) offer's level for this many shelves; more than 15 count as 15. */
  fun topOfferRange(shelves: Int): IntRange {
    val b = shelves.coerceIn(0, 15)
    return max(1 + b / 2, b * 2)..max(8 + b / 2 + b, b * 2)
  }

  /** Damage after armor, as Java Edition computes it for a melee hit. */
  fun afterArmor(damage: Double, armor: Double, toughness: Double): Double {
    val reduction = (armor - damage / (2 + toughness / 4)).coerceIn(armor * 0.2, 20.0)
    return damage * (1 - reduction / 25)
  }

  /** Extra melee damage from the weapon's enchantments against this kind of mob. */
  fun enchantmentBonus(sharpness: Int, smite: Int, bane: Int, undead: Boolean, arthropod: Boolean): Double =
    (if (sharpness > 0) 0.5 * sharpness + 0.5 else 0.0) +
      (if (undead) 2.5 * smite else 0.0) +
      (if (arthropod) 2.5 * bane else 0.0)

  /** Fully charged hits without crits to bring the health to zero; null when a hit does nothing. */
  fun hitsToKill(health: Double, perHit: Double): Int? = if (perHit <= 0.0) null else max(1, ceil(health / perHit - 1e-9).toInt())

  /**
   * Uses left, counting Unbreaking: tools and weapons skip damage with chance 1 − 1/(L+1),
   * armor with chance 0.4·(1 − 1/(L+1)), so it lasts less long than a tool.
   */
  fun expectedUses(remaining: Int, unbreaking: Int, armor: Boolean): Int {
    if (unbreaking <= 0) return remaining
    val chance = if (armor) 0.6 + 0.4 / (unbreaking + 1) else 1.0 / (unbreaking + 1)
    return (remaining / chance).toInt()
  }

  fun durabilityPercent(remaining: Int, maxDurability: Int): Int = if (maxDurability <= 0) 100 else min(100, remaining * 100 / maxDurability)

  /** An anvil step costing this many levels or more says “Too Expensive!” in survival. */
  const val TOO_EXPENSIVE = 40

  /**
   * The price of one enchantment level when it comes off an item, from the vanilla data (`anvil_cost`).
   * A book pays half, at least 1. Enchantments missing here count as 4, the most common price.
   */
  val ANVIL_COSTS: Map<String, Int> = mapOf(
    "protection" to 1, "fire_protection" to 2, "feather_falling" to 2, "blast_protection" to 4, "projectile_protection" to 2,
    "respiration" to 4, "aqua_affinity" to 4, "thorns" to 8, "depth_strider" to 4, "frost_walker" to 4, "binding_curse" to 8,
    "soul_speed" to 8, "swift_sneak" to 8, "sharpness" to 1, "smite" to 2, "bane_of_arthropods" to 2, "knockback" to 2,
    "fire_aspect" to 4, "looting" to 4, "sweeping_edge" to 4, "efficiency" to 1, "silk_touch" to 8, "unbreaking" to 2,
    "fortune" to 4, "power" to 1, "punch" to 4, "flame" to 4, "infinity" to 8, "luck_of_the_sea" to 4, "lure" to 4,
    "loyalty" to 1, "impaling" to 4, "riptide" to 4, "channeling" to 8, "multishot" to 4, "quick_charge" to 2, "piercing" to 1,
    "mending" to 4, "vanishing_curse" to 8, "density" to 2, "breach" to 4, "wind_burst" to 4,
  )

  fun bookWeight(enchantment: String): Int = max(1, (ANVIL_COSTS[enchantment] ?: 4) / 2)

  /** One enchanted book for the anvil; [weight] is what one of its levels costs. */
  data class Book(val key: String, val level: Int, val weight: Int) {
    val value get() = level * weight
  }

  /** One use of the anvil: the pieces on the left and on the right (0 is the item, 1… the books) and its price in levels. */
  data class AnvilStep(val left: List<Int>, val right: List<Int>, val cost: Int)

  data class AnvilPlan(val steps: List<AnvilStep>, val total: Int, val tooExpensive: Boolean)

  private class Merged(val pieces: List<Int>, val total: Int, val worst: Int, val steps: List<AnvilStep>) {
    /** Plans that never hit “Too Expensive!” win, then the cheapest, then the one with the smallest single step. */
    fun beats(other: Merged?) = other == null || compareValuesBy(this, other, { it.worst >= TOO_EXPENSIVE }, { it.total }, { it.worst }) < 0
  }

  /** The prior work penalty after this many anvil uses: 0, 1, 3, 7… */
  fun penalty(works: Int) = (1 shl works) - 1

  /**
   * The cheapest order to put [books] on an item whose repair cost is [itemPenalty]. Every split of every set
   * of pieces is tried, keeping the best plan per number of prior works, since that number prices later steps.
   * The item always stays on the left; only the right side's enchantments are paid for.
   */
  fun anvilPlan(itemPenalty: Int, books: List<Book>): AnvilPlan {
    require(books.size <= 10) { "at most 10 books" }
    val n = books.size + 1
    val full = (1 shl n) - 1
    val values = IntArray(1 shl n)
    for (mask in 1..full) {
      val low = Integer.numberOfTrailingZeros(mask)
      values[mask] = values[mask and (mask - 1)] + if (low == 0) 0 else books[low - 1].value
    }
    val best = Array(1 shl n) { HashMap<Int, Merged>() }
    best[1][32 - Integer.numberOfLeadingZeros(itemPenalty.coerceAtLeast(0))] = Merged(listOf(0), 0, 0, emptyList())
    for (i in 1 until n) best[1 shl i][0] = Merged(listOf(i), 0, 0, emptyList())
    for (mask in (1..full).filter { Integer.bitCount(it) >= 2 }.sortedBy(Integer::bitCount)) {
      var left = (mask - 1) and mask
      while (left > 0) {
        val right = mask xor left
        // The item never goes on the right: that would put the books' enchantments nowhere.
        if (right and 1 == 0) {
          for ((lw, l) in best[left]) for ((rw, r) in best[right]) {
            val cost = penalty(lw) + penalty(rw) + values[right]
            val works = max(lw, rw) + 1
            val merged = Merged(l.pieces + r.pieces, l.total + r.total + cost, maxOf(cost, l.worst, r.worst), l.steps + r.steps + AnvilStep(l.pieces, r.pieces, cost))
            if (merged.beats(best[mask][works])) best[mask][works] = merged
          }
        }
        left = (left - 1) and mask
      }
    }
    val plan = best[full].values.reduce { a, b -> if (b.beats(a)) b else a }
    return AnvilPlan(plan.steps, plan.total, plan.worst >= TOO_EXPENSIVE)
  }

  /** Something on the anvil: an item or an enchanted book, its enchantments, and its repair cost (0, 1, 3, 7…). */
  data class AnvilPiece(val book: Boolean, val enchantments: Map<String, Int>, val repairCost: Int) {
    /** Enchantment levels in all, to rank results: Power V and Unbreaking III are 8. */
    val levels get() = enchantments.values.sum()
  }

  /** What the game knows about enchantments, asked of the platform's registry. */
  interface Enchanting {
    fun maxLevel(key: String): Int

    /** Whether the enchantment goes on the kind of item being made. */
    fun fits(key: String): Boolean

    fun compatible(a: String, b: String): Boolean

    /** The anvil cost of one level on an item; a book pays half, at least 1. */
    fun cost(key: String): Int = ANVIL_COSTS[key] ?: 4
  }

  /** The anvil's output and its price in levels. */
  data class Combined(val result: AnvilPiece, val cost: Int)

  /**
   * One anvil use, priced as the game's `AnvilMenu.createResult` (26.3) does, leaving out repairs and renaming: both
   * repair costs, then each enchantment on the right at its new level (equal levels go one up, to the maximum) times
   * its cost, plus 1 for every conflict. Null when the anvil shows nothing: a book on the left of an item, or nothing
   * on the right that fits.
   */
  fun combine(left: AnvilPiece, right: AnvilPiece, rules: Enchanting): Combined? {
    if (left.book && !right.book) return null
    val result = LinkedHashMap(left.enchantments)
    var cost = 0
    var applied = false
    var refused = false
    for ((key, level) in right.enchantments) {
      val current = result[key] ?: 0
      var fits = left.book || rules.fits(key)
      for (other in result.keys) if (other != key && !rules.compatible(key, other)) {
        fits = false
        cost++
      }
      if (!fits) {
        refused = true
        continue
      }
      applied = true
      val next = minOf(if (current == level) level + 1 else max(current, level), rules.maxLevel(key))
      result[key] = next
      cost += (if (right.book) max(1, rules.cost(key) / 2) else rules.cost(key)) * next
    }
    if ((refused && !applied) || cost <= 0) return null
    return Combined(AnvilPiece(left.book, result, (max(left.repairCost, right.repairCost) * 2L + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()), left.repairCost + right.repairCost + cost)
  }

  /** One anvil use in a [MergePlan]: pieces by index on each side, the price, and what comes out. */
  data class MergeStep(val left: List<Int>, val right: List<Int>, val cost: Int, val result: AnvilPiece)

  /** How to merge what the player has: the pieces it uses, the steps, and the item it ends with. */
  data class MergePlan(val used: List<Int>, val steps: List<MergeStep>, val result: AnvilPiece, val total: Int, val tooExpensive: Boolean)

  private class Node(val pieces: List<Int>, val piece: AnvilPiece, val total: Int, val worst: Int, val steps: List<MergeStep>) {
    val doable get() = worst < TOO_EXPENSIVE

    /** Doable first, then the most levels, then the cheapest, then the smallest single step. */
    fun beats(other: Node?) = other == null ||
      compareValuesBy(this, other, { !it.doable }, { -it.piece.levels }, { it.total }, { it.worst }) < 0
  }

  /**
   * The best way to merge [pieces]: items of one kind and enchanted books, as the player has them. Every subset is
   * tried in every order, since levels merge differently by order (II + II + III ends at III or at IV). The plan ends
   * in an item; it may leave a piece out when that piece adds nothing or only costs. At most 8 pieces.
   */
  fun mergePlan(pieces: List<AnvilPiece>, rules: Enchanting): MergePlan? {
    require(pieces.size <= 8) { "at most 8 pieces" }
    val n = pieces.size
    val best = Array(1 shl n) { HashMap<AnvilPiece, Node>() }
    for (i in 0 until n) best[1 shl i][pieces[i]] = Node(listOf(i), pieces[i], 0, 0, emptyList())
    for (mask in (1 until (1 shl n)).filter { Integer.bitCount(it) >= 2 }.sortedBy(Integer::bitCount)) {
      var left = (mask - 1) and mask
      while (left > 0) {
        val right = mask xor left
        for (l in best[left].values) for (r in best[right].values) {
          val made = combine(l.piece, r.piece, rules) ?: continue
          val step = MergeStep(l.pieces, r.pieces, made.cost, made.result)
          val node = Node(l.pieces + r.pieces, made.result, l.total + r.total + made.cost, maxOf(made.cost, l.worst, r.worst), l.steps + r.steps + step)
          if (node.beats(best[mask][made.result])) best[mask][made.result] = node
        }
        left = (left - 1) and mask
      }
      // Keep the search small: the few best results per set of pieces are enough to build on.
      if (best[mask].size > KEEP) {
        val kept = best[mask].values.sortedWith { a, b -> if (a.beats(b)) -1 else if (b.beats(a)) 1 else 0 }.take(KEEP)
        best[mask].clear()
        kept.forEach { best[mask][it.piece] = it }
      }
    }
    val plan = best.flatMap { it.values }.filter { !it.piece.book && it.steps.isNotEmpty() }.reduceOrNull { a, b -> if (b.beats(a)) b else a } ?: return null
    // A plan that ends no better than an item the player already has isn't worth the levels.
    if (plan.piece.levels <= pieces.filter { !it.book }.maxOf { it.levels }) return null
    return MergePlan(plan.pieces.sorted(), plan.steps, plan.piece, plan.total, !plan.doable)
  }

  private const val KEEP = 24

  /**
   * Each enchantment's lowest enchanting cost as the game's data has it (26.3): the cost at level 1 and its rise per
   * level. A grindstone pays back from these; an enchantment a data pack or mod adds counts as 1 here, and the mods read
   * the game's own value instead.
   */
  private val MIN_COSTS: Map<String, Pair<Int, Int>> = mapOf(
    "aqua_affinity" to (1 to 0), "bane_of_arthropods" to (5 to 8), "binding_curse" to (25 to 0), "blast_protection" to (5 to 8), "breach" to (15 to 9),
    "channeling" to (25 to 0), "density" to (5 to 8), "depth_strider" to (10 to 10), "efficiency" to (1 to 10), "feather_falling" to (5 to 6),
    "fire_aspect" to (10 to 20), "fire_protection" to (10 to 8), "flame" to (20 to 0), "fortune" to (15 to 9), "frost_walker" to (10 to 10),
    "impaling" to (1 to 8), "infinity" to (20 to 0), "knockback" to (5 to 20), "looting" to (15 to 9), "loyalty" to (12 to 7),
    "luck_of_the_sea" to (15 to 9), "lunge" to (5 to 8), "lure" to (15 to 9), "mending" to (25 to 25), "multishot" to (20 to 0), "piercing" to (1 to 10),
    "power" to (1 to 10), "projectile_protection" to (3 to 6), "protection" to (1 to 11), "punch" to (12 to 20), "quick_charge" to (12 to 20),
    "respiration" to (10 to 10), "riptide" to (17 to 7), "sharpness" to (1 to 11), "silk_touch" to (15 to 0), "smite" to (5 to 8), "soul_speed" to (10 to 10),
    "sweeping_edge" to (5 to 9), "swift_sneak" to (25 to 25), "thorns" to (10 to 20), "unbreaking" to (5 to 8), "vanishing_curse" to (25 to 0),
    "wind_burst" to (15 to 9),
  )

  /** The game's curses, which a grindstone leaves on (the `#curse` tag). */
  val CURSES = setOf("binding_curse", "vanishing_curse")

  /** An enchantment's lowest enchanting cost at [level], as `Enchantment.getMinCost` gives it. */
  fun minCost(enchantment: String, level: Int): Int = MIN_COSTS[enchantment]?.let { (base, perLevel) -> base + perLevel * (level - 1) } ?: 1

  /**
   * The experience a grindstone drops for enchantments whose lowest costs add up to [costs]: half of it rounded up,
   * and as much again at most by chance ([chance] gives 0 until its bound), as `GrindstoneMenu` pays.
   */
  fun grindstoneExperience(costs: Int, chance: (Int) -> Int): Int {
    if (costs <= 0) return 0
    val half = ceil(costs / 2.0).toInt()
    return half + chance(half)
  }

  /** The range [grindstoneExperience] falls in, for a plan to say. */
  fun grindstoneExperienceRange(costs: Int): IntRange = if (costs <= 0) 0..0 else ceil(costs / 2.0).toInt().let { it..(it * 2 - 1) }

  /** The prior work penalty a grindstone leaves: as if each enchantment left (only curses) took one more anvil use. */
  fun grindstoneRepairCost(left: Int): Int = (0 until left).fold(0) { cost, _ -> cost * 2 + 1 }

  /**
   * The damage of two pieces of one item merged on a grindstone: what both have left, plus 5% of [max], as the game
   * repairs it; never below none.
   */
  fun grindstoneDamage(max: Int, first: Int, second: Int): Int = max(max - ((max - first) + (max - second) + max * 5 / 100), 0)

  /**
   * How much of a potion's duration an item gives, as its tooltip shows it: a tipped arrow an eighth,
   * a lingering potion a quarter. Read from the item type: Paper's API has no duration scale to ask.
   */
  fun potionDurationScale(id: String): Double = when (id) {
    "tipped_arrow" -> 0.125
    "lingering_potion" -> 0.25
    else -> 1.0
  }

  /** Whole seconds the tooltip shows for an effect of [ticks] on an item with this [scale]; −1 lasts forever. */
  fun effectSeconds(ticks: Int, scale: Double, infinite: Boolean = false): Int = if (infinite) -1 else floor(ticks * scale).toInt() / 20

  /** One attribute modifier: [operation] and [slot] as commands name them (`add_value`, `mainhand`). */
  data class Modifier(val attribute: String, val amount: Double, val operation: String, val slot: String)

  /** The modifiers that work: an item with modifiers of its own uses only those, never its type's defaults. */
  fun effectiveModifiers(own: List<Modifier>, defaults: List<Modifier>): List<Modifier> = own.ifEmpty { defaults }

  /** What an attribute comes to: the base plus every added value, then the base multipliers, then the total ones. */
  fun attributeValue(base: Double, modifiers: List<Modifier>): Double {
    val added = base + modifiers.filter { it.operation == "add_value" }.sumOf { it.amount }
    val multiplied = added * (1 + modifiers.filter { it.operation == "add_multiplied_base" }.sumOf { it.amount })
    return modifiers.filter { it.operation == "add_multiplied_total" }.fold(multiplied) { value, modifier -> value * (1 + modifier.amount) }
  }

  /** The player's own values a weapon's tooltip builds on: a fist hits for 1 and swings 4 times a second. */
  val PLAYER_BASE = mapOf("attack_damage" to 1.0, "attack_speed" to 4.0)

  private val MAIN_HAND = setOf("mainhand", "hand", "any")

  /**
   * Attack damage and speed as the tooltip shows them in the main hand: a diamond sword's 6 and −2.4 read as 7 and 1.6.
   * Enchantments aren't in it, as in the game. Only attributes the item changes are listed.
   */
  fun tooltip(modifiers: List<Modifier>): Map<String, Double> = PLAYER_BASE.mapNotNull { (attribute, base) ->
    val applied = modifiers.filter { it.attribute == attribute && it.slot in MAIN_HAND }
    if (applied.isEmpty()) null else attribute to Math.round(attributeValue(base, applied) * 100) / 100.0
  }.toMap()

  /** The first [max] items and how many were left out. */
  fun <T> capped(items: List<T>, max: Int): Pair<List<T>, Int> = items.take(max) to (items.size - max).coerceAtLeast(0)

  /** Hunger points and saturation each food restores, plus what else eating it does. */
  data class Food(val nutrition: Int, val saturation: Double, val note: String? = null)

  val FOODS: Map<String, Food> = mapOf(
    "apple" to Food(4, 2.4),
    "baked_potato" to Food(5, 6.0),
    "beef" to Food(3, 1.8),
    "beetroot" to Food(1, 1.2),
    "beetroot_soup" to Food(6, 7.2),
    "bread" to Food(5, 6.0),
    "carrot" to Food(3, 3.6),
    "chicken" to Food(2, 1.2, "30% chance of Hunger for 30 s"),
    "chorus_fruit" to Food(4, 2.4, "teleports up to 8 blocks; can be eaten when full"),
    "cod" to Food(2, 0.4),
    "cooked_beef" to Food(8, 12.8),
    "cooked_chicken" to Food(6, 7.2),
    "cooked_cod" to Food(5, 6.0),
    "cooked_mutton" to Food(6, 9.6),
    "cooked_porkchop" to Food(8, 12.8),
    "cooked_rabbit" to Food(5, 6.0),
    "cooked_salmon" to Food(6, 9.6),
    "cookie" to Food(2, 0.4),
    "dried_kelp" to Food(1, 0.6, "eaten fast"),
    "enchanted_golden_apple" to Food(4, 9.6, "Regeneration II 20 s, Absorption IV 2 min, Resistance and Fire Resistance 5 min; can be eaten when full"),
    "glow_berries" to Food(2, 0.4),
    "golden_apple" to Food(4, 9.6, "Regeneration II 5 s, Absorption 2 min; can be eaten when full"),
    "golden_carrot" to Food(6, 14.4),
    "honey_bottle" to Food(6, 1.2, "cures Poison; leaves a glass bottle"),
    "melon_slice" to Food(2, 1.2),
    "mushroom_stew" to Food(6, 7.2),
    "mutton" to Food(2, 1.2),
    "poisonous_potato" to Food(2, 1.2, "60% chance of Poison for 5 s"),
    "porkchop" to Food(3, 1.8),
    "potato" to Food(1, 0.6),
    "pufferfish" to Food(1, 0.2, "Hunger III 15 s, Nausea 15 s, Poison II 60 s"),
    "pumpkin_pie" to Food(8, 4.8),
    "rabbit" to Food(3, 1.8),
    "rabbit_stew" to Food(10, 12.0),
    "rotten_flesh" to Food(4, 0.8, "80% chance of Hunger for 30 s"),
    "salmon" to Food(2, 0.4),
    "spider_eye" to Food(2, 3.2, "Poison for 5 s"),
    "suspicious_stew" to Food(6, 7.2, "gives the effect of the flower it was made with"),
    "sweet_berries" to Food(2, 0.4),
    "tropical_fish" to Food(1, 0.2),
  )
}
