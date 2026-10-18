package chat.allay.core

import chat.allay.core.tasks.Kind
import chat.allay.core.tasks.Reservations
import chat.allay.core.tasks.TaskBook
import chat.allay.core.tools.ItemData
import chat.allay.core.tools.Pins
import chat.allay.core.tools.ToolError
import chat.allay.core.tools.Workshop
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AutomationTest {
  private val player = UUID.randomUUID()

  @Test fun `a player runs at most the configured number of tasks`() {
    val book = TaskBook({ 2 })
    book.start(player, Kind.CHESTS, "chests", "sort", 1, null)
    val second = book.start(player, Kind.FURNACES, "furnaces", "smelt", 1, null)
    assertFailsWith<ToolError> { book.start(player, Kind.BREWING, "brewing", "brew", 1, null) }
    book.end(second)
    assertNotNull(book.start(player, Kind.BREWING, "brewing", "brew", 1, null))
    assertEquals(2, book.of(player).size)
  }

  @Test fun `agents go two levels deep and no further`() {
    val book = TaskBook({ 8 })
    assertNotNull(book.start(player, Kind.BREWING, "child", "", 2, "x"))
    assertFailsWith<ToolError> { book.start(player, Kind.BREWING, "grandchild", "", 3, "y") }
  }

  @Test fun `reservations hide items from other tasks until taken or released`() {
    val holds = Reservations()
    holds.reserve("a", "world:1,2,3", "spider_eye", 2)
    assertEquals(2, holds.held("world:1,2,3", "spider_eye", except = "b"))
    assertEquals(0, holds.held("world:1,2,3", "spider_eye", except = "a"))
    holds.take("a", "world:1,2,3", "spider_eye", 1)
    assertEquals(1, holds.held("world:1,2,3", "spider_eye"))
    holds.release("a")
    assertEquals(0, holds.held("world:1,2,3", "spider_eye"))
  }

  @Test fun `the best item of a group takes the pinned slot`() {
    val items = mapOf(
      3 to ItemData("iron_sword", 1),
      12 to ItemData("netherite_sword", 1),
      20 to ItemData("diamond_sword", 1, enchantments = mapOf("sharpness" to 5)),
      7 to ItemData("water_bucket", 1),
      30 to ItemData("bread", 5, food = ItemData.Food(5, 6.0)),
      31 to ItemData("golden_carrot", 5, food = ItemData.Food(6, 14.4)),
    )
    val picked = Pins.pick(listOf(Pins.Pin(0, "sword"), Pins.Pin(1, "water_bucket"), Pins.Pin(8, "food"), Pins.Pin(2, "sword")), items)
    assertEquals(mapOf(0 to 12, 1 to 7, 8 to 31, 2 to 20), picked)
  }

  @Test fun `potions pin by their kind, the strongest first`() {
    fun bottle(id: String, base: String) = ItemData(id, 1, potion = ItemData.Potion(base, emptyList(), emptyList()))
    val items = mapOf(
      10 to bottle("potion", "fire_resistance"),
      11 to bottle("potion", "healing"),
      12 to bottle("potion", "strong_healing"),
      13 to bottle("splash_potion", "strong_healing"),
      14 to ItemData("golden_apple", 3, food = ItemData.Food(4, 9.6)),
    )
    val picked = Pins.pick(listOf(Pins.Pin(8, "potion:healing"), Pins.Pin(7, "splash_potion:healing"), Pins.Pin(6, "potion:fire_resistance"), Pins.Pin(5, "golden_apple")), items)
    assertEquals(mapOf(8 to 12, 7 to 13, 6 to 10, 5 to 14), picked)
  }

  /** Sorts [items] as `sort_inventory` does, items without durability stacking to 64, and returns every slot after it. */
  private fun arrange(items: Map<Int, ItemData>, pins: List<Pins.Pin>, hotbar: Boolean, block: (ItemData) -> Boolean = { false }): Map<Int, ItemData?> {
    val sorted = Pins.arrange(
      items, Pins.pick(pins, items, block), hotbar, offhand = false,
      data = { it }, amount = ItemData::count, maxStack = { if (it.maxDamage > 0 || it.id == "bundle") 1 else 64 },
      similar = { a, b -> a.copy(count = 0) == b.copy(count = 0) }, withAmount = { item, count -> item.copy(count = count) },
    )!!
    return items + sorted.slots
  }

  private fun tool(id: String) = ItemData(id, 1, maxDamage = 1561)

  @Test fun `a sorted hotbar holds the pins and leaves the rest to the main inventory`() {
    // The owner's inventory from 18 October: arrows and a bundle sat in slots 5 and 6 after every sort.
    val items = mapOf(
      0 to tool("diamond_sword"), 1 to tool("bow"), 2 to ItemData("water_bucket", 1), 3 to ItemData("cobbled_deepslate", 61),
      4 to ItemData("arrow", 56), 5 to ItemData("bundle", 1), 6 to tool("diamond_pickaxe"), 7 to ItemData("golden_carrot", 64, food = ItemData.Food(6, 14.4)),
      8 to ItemData("golden_apple", 16, food = ItemData.Food(4, 9.6)), 9 to ItemData("bundle", 1), 10 to ItemData("coal_block", 60),
      20 to ItemData("nether_bricks", 64), 23 to ItemData("raw_iron", 64),
    )
    val pins = listOf(Pins.Pin(0, "sword"), Pins.Pin(1, "bow"), Pins.Pin(2, "water_bucket"), Pins.Pin(3, "block"), Pins.Pin(6, "pickaxe"), Pins.Pin(7, "golden_carrot"), Pins.Pin(8, "golden_apple"))
    val after = arrange(items, pins, hotbar = true) { it.id in setOf("cobbled_deepslate", "nether_bricks", "coal_block") }
    assertEquals(listOf("diamond_sword", "bow", "water_bucket", "nether_bricks", null, null, "diamond_pickaxe", "golden_carrot", "golden_apple"), (0..8).map { after[it]?.id })
    assertEquals(listOf("arrow", "bundle", "bundle", "coal_block", "cobbled_deepslate", "raw_iron"), (9..35).mapNotNull { after[it]?.id })
  }

  @Test fun `the hotbar takes what the main inventory can't fit`() {
    val items = (9..35).associateWith { ItemData("stone", 64) } + mapOf(0 to tool("iron_sword"), 1 to ItemData("dirt", 10), 2 to ItemData("andesite", 5))
    val after = arrange(items, listOf(Pins.Pin(0, "sword")), hotbar = true)
    // 29 stacks for 35 free slots: andesite, dirt, and 27 stone; the last two stone spill into the hotbar.
    assertEquals(listOf("iron_sword", "stone", "stone", null, null, null, null, null, null), (0..8).map { after[it]?.id })
    assertEquals("andesite", after[9]?.id)
  }

  @Test fun `a sorted hotbar without pins keeps what's held in hand, weapons first`() {
    val items = mapOf(
      0 to ItemData("dirt", 30), 1 to ItemData("bread", 5, food = ItemData.Food(5, 6.0)), 2 to ItemData("arrow", 20),
      3 to tool("iron_pickaxe"), 4 to tool("stone_sword"), 5 to ItemData("torch", 40), 6 to ItemData("torch", 40),
    )
    val after = arrange(items, emptyList(), hotbar = true)
    assertEquals(listOf("stone_sword", "iron_pickaxe", "torch", "torch", "bread", null, null, null, null), (0..8).map { after[it]?.id })
    assertEquals(listOf(64, 16), listOf(after[2]?.count, after[3]?.count))
    assertEquals(listOf("arrow", "dirt"), (9..35).mapNotNull { after[it]?.id })
  }

  @Test fun `pins without the hotbar leave its other slots alone`() {
    val items = mapOf(0 to ItemData("dirt", 30), 4 to ItemData("arrow", 20), 12 to tool("iron_sword"))
    val after = arrange(items, listOf(Pins.Pin(0, "sword")), hotbar = false)
    assertEquals(listOf("iron_sword", null, null, null, "arrow"), (0..4).map { after[it]?.id })
    assertEquals("dirt", after[9]?.id)
    assertNull(after[12])
  }

  @Test fun `pins reach the main inventory and its slot's item joins the sort`() {
    val items = mapOf(0 to ItemData("bundle", 1), 9 to ItemData("arrow", 20), 15 to ItemData("bundle", 1), 30 to ItemData("bundle", 1), 31 to ItemData("dirt", 5))
    val pins = (1..3).map { Pins.Pin(Pins.slot("inventory:$it")!!, "bundle") }
    val after = arrange(items, pins, hotbar = false)
    assertEquals(listOf("bundle", "bundle", "bundle", "arrow", "dirt"), (9..13).map { after[it]?.id })
    assertNull(after[0])
    assertEquals(listOf(8, 9, 35, Pins.OFFHAND, null, null), listOf("9", "inventory:1", "inventory:27", "offhand", "inventory:28", "10").map(Pins::slot))
    assertEquals("inventory:1", Pins.name(9))
  }

  @Test fun `a block pin takes the fullest stack`() {
    val items = mapOf(3 to ItemData("cobbled_deepslate", 61), 20 to ItemData("nether_bricks", 64))
    assertEquals(mapOf(3 to 20), Pins.pick(listOf(Pins.Pin(3, "block")), items) { true })
  }

  @Test fun `a pin nothing matches is left out`() {
    assertEquals(emptyMap(), Pins.pick(listOf(Pins.Pin(0, "trident")), mapOf(1 to ItemData("dirt", 1))))
  }

  @Test fun `fuel covers exactly the load`() {
    assertEquals(1, Workshop.fuelFor(8, "coal"))
    assertEquals(2, Workshop.fuelFor(9, "coal"))
    assertEquals(0, Workshop.fuelFor(8, "coal", banked = 1600))
    assertEquals(1, Workshop.fuelFor(64, "coal_block"))
    assertEquals(6, Workshop.fuelFor(9, "oak_log"))
    assertEquals(0, Workshop.fuelFor(9, "dirt"))
  }

  @Test fun `a load spreads evenly over furnaces`() {
    assertEquals(listOf(4, 3, 3), Workshop.spread(10, 3))
    assertEquals(listOf(64, 64), Workshop.spread(200, 2))
    assertEquals(listOf(1, 0, 0), Workshop.spread(1, 3))
  }

  @Test fun `two ores split over furnaces so they finish together`() {
    val furnace = Workshop.Oven(mapOf("raw_iron" to 1, "raw_gold" to 1, "beef" to 1))
    val blast = Workshop.Oven(mapOf("raw_iron" to 2, "raw_gold" to 2))
    val parts = Workshop.allot(mapOf("raw_iron" to 64, "raw_gold" to 32), listOf(furnace, furnace, blast))!!
    // The blast furnace takes iron at double speed with a furnace beside it; the other furnace takes the gold.
    assertEquals(Workshop.Part("raw_iron", 43), parts[2])
    assertEquals(setOf(Workshop.Part("raw_iron", 21), Workshop.Part("raw_gold", 32)), setOf(parts[0], parts[1]))
  }

  @Test fun `a blast furnace takes its fair share of one item`() {
    val parts = Workshop.allot(mapOf("raw_iron" to 30), listOf(Workshop.Oven(mapOf("raw_iron" to 1)), Workshop.Oven(mapOf("raw_iron" to 2))))!!
    assertEquals(listOf(Workshop.Part("raw_iron", 10), Workshop.Part("raw_iron", 20)), parts)
  }

  @Test fun `an item no furnace cooks makes no plan`() {
    assertNull(Workshop.allot(mapOf("beef" to 5), listOf(Workshop.Oven(mapOf("raw_iron" to 2)))))
  }

  private val recipes: (String) -> List<Workshop.Recipe> = { id ->
    when (id) {
      "diamond_pickaxe" -> listOf(Workshop.Recipe(id, 1, listOf(setOf("diamond"), setOf("diamond"), setOf("diamond"), setOf("stick"), setOf("stick"))))
      "stick" -> listOf(Workshop.Recipe(id, 4, listOf(setOf("oak_planks", "birch_planks"), setOf("oak_planks", "birch_planks"))))
      "oak_planks" -> listOf(Workshop.Recipe(id, 4, listOf(setOf("oak_log"))))
      "birch_planks" -> listOf(Workshop.Recipe(id, 4, listOf(setOf("birch_log"))))
      else -> emptyList()
    }
  }

  @Test fun `crafting makes the parts it lacks`() {
    val plan = assertIs<Workshop.Plan>(Workshop.plan("diamond_pickaxe", 1, mapOf("diamond" to 3, "oak_log" to 1), recipes))
    assertEquals(mapOf("diamond" to 3, "oak_log" to 1), plan.takes)
    assertEquals(listOf("oak_planks", "stick", "diamond_pickaxe"), plan.steps.map { it.recipe.result })
    // A log gives four planks, two make four sticks: two planks and two sticks are left over.
    assertEquals(mapOf("diamond_pickaxe" to 1, "oak_planks" to 2, "stick" to 2), plan.makes)
  }

  @Test fun `crafting says what's missing`() {
    val missing = assertIs<Workshop.Missing>(Workshop.plan("diamond_pickaxe", 1, mapOf("diamond" to 2, "stick" to 2), recipes))
    assertEquals(mapOf("diamond" to 1), missing.items)
  }

  @Test fun `crafting uses what the player has before crafting parts`() {
    val plan = assertIs<Workshop.Plan>(Workshop.plan("diamond_pickaxe", 1, mapOf("diamond" to 3, "stick" to 5), recipes))
    assertEquals(mapOf("diamond" to 3, "stick" to 2), plan.takes)
    assertEquals(listOf("diamond_pickaxe"), plan.steps.map { it.recipe.result })
  }

  private fun quota(text: String) = QuotaSettings.parse(YamlConfig(YamlConfig.parse(text)))

  @Test fun `quota tiers are weights and operators get the biggest`() {
    val settings = quota(
      """
      quota:
        enabled: true
        monthly_usd: 20
        tiers:
          default: 1
          vip: "3"
      """.trimIndent(),
    )
    assertNull(settings.error)
    assertEquals(1.0, settings.weight(false) { false })
    assertEquals(3.0, settings.weight(false) { it == "vip" })
    assertEquals(3.0, settings.weight(true) { false })
  }

  @Test fun `a broken quota config says why`() {
    assertNotNull(quota("quota:\n  enabled: true\n  monthly_usd: 10\n  tiers:\n    a: 0").error)
    assertNotNull(quota("quota:\n  enabled: true\n  monthly_usd: 10\n  tiers:\n    a: lots").error)
    assertNotNull(quota("quota:\n  enabled: true\n  tiers:\n    a: 1").error)
    assertNull(quota("quota:\n  enabled: false\n  tiers:\n    a: 0").error)
    // Weights add up to anything: nothing is booked, so there's no 100% to pass.
    assertNull(quota("quota:\n  enabled: true\n  monthly_usd: 10\n  tiers:\n    a: 5\n    b: 50").error)
  }

  @Test fun `limits split what's unlocked among the players who asked`() {
    // A VIP alone gets everything; with a regular player it's 75 to 25; a third player makes it 60, 20, 20.
    assertEquals(20.0, Quotas.limit(20.0, 3.0, 3.0))
    assertEquals(15.0, Quotas.limit(20.0, 3.0, 4.0))
    assertEquals(5.0, Quotas.limit(20.0, 1.0, 4.0))
    assertEquals(12.0, Quotas.limit(20.0, 3.0, 5.0))
  }

  @Test fun `a daily budget unlocks the month day by day`() {
    val settings = quota("quota:\n  enabled: true\n  monthly_usd: 31\n  tiers:\n    default: 1")
    assertEquals(10.0, settings.unlocked(java.time.ZonedDateTime.of(2026, 10, 10, 12, 0, 0, 0, java.time.ZoneOffset.UTC)), 1e-9)
    val monthly = quota("quota:\n  enabled: true\n  daily: false\n  monthly_usd: 31\n  tiers:\n    default: 1")
    assertEquals(31.0, monthly.unlocked(java.time.ZonedDateTime.of(2026, 10, 1, 0, 0, 0, 0, java.time.ZoneOffset.UTC)))
  }

  @Test fun `the book counts each active player's weight once`() {
    val folder = kotlin.io.path.createTempDirectory().toFile()
    val book = QuotaBook(folder, java.util.logging.Logger.getAnonymousLogger()) { java.time.ZoneOffset.UTC }
    val vip = UUID.randomUUID()
    assertEquals(3.0, book.active(vip, 3.0))
    assertEquals(3.0, book.active(vip, 3.0))
    assertEquals(4.0, book.active(player, 1.0))
    book.add(vip, 2.5)
    assertEquals(2.5, book.player(vip))
    assertEquals(2.5, book.server())
  }

  @Test fun `models go by the first tier a player holds`() {
    val models = ModelTiers.parse(YamlConfig(YamlConfig.parse("models:\n  vip: \"a/big\"\n  p10: \"a/small\"")))
    assertEquals("a/small", models.of(false) { it == "p10" })
    assertEquals("a/big", models.of(false) { true })
    assertEquals("a/big", models.of(true) { false })
    assertNull(models.of(false) { false })
  }
}
