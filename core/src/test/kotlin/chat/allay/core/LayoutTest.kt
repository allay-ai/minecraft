package chat.allay.core

import chat.allay.core.tools.ItemData
import chat.allay.core.tools.Layout
import chat.allay.core.tools.Layout.Spec
import chat.allay.core.tools.Layout.Zone
import chat.allay.core.tools.Storage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LayoutTest {
  private val stacks = object : Storage.Stacks<ItemData> {
    override fun id(item: ItemData) = item.id
    override fun amount(item: ItemData) = item.count
    override fun max(item: ItemData) = if (item.maxDamage > 0 || item.id == "bundle") 1 else 64
    override fun similar(a: ItemData, b: ItemData) = a.copy(count = 0) == b.copy(count = 0)
    override fun withAmount(item: ItemData, amount: Int) = item.copy(count = amount)
    override fun edible(item: ItemData) = item.food != null
    override fun data(item: ItemData) = item
  }

  private fun item(id: String, count: Int = 64) = ItemData(id, count)
  private val bread = ItemData("bread", 20, food = ItemData.Food(5, 6.0))

  /** A 27-cell grid holding [items] in its first cells. */
  private fun grid(vararg items: ItemData?) = items.toList() + List(27 - items.size) { null }

  private fun lay(cells: List<ItemData?>, spec: Spec) = assertNotNull(Layout.lay(cells, emptyList(), emptySet(), spec, stacks))

  private fun ids(cells: List<ItemData?>) = cells.map { it?.id }

  @Test fun `materials take every form of a resource and nothing made of it`() {
    listOf("raw_iron", "iron_ingot", "iron_nugget", "iron_block", "raw_iron_block", "deepslate_iron_ore").forEach { assertTrue(Layout.matches("iron", item(it)), it) }
    listOf("iron_sword", "iron_door", "golden_carrot").forEach { assertTrue(!Layout.matches("iron", item(it)) && !Layout.matches("gold", item(it)), it) }
    assertTrue(Layout.matches("netherite", item("ancient_debris")))
    assertTrue(Layout.matches("redstone", item("redstone")))
    assertTrue(Layout.matches("ores", item("raw_gold")))
    assertTrue(Layout.matches("enchanted", ItemData("enchanted_book", 1, storedEnchantments = mapOf("mending" to 1))))
  }

  @Test fun `the order comes first with every stack, the rest after by name`() {
    val cells = grid(item("dirt"), item("raw_gold"), item("raw_iron"), item("iron_ingot", 10), item("arrow"), item("raw_iron"))
    val laid = lay(cells, Spec(order = listOf("iron", "gold")))
    assertEquals(listOf("iron_ingot", "raw_iron", "raw_iron", "raw_gold", "arrow", "dirt", null), ids(laid.cells).take(7))
  }

  @Test fun `a row zone takes its items and keeps its other cells for last`() {
    val cells = grid(item("dirt"), bread, item("stone"), item("arrow"))
    val laid = lay(cells, Spec(zones = listOf(Zone(listOf("food"), "row:last"))))
    assertEquals(listOf("arrow", "dirt", "stone", null), ids(laid.cells).take(4))
    assertEquals(listOf("bread", null), ids(laid.cells).subList(18, 20))
  }

  @Test fun `a column zone runs top to bottom and overflow flows on`() {
    val bundles = (1..4).map { ItemData("bundle", 1) }
    val laid = lay(grid(*(bundles + item("dirt")).toTypedArray()), Spec(zones = listOf(Zone(listOf("bundle"), "column:9"))))
    assertEquals(listOf("bundle", "bundle", "bundle"), listOf(8, 17, 26).map { laid.cells[it]?.id })
    assertEquals(listOf("bundle", "dirt"), ids(laid.cells).take(2))
  }

  @Test fun `start and end zones need no counting`() {
    val cells = grid(item("dirt"), ItemData("bundle", 1), item("stone"), ItemData("bundle", 1), item("arrow"))
    val laid = lay(cells, Spec(zones = listOf(Zone(listOf("bundle"), "start"), Zone(listOf("stone"), "end"))))
    assertEquals(listOf("bundle", "bundle", "arrow", "dirt", "stone", null), ids(laid.cells).take(6))
  }

  @Test fun `by category with rows starts each kind on a new row while there's room`() {
    val cells = grid(item("oak_log"), item("cobblestone"), bread, item("stone"))
    val laid = lay(cells, Spec(by = Layout.By.CATEGORY, rows = true))
    assertEquals("oak_log", laid.cells[0]?.id)
    assertEquals(listOf("cobblestone", "stone"), ids(laid.cells).subList(9, 11))
    assertEquals("bread", laid.cells[18]?.id)
    // Without room for the skips, the kinds sit side by side.
    val full = grid(*((1..25).map { item("stone") } + item("oak_log") + bread).toTypedArray())
    assertEquals(0, lay(full, Spec(rows = true)).extra.size)
  }

  @Test fun `by count puts the biggest pile first`() {
    val laid = lay(grid(item("arrow", 10), item("dirt"), item("dirt"), item("stone")), Spec(by = Layout.By.COUNT))
    assertEquals(listOf("dirt", "dirt", "stone", "arrow"), ids(laid.cells).take(4))
  }

  @Test fun `kept items stay where they are and take nothing`() {
    val book = ItemData("enchanted_book", 1, storedEnchantments = mapOf("mending" to 1))
    val cells = grid(item("dirt"), item("stone"), book, item("arrow"))
    val laid = lay(cells, Spec(keep = listOf("enchanted")))
    assertEquals(listOf("arrow", "dirt", "enchanted_book", "stone"), ids(laid.cells).take(4))
  }

  @Test fun `merge only pours stacks together and moves nothing else`() {
    val cells = grid(item("stone", 40), item("dirt", 5), item("stone", 40), null, item("dirt", 5))
    val laid = lay(cells, Spec(mergeOnly = true))
    assertEquals(listOf(64, 10, 16, null, null), laid.cells.take(5).map { it?.count })
    assertEquals(1, laid.merged)
  }

  @Test fun `zones name real cells`() {
    assertEquals((45..53).toList(), Layout.cells("row:last", 54))
    assertEquals(listOf(0, 1, 2), Layout.cells("slots:1-3", 27))
    assertNull(Layout.cells("row:4", 27))
    assertNull(Layout.problem(Spec(zones = listOf(Zone(listOf("food"), "end"))), 27))
    assertTrue(Layout.problem(Spec(zones = listOf(Zone(listOf("food"), "row:7"))), 54) != null)
  }

  @Test fun `grouped chests put the ordered category nearest`() {
    val chests = listOf(Storage.Chest<ItemData>("near", List(2) { null }), Storage.Chest("far", listOf(item("stone"), item("cobblestone"), item("andesite"), bread)))
    val plan = assertNotNull(Storage.group(chests, stacks, Spec(order = listOf("food"), by = Layout.By.CATEGORY)))
    assertEquals("bread", plan.chests["near"]!![0]?.id)
  }
}
