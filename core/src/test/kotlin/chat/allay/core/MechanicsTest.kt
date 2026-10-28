package chat.allay.core

import chat.allay.core.tools.Mechanics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MechanicsTest {
  @Test fun `experience follows the vanilla table`() {
    assertEquals(7, Mechanics.pointsToNext(0))
    assertEquals(37, Mechanics.pointsToNext(15))
    assertEquals(42, Mechanics.pointsToNext(16))
    assertEquals(112, Mechanics.pointsToNext(30))
    assertEquals(121, Mechanics.pointsToNext(31))
    assertEquals(0, Mechanics.pointsAt(0))
    assertEquals(352, Mechanics.pointsAt(16))
    assertEquals(394, Mechanics.pointsAt(17))
    assertEquals(1395, Mechanics.pointsAt(30))
    assertEquals(1628, Mechanics.pointsAt(32))
    // Every level's total is the previous total plus the step between them.
    (0 until 100).forEach { level -> assertEquals(Mechanics.pointsAt(level) + Mechanics.pointsToNext(level), Mechanics.pointsAt(level + 1), "level $level") }
    assertEquals(1395 + 56, Mechanics.points(30, 0.5f))
  }

  @Test fun `bookshelves count only through a free gap`() {
    assertEquals(32, Mechanics.BOOKSHELF_OFFSETS.size)
    assertEquals(32, Mechanics.bookshelves({ _, _, _ -> true }, { _, _, _ -> true }))
    // A torch in the diagonal gap blocks only the corner shelf behind it, on that level.
    val blocked = Mechanics.bookshelves({ _, _, _ -> true }, { dx, dy, dz -> !(dx == 1 && dy == 0 && dz == 1) })
    assertEquals(31, blocked)
    assertEquals(0, Mechanics.bookshelves({ _, _, _ -> false }, { _, _, _ -> true }))
  }

  @Test fun `fifteen shelves make a level thirty offer`() {
    assertEquals(30..30, Mechanics.topOfferRange(15))
    assertEquals(30..30, Mechanics.topOfferRange(32))
    assertEquals(1..8, Mechanics.topOfferRange(0))
    assertEquals(20..23, Mechanics.topOfferRange(10))
  }

  @Test fun `armor and enchantments shape the hits to kill`() {
    val zombieHit = Mechanics.afterArmor(7.0, armor = 2.0, toughness = 0.0)
    assertEquals(6.888, zombieHit, 1e-9)
    assertEquals(3, Mechanics.hitsToKill(20.0, zombieHit))
    assertEquals(3.0, Mechanics.enchantmentBonus(sharpness = 5, smite = 0, bane = 0, undead = true, arthropod = false))
    assertEquals(12.5, Mechanics.enchantmentBonus(sharpness = 0, smite = 5, bane = 0, undead = true, arthropod = false))
    assertEquals(0.0, Mechanics.enchantmentBonus(sharpness = 0, smite = 5, bane = 0, undead = false, arthropod = false))
    assertEquals(2, Mechanics.hitsToKill(20.0, 10.0))
    assertEquals(1, Mechanics.hitsToKill(0.5, 10.0))
    assertNull(Mechanics.hitsToKill(20.0, 0.0))
  }

  @Test fun `unbreaking stretches tools more than armor`() {
    assertEquals(100, Mechanics.expectedUses(100, 0, armor = false))
    assertEquals(400, Mechanics.expectedUses(100, 3, armor = false))
    assertEquals(142, Mechanics.expectedUses(100, 3, armor = true))
    assertEquals(9, Mechanics.durabilityPercent(150, 1561))
  }

  @Test fun `the food table knows the classics`() {
    assertEquals(Mechanics.Food(6, 14.4), Mechanics.FOODS["golden_carrot"])
    assertEquals(8, Mechanics.FOODS["cooked_beef"]!!.nutrition)
    assertEquals(true, Mechanics.FOODS["rotten_flesh"]!!.note?.contains("Hunger"))
    Mechanics.FOODS.values.forEach { assert(it.nutrition in 1..10 && it.saturation >= 0) }
  }

  private fun book(key: String, level: Int) = Mechanics.Book(key, level, Mechanics.bookWeight(key))

  @Test fun `books cost half the item price of a level`() {
    assertEquals(1, Mechanics.bookWeight("sharpness"))
    assertEquals(2, Mechanics.bookWeight("mending"))
    assertEquals(4, Mechanics.bookWeight("silk_touch"))
    assertEquals(2, Mechanics.bookWeight("some_new_enchantment"))
  }

  @Test fun `one book on a fresh item costs its levels`() {
    val plan = Mechanics.anvilPlan(0, listOf(book("sharpness", 5)))
    assertEquals(listOf(Mechanics.AnvilStep(listOf(0), listOf(1), 5)), plan.steps)
    assertEquals(5, plan.total)
    assertEquals(0, Mechanics.anvilPlan(0, emptyList()).steps.size)
  }

  @Test fun `two books go on one at a time, paying the penalty once`() {
    val plan = Mechanics.anvilPlan(0, listOf(book("sharpness", 5), book("unbreaking", 3)))
    // 5, then 1 for the item's first work plus 3.
    assertEquals(9, plan.total)
    assertEquals(2, plan.steps.size)
    assertEquals(false, plan.tooExpensive)
  }

  @Test fun `a worn item takes its books merged first rather than hit too expensive`() {
    val plan = Mechanics.anvilPlan(31, listOf(book("protection", 4), book("mending", 1)))
    // Book onto book for 2, then 31 + 1 + 6 on the item: one pass instead of two.
    assertEquals(40, plan.total)
    assertEquals(false, plan.tooExpensive)
    assertEquals(listOf(0), plan.steps.last().left)
    val hopeless = Mechanics.anvilPlan(31, listOf(book("protection", 4), book("mending", 1), book("unbreaking", 3)))
    assertEquals(true, hopeless.tooExpensive)
  }

  @Test fun `every plan uses each book exactly once`() {
    val books = listOf("sharpness" to 5, "unbreaking" to 3, "mending" to 1, "looting" to 3, "fire_aspect" to 2, "sweeping_edge" to 3).map { (k, l) -> book(k, l) }
    val plan = Mechanics.anvilPlan(0, books)
    assertEquals(books.size, plan.steps.size)
    assertEquals((0..books.size).toList(), (plan.steps.last().left + plan.steps.last().right).sorted())
    assertEquals(plan.total, plan.steps.sumOf { it.cost })
  }

  private fun modifier(attribute: String, amount: Double, operation: String = "add_value", slot: String = "mainhand") = Mechanics.Modifier(attribute, amount, operation, slot)

  @Test fun `weapon tooltips add the player's base to the item's modifiers`() {
    val diamondSword = listOf(modifier("attack_damage", 6.0), modifier("attack_speed", -2.4))
    assertEquals(mapOf("attack_damage" to 7.0, "attack_speed" to 1.6), Mechanics.tooltip(diamondSword))
    val netheriteAxe = listOf(modifier("attack_damage", 9.0), modifier("attack_speed", -3.0))
    assertEquals(mapOf("attack_damage" to 10.0, "attack_speed" to 1.0), Mechanics.tooltip(netheriteAxe))
    // Armor changes no attack numbers, and modifiers for another slot don't count in hand.
    assertEquals(emptyMap(), Mechanics.tooltip(listOf(modifier("armor", 8.0, slot = "chest"))))
    assertEquals(emptyMap(), Mechanics.tooltip(listOf(modifier("attack_damage", 3.0, slot = "offhand"))))
    // Multipliers come after the added values: (1 + 6) × 1.5, then × 2.
    val custom = diamondSword + modifier("attack_damage", 0.5, "add_multiplied_base") + modifier("attack_damage", 1.0, "add_multiplied_total")
    assertEquals(21.0, Mechanics.tooltip(custom)["attack_damage"])
  }

  @Test fun `an item's own modifiers replace its type's defaults`() {
    val defaults = listOf(modifier("attack_damage", 6.0), modifier("attack_speed", -2.4))
    val own = listOf(modifier("movement_speed", 0.1, "add_multiplied_base", "any"))
    assertEquals(defaults, Mechanics.effectiveModifiers(emptyList(), defaults))
    // Vanilla then uses only the item's own: no sword damage is left on top.
    assertEquals(own, Mechanics.effectiveModifiers(own, defaults))
    assertEquals(emptyMap(), Mechanics.tooltip(Mechanics.effectiveModifiers(own, defaults)))
  }

  @Test fun `tipped arrows and lingering potions show shorter effects`() {
    assertEquals(1.0, Mechanics.potionDurationScale("potion"))
    assertEquals(1.0, Mechanics.potionDurationScale("splash_potion"))
    // Swiftness lasts 3:00 in a bottle, 0:45 in a lingering cloud, 0:22 on an arrow.
    assertEquals(180, Mechanics.effectSeconds(3600, Mechanics.potionDurationScale("potion")))
    assertEquals(45, Mechanics.effectSeconds(3600, Mechanics.potionDurationScale("lingering_potion")))
    assertEquals(22, Mechanics.effectSeconds(3600, Mechanics.potionDurationScale("tipped_arrow")))
    assertEquals(-1, Mechanics.effectSeconds(3600, 1.0, infinite = true))
  }

  @Test fun `capped lists say how many were left out`() {
    assertEquals(listOf(1, 2) to 3, Mechanics.capped(listOf(1, 2, 3, 4, 5), 2))
    assertEquals(listOf(1, 2) to 0, Mechanics.capped(listOf(1, 2), 27))
    assertEquals(emptyList<Int>() to 0, Mechanics.capped(emptyList<Int>(), 27))
  }

  @Test fun `a grindstone pays back as the game does`() {
    // Sharpness V: 1 + 11 × 4 = 45, so 23 and up to 22 more.
    assertEquals(45, Mechanics.minCost("sharpness", 5))
    assertEquals(23, Mechanics.grindstoneExperience(45) { 0 })
    assertEquals(45, Mechanics.grindstoneExperience(45) { it - 1 })
    assertEquals(23..45, Mechanics.grindstoneExperienceRange(45))
    assertEquals(0, Mechanics.grindstoneExperience(0) { error("no chance without enchantments") })
    assertEquals(1, Mechanics.minCost("some_pack:glow", 3))
  }

  @Test fun `a grindstone joins durability with 5 percent more`() {
    // A diamond pickaxe (1561) with 100 and 200 left: 300 + 78.
    assertEquals(1561 - 378, Mechanics.grindstoneDamage(1561, 1461, 1361))
    assertEquals(0, Mechanics.grindstoneDamage(1561, 100, 50))
    assertEquals(listOf(0, 1, 3), (0..2).map(Mechanics::grindstoneRepairCost))
  }
}
