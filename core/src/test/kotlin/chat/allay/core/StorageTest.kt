package chat.allay.core

import chat.allay.core.tools.Layout
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
    val plan = assertNotNull(Storage.sortEach(chests, stacks, Layout.Spec()))
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

  @Test fun `a move takes no more than its limit and leaves fixed slots alone`() {
    val chests = listOf(
      chest("inventory", 4, Item("torch", 10), Item("torch", 30), Item("torch", 30)),
      chest("target", 3),
    )
    val plan = Storage.move(chests, setOf("target"), { it.id == "torch" }, stacks, limit = 35, fixed = mapOf("inventory" to setOf(0)))
    assertEquals(listOf(Item("torch", 10), null, Item("torch", 25), null), plan.chests["inventory"])
    assertEquals(35, plan.chests["target"]!!.filterNotNull().sumOf { it.amount })
    assertEquals(emptyMap(), plan.left)
  }

  @Test fun `taking into the inventory merges, then fills the main rows before the hotbar`() {
    val chests = listOf(
      chest("chest", 2, Item("bread", 64), Item("bread", 20)),
      chest("inventory", 4, null, Item("bread", 60)),
    )
    // Slots 2 and 3 stand for the main inventory, 0 for the hotbar.
    val plan = Storage.move(chests, setOf("inventory"), { true }, stacks, order = mapOf("inventory" to listOf(2, 3, 0)))
    assertEquals(listOf(null, Item("bread", 64), Item("bread", 64), Item("bread", 16)), plan.chests["inventory"])
  }

  @Test fun `a target that refuses an item takes none of it`() {
    val chests = listOf(chest("source", 1, Item("shulker_box", 1, max = 1)), chest("box", 2))
    val plan = Storage.move(chests, setOf("box"), { true }, stacks, accepts = { _, item -> item.id != "shulker_box" })
    assertEquals(listOf(Item("shulker_box", 1, max = 1)), plan.chests["source"])
    assertEquals(mapOf("shulker_box" to 1), plan.left)
  }

  @Test fun `stashing puts each item where it already lies, then where a sign says`() {
    val inventory = chest("inventory", 5, Item("diamond_sword", 1, max = 1), Item("cobblestone", 40), Item("iron_ingot", 10), Item("bread", 5), Item("music_disc_cat", 1, max = 1))
    val chests = listOf(
      chest("stone", 2, Item("cobblestone", 50)),
      chest("ores", 2, assign = Storage.Assign(Category.ORES)),
      chest("food", 1, Item("bread", 64)),
    )
    val plan = Storage.stash(inventory, chests, stacks, fixed = setOf(0))
    assertEquals(listOf(Item("cobblestone", 64), Item("cobblestone", 26)), plan.chests["stone"])
    assertEquals(listOf(Item("iron_ingot", 10), null), plan.chests["ores"])
    // The sword sits in a fixed slot, the bread has no room, and nothing is for the disc.
    assertEquals(listOf(Item("diamond_sword", 1, max = 1), null, null, Item("bread", 5), Item("music_disc_cat", 1, max = 1)), plan.chests["inventory"])
    assertEquals(mapOf("bread" to 5), plan.left)
  }

  @Test fun `a plan keeps every total or isn't balanced`() {
    val before = mapOf(
      "a" to listOf(Item("iron_ingot", 30), Item("diamond_sword", 1, "sharpness", max = 1), null),
      "inventory" to listOf(null, Item("iron_ingot", 10)),
    )
    val moved = mapOf(
      "a" to listOf(null, null, Item("diamond_sword", 1, "sharpness", max = 1)),
      "inventory" to listOf(Item("iron_ingot", 40), null),
    )
    assertTrue(Storage.balanced(before, moved, stacks))
    // One chest planned twice: the same items counted again.
    assertTrue(!Storage.balanced(before, moved + ("inventory" to listOf(Item("iron_ingot", 70), null)), stacks))
    // Lost, or swapped for another kind of the same id.
    assertTrue(!Storage.balanced(before, moved + ("a" to listOf(null, null, null)), stacks))
    assertTrue(!Storage.balanced(before, moved + ("a" to listOf(null, null, Item("diamond_sword", 1, max = 1))), stacks))
    // Another chest, another size, an empty stack.
    assertTrue(!Storage.balanced(before, moved - "a" + ("b" to moved["a"]!!), stacks))
    assertTrue(!Storage.balanced(before, moved + ("a" to listOf(null, Item("diamond_sword", 1, "sharpness", max = 1))), stacks))
    assertTrue(!Storage.balanced(before, moved + ("a" to listOf(Item("dirt", 0), null, Item("diamond_sword", 1, "sharpness", max = 1))), stacks))
  }

  @Test fun `every planner keeps every total`() {
    val chests = listOf(
      chest("a", 3, Item("iron_ingot", 30), Item("bread", 10), Item("cobblestone", 64)),
      chest("b", 3, Item("bread", 5), Item("iron_ingot", 40)),
    )
    val inventory = chest("inventory", 4, Item("cobblestone", 3), Item("iron_ingot", 2))
    val before = (chests + inventory).associate { it.key to it.slots }
    val chestsOnly = chests.associate { it.key to it.slots }
    assertTrue(Storage.balanced(chestsOnly, assertNotNull(Storage.group(chests, stacks)).chests, stacks))
    assertTrue(Storage.balanced(chestsOnly, assertNotNull(Storage.sortEach(chests, stacks)).chests, stacks))
    assertTrue(Storage.balanced(before, Storage.move(chests + inventory, setOf("inventory"), { it.id == "iron_ingot" }, stacks).chests, stacks))
    assertTrue(Storage.balanced(before, Storage.stash(inventory, chests, stacks).chests, stacks))
  }

  @Test fun `each item may have a limit of its own`() {
    val chests = listOf(
      chest("chest", 3, Item("lapis_lazuli", 64), Item("coal", 5), Item("coal", 20)),
      chest("inventory", 3),
    )
    val part = { item: Item -> mapOf("lapis_lazuli" to 0, "coal" to 1)[item.id] }
    val plan = Storage.move(chests, setOf("inventory"), { true }, stacks, share = part, shares = mapOf(0 to 6, 1 to 8))
    assertEquals(mapOf("lapis_lazuli" to 6, "coal" to 8), plan.chests["inventory"]!!.filterNotNull().groupBy { it.id }.mapValues { (_, l) -> l.sumOf { it.amount } })
    assertEquals(listOf(Item("lapis_lazuli", 58), null, Item("coal", 17)), plan.chests["chest"])
  }
}
