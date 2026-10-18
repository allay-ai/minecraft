package chat.allay.core

import chat.allay.core.tools.Storage
import chat.allay.core.tools.Storage.Category
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorageTest {
  data class Item(val id: String, val amount: Int, val meta: String = "", val max: Int = 64)

  private val stacks = object : Storage.Stacks<Item> {
    override fun id(item: Item) = item.id
    override fun amount(item: Item) = item.amount
    override fun max(item: Item) = item.max
    override fun similar(a: Item, b: Item) = a.id == b.id && a.meta == b.meta
    override fun withAmount(item: Item, amount: Int) = item.copy(amount = amount)
    override fun edible(item: Item) = item.id in setOf("bread", "cooked_beef", "golden_carrot")
  }

  private fun chest(key: String, size: Int, vararg items: Item?, assign: Storage.Assign? = null) =
    Storage.Chest(key, items.toList() + List(size - items.size) { null }, assign)

  private fun totals(plan: Storage.Plan<Item>) = plan.chests.values.flatten().filterNotNull().groupBy { it.id }.mapValues { (_, list) -> list.sumOf { it.amount } }

  private fun ids(slots: List<Item?>) = slots.filterNotNull().map { it.id }.toSet()

  @Test fun `categories follow the game's names`() {
    assertEquals(Category.ORES, Storage.category("iron_ingot"))
    assertEquals(Category.ORES, Storage.category("deepslate_diamond_ore"))
    assertEquals(Category.WOOD, Storage.category("spruce_planks"))
    assertEquals(Category.STONE, Storage.category("cobblestone"))
    assertEquals(Category.TOOLS, Storage.category("diamond_pickaxe"))
    assertEquals(Category.REDSTONE, Storage.category("repeater"))
    assertEquals(Category.FOOD, Storage.category("bread", edible = true))
    assertEquals(Category.MOB_DROPS, Storage.category("rotten_flesh", edible = true))
    assertEquals(Category.FARMING, Storage.category("wheat_seeds"))
    assertEquals(Category.POTIONS, Storage.category("splash_potion"))
    assertEquals(Category.OTHER, Storage.category("music_disc_cat"))
  }

  @Test fun `grouping puts one resource in one chest and keeps every total`() {
    val chests = listOf(
      chest("a", 3, Item("iron_ingot", 30), Item("bread", 10), Item("cobblestone", 64)),
      chest("b", 3, Item("bread", 5), Item("iron_ingot", 40), Item("cobblestone", 20)),
      chest("c", 3),
    )
    val plan = assertNotNull(Storage.group(chests, stacks))
    assertEquals(mapOf("iron_ingot" to 70, "bread" to 15, "cobblestone" to 84), totals(plan))
    // Each item lives in exactly one chest.
    listOf("iron_ingot", "bread", "cobblestone").forEach { id -> assertEquals(1, plan.chests.values.count { slots -> slots.any { it?.id == id } }, id) }
  }

  @Test fun `the biggest category takes the nearest chest`() {
    val chests = listOf(
      chest("near", 2, Item("bread", 3)),
      chest("far", 2, Item("cobblestone", 64), Item("cobblestone", 64)),
    )
    val plan = assertNotNull(Storage.group(chests, stacks))
    assertEquals(setOf("cobblestone"), ids(plan.chests["near"]!!))
    assertEquals(Category.STONE, plan.labels["near"])
    assertEquals(Category.FOOD, plan.labels["far"])
  }

  @Test fun `a category that doesn't fit flows into the next chest`() {
    val chests = listOf(
      chest("a", 2, Item("cobblestone", 64), Item("cobblestone", 64)),
      chest("b", 2, Item("cobblestone", 64), Item("bread", 1)),
      chest("c", 2),
    )
    val plan = assertNotNull(Storage.group(chests, stacks))
    assertEquals(setOf("cobblestone"), ids(plan.chests["a"]!!))
    assertTrue("cobblestone" in ids(plan.chests["b"]!!))
    assertEquals(setOf("bread"), ids(plan.chests["c"]!!))
  }

  @Test fun `a chest assigned by a sign or the player gets its category`() {
    val chests = listOf(
      chest("a", 2, Item("iron_ingot", 10)),
      chest("ores", 2, Item("bread", 4), assign = Storage.Assign(Category.ORES)),
    )
    val plan = assertNotNull(Storage.group(chests, stacks))
    assertEquals(setOf("iron_ingot"), ids(plan.chests["ores"]!!))
    assertEquals(setOf("bread"), ids(plan.chests["a"]!!))
  }

  @Test fun `an item frame's item comes first in its chest`() {
    val chests = listOf(
      chest("a", 2, Item("diamond", 5), Item("emerald", 3)),
      chest("frame", 1, assign = Storage.Assign(items = setOf("emerald"))),
    )
    val plan = assertNotNull(Storage.group(chests, stacks))
    assertEquals(listOf(Item("emerald", 3)), plan.chests["frame"])
  }

  @Test fun `items that differ in meta stay apart`() {
    val chests = listOf(chest("a", 3, Item("diamond_sword", 1, "sharpness", 1), Item("diamond_sword", 1, "", 1)))
    val plan = assertNotNull(Storage.group(chests, stacks))
    assertEquals(2, plan.chests["a"]!!.filterNotNull().size)
  }

  @Test fun `nothing is planned when everything together doesn't fit`() {
    val chests = listOf(chest("a", 1, Item("dirt", 64)), chest("b", 1, Item("stone", 64)))
    assertNotNull(Storage.group(chests, stacks))
    assertNull(Storage.group(listOf(chest("a", 1, Item("dirt", 100, max = 64))), stacks))
  }

  @Test fun `sorting each chest keeps them apart`() {
    val chests = listOf(chest("a", 3, Item("stone", 10), null, Item("stone", 10)), chest("b", 2, Item("dirt", 1)))
    val plan = assertNotNull(Storage.sortEach(chests, stacks, compareBy { it.id }))
    assertEquals(listOf(Item("stone", 20), null, null), plan.chests["a"])
    assertEquals(listOf(Item("dirt", 1), null), plan.chests["b"])
  }

  @Test fun `moving fills the targets, merging first, and reports what didn't fit`() {
    val chests = listOf(
      chest("source", 3, Item("iron_ingot", 40), Item("bread", 5), Item("iron_ingot", 64)),
      chest("target", 2, Item("iron_ingot", 60)),
    )
    val plan = Storage.move(chests, setOf("target"), { it.id == "iron_ingot" }, stacks)
    assertEquals(listOf(Item("iron_ingot", 64), Item("iron_ingot", 64)), plan.chests["target"])
    assertEquals(listOf(null, Item("bread", 5), Item("iron_ingot", 36)), plan.chests["source"])
    assertEquals(mapOf("iron_ingot" to 36), plan.left)
    assertEquals(mapOf("iron_ingot" to 164, "bread" to 5), totals(plan))
  }
}
