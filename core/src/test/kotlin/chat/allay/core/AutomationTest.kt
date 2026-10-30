package chat.allay.core

import chat.allay.core.tasks.Kind
import chat.allay.core.tasks.Reservations
import chat.allay.core.tasks.Task
import chat.allay.core.tasks.TaskBook
import chat.allay.core.tools.ItemData
import chat.allay.core.tools.Layout
import chat.allay.core.tools.Pins
import chat.allay.core.tools.ToolError
import chat.allay.core.tools.Workshop
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

  @Test fun `a parent at the task limit hears to wait for its own sub-agent`() {
    val book = TaskBook({ 2 })
    val parent = book.start(player, Kind.BREWING, "Зелья", "brew", 1, null)
    val child = book.start(player, Kind.FURNACES, "Стекло", "smelt sand", 2, parent.id)
    assertEquals(listOf(child), book.children(parent))
    val error = assertFailsWith<ToolError> { book.start(player, Kind.CRAFTING, "Бутылки", "craft bottles", 2, parent.id) }
    assertTrue("«Стекло» (${child.id}) still runs" in error.message!!)
    book.end(child)
    assertTrue(book.children(parent).isEmpty())
  }

  @Test fun `no two players' running tasks share an id`() {
    val ids = ArrayDeque(listOf("aaaa", "aaaa", "bbbb"))
    val book = TaskBook({ 2 }, newId = { ids.removeFirst() })
    assertEquals("aaaa", book.start(player, Kind.CHESTS, "a", "", 1, null).id)
    assertEquals("bbbb", book.start(UUID.randomUUID(), Kind.CHESTS, "b", "", 1, null).id)
  }

  @Test fun `a wake takes what fits and leaves the rest for the next one`() {
    val task = TaskBook({ 2 }).start(player, Kind.CHESTS, "a", "", 1, null)
    task.inbox += "[job] " + "x".repeat(1500)
    task.inbox += "[sub-agent] " + "y".repeat(700)
    task.inbox += "[timer] the wait is over"
    assertEquals(1506, task.drain().length)
    assertEquals("[sub-agent] " + "y".repeat(700) + "\n[timer] the wait is over", task.drain())
    assertEquals("", task.drain())
    task.inbox += "z".repeat(3000)
    assertEquals(2000, task.drain().length)
  }

  @Test fun `a question stays open until the player answers, and news isn't a question`() {
    val task = TaskBook({ 2 }).start(player, Kind.FURNACES, "a", "", 1, null)
    task.begin("smelt the iron")
    task.waited = true
    task.settle("Плавлю 64 руды, это минуты три", prompted = false, busy = true)
    assertEquals(Task.State.WAITING, task.state)
    assertNull(task.question)

    task.begin("[job] smelting done")
    task.waited = true
    task.settle("Куда положить слитки?", prompted = false, busy = false)
    assertEquals(Task.State.ASKING, task.state)
    assertEquals("Куда положить слитки?", task.question)

    // A timer in between doesn't lose the question.
    task.begin("[timer] the wait is over")
    task.waited = true
    task.settle(null, prompted = false, busy = false)
    assertEquals(Task.State.ASKING, task.state)
    assertEquals("Куда положить слитки?", task.question)

    task.begin("[player] в инвентарь")
    assertNull(task.question)
    task.waited = true
    task.settle(null, prompted = false, busy = false)
    assertEquals(Task.State.WAITING, task.state)

    // Ending a wake without wait waits for the player, unless a job or a sub-agent will wake it.
    task.begin("[job] picking: done")
    task.settle(null, prompted = false, busy = true)
    assertEquals(Task.State.WAITING, task.state)
    task.settle(null, prompted = false, busy = false)
    assertEquals(Task.State.ASKING, task.state)
  }

  @Test fun `a sub-agent's report carries what its tools did`() {
    val task = TaskBook({ 2 }).start(player, Kind.CRAFTING, "Бутылки", "", 2, "p1")
    assertEquals("[sub-agent] «Бутылки» (crafting, ${task.id}) finished: готово", task.outcome("finished: готово"))
    repeat(7) { task.report("crafted ${it + 1} × glass_bottle into the player's inventory") }
    val report = task.outcome("finished: 3 бутылки")
    assertTrue(report.startsWith("[sub-agent] «Бутылки» (crafting, ${task.id}) finished: 3 бутылки\nWhat its tools reported: crafted 2 × glass_bottle"))
    assertTrue(report.endsWith("crafted 7 × glass_bottle into the player's inventory"))
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

  /** Items without durability stack to 64, as in the game. */
  private val stacks = object : chat.allay.core.tools.Storage.Stacks<ItemData> {
    override fun id(item: ItemData) = item.id
    override fun amount(item: ItemData) = item.count
    override fun max(item: ItemData) = if (item.maxDamage > 0 || item.id == "bundle") 1 else 64
    override fun similar(a: ItemData, b: ItemData) = a.copy(count = 0) == b.copy(count = 0)
    override fun withAmount(item: ItemData, amount: Int) = item.copy(count = amount)
    override fun edible(item: ItemData) = item.food != null
    override fun data(item: ItemData) = item
  }

  /** Sorts [items] as `sort_inventory` does and returns every slot after it. */
  private fun arrange(
    items: Map<Int, ItemData>, pins: List<Pins.Pin>, hotbar: Boolean, spec: chat.allay.core.tools.Layout.Spec = chat.allay.core.tools.Layout.Spec(),
    block: (ItemData) -> Boolean = { false },
  ): Map<Int, ItemData?> = items + Pins.arrange(items, Pins.pick(pins, items, block), hotbar, offhand = false, spec, stacks, block)!!.slots

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

  @Test fun `sticks, bamboo, and lava burn as in the game, and nether wood doesn't`() {
    assertEquals(2, Workshop.fuelFor(1, "stick"))
    assertEquals(4, Workshop.fuelFor(1, "bamboo"))
    assertEquals(1, Workshop.fuelFor(100, "lava_bucket"))
    assertEquals(2, Workshop.fuelFor(101, "lava_bucket"))
    assertEquals(300, Workshop.burnTicks("bamboo_block"))
    assertNull(Workshop.burnTicks("crimson_planks"))
    assertNull(Workshop.burnTicks("warped_stem"))
  }

  @Test fun `a load spreads evenly over furnaces`() {
    assertEquals(listOf(4, 3, 3), Workshop.spread(10, 3))
    assertEquals(listOf(64, 64), Workshop.spread(200, 2))
    assertEquals(listOf(1, 0, 0), Workshop.spread(1, 3))
  }

  @Test fun `fuel spreads evenly within each furnace's room`() {
    assertEquals(listOf(2, 4, 4), Workshop.fill(10, listOf(2, 9, 9)))
    assertEquals(listOf(6, 6, 6), Workshop.fill(18, listOf(64, 64, 64)))
    // What doesn't fit stays out.
    assertEquals(listOf(1, 2), Workshop.fill(10, listOf(1, 2)))
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

  @Test fun `crafting goes four levels down`() {
    // A chain of parts, each made of the one before: d from c, c from b, b from a.
    val chain: (String) -> List<Workshop.Recipe> = { id ->
      mapOf("d" to "c", "c" to "b", "b" to "a", "a" to "log")[id]?.let { listOf(Workshop.Recipe(id, 1, listOf(setOf(it)))) }.orEmpty()
    }
    val plan = assertIs<Workshop.Plan>(Workshop.plan("d", 1, mapOf("log" to 1), chain))
    assertEquals(listOf("a", "b", "c", "d"), plan.steps.map { it.recipe.result })
    // Three levels stop at a, and name it as missing.
    assertEquals(mapOf("a" to 1), assertIs<Workshop.Missing>(Workshop.plan("d", 1, mapOf("log" to 1), chain, depth = 3)).items)
  }

  @Test fun `what's missing never goes through a block of the item`() {
    val blocks: (String) -> List<Workshop.Recipe> = { id ->
      when (id) {
        "diamond" -> listOf(Workshop.Recipe(id, 9, listOf(setOf("diamond_block"))))
        "diamond_block" -> listOf(Workshop.Recipe(id, 1, List(9) { setOf("diamond") }))
        else -> recipes(id)
      }
    }
    assertEquals(mapOf("diamond" to 1), assertIs<Workshop.Missing>(Workshop.plan("diamond_pickaxe", 1, mapOf("diamond" to 2, "stick" to 2), blocks)).items)
    // A block the player has still breaks into diamonds.
    val plan = assertIs<Workshop.Plan>(Workshop.plan("diamond_pickaxe", 1, mapOf("diamond_block" to 1, "stick" to 2), blocks))
    assertEquals(mapOf("diamond_block" to 1, "stick" to 2), plan.takes)
  }

  @Test fun `what's missing counts what the player has once`() {
    val iron: (String) -> List<Workshop.Recipe> = { id ->
      when (id) {
        "hopper" -> listOf(Workshop.Recipe(id, 1, List(5) { setOf("iron_ingot") }))
        "iron_ingot" -> listOf(Workshop.Recipe(id, 1, listOf(setOf("raw_iron")), Workshop.Station.FURNACE))
        else -> emptyList()
      }
    }
    // Ten hoppers take fifty ingots; seven raw iron leave forty-three short.
    assertEquals(mapOf("raw_iron" to 43), assertIs<Workshop.Missing>(Workshop.plan("hopper", 10, mapOf("raw_iron" to 7), iron)).items)
    val plan = assertIs<Workshop.Plan>(Workshop.plan("hopper", 1, mapOf("raw_iron" to 7), iron))
    assertEquals(mapOf("raw_iron" to 5), plan.smelts)
  }

  @Test fun `water bottles fill from glass bottles crafted from glass for a plugin's can`() {
    val book: (String) -> List<Workshop.Recipe> = { id ->
      when (id) {
        "redbull:original" -> listOf(Workshop.Recipe(id, 1, listOf(setOf("redbull:taurine"), setOf("blue_dye"), setOf("redbull:taurine"), setOf("sugar"), setOf("redbull:empty_can"), setOf("sugar"), setOf("sugar"), setOf(Workshop.WATER_BOTTLE), setOf("sugar"))))
        "redbull:empty_can" -> listOf(Workshop.Recipe(id, 1, listOf(setOf("iron_nugget"), setOf("iron_nugget"), setOf("iron_nugget"), setOf("iron_nugget"), setOf("copper_nugget"))))
        "copper_nugget" -> listOf(Workshop.Recipe(id, 9, listOf(setOf("copper_ingot")), small = true))
        Workshop.WATER_BOTTLE -> listOf(Workshop.FILL)
        "glass_bottle" -> listOf(Workshop.Recipe(id, 3, listOf(setOf("glass"), setOf("glass"), setOf("glass"))))
        else -> emptyList()
      }
    }
    val have = mapOf("redbull:taurine" to 2, "blue_dye" to 1, "sugar" to 4, "iron_nugget" to 4, "copper_ingot" to 1, "glass" to 3)
    val plan = assertIs<Workshop.Plan>(Workshop.plan("redbull:original", 1, have, book))
    assertEquals(listOf("copper_nugget", "redbull:empty_can", "glass_bottle", Workshop.WATER_BOTTLE, "redbull:original"), plan.steps.map { it.recipe.result })
    assertEquals(1, plan.fills)
    assertEquals(mapOf("redbull:original" to 1, "copper_nugget" to 8, "glass_bottle" to 2), plan.makes)
    assertTrue(Workshop.water("potion:water") && Workshop.water("Water Bottle") && Workshop.water("minecraft:water_bottle"))
    assertFalse(Workshop.water("water_bucket"))
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

  private val woods: (String) -> List<Workshop.Recipe> = { id ->
    val planks = setOf("dark_oak_planks", "oak_planks", "jungle_planks")
    when (id) {
      "chest" -> listOf(Workshop.Recipe(id, 1, List(8) { planks }))
      "crafting_table" -> listOf(Workshop.Recipe(id, 1, List(4) { planks }, small = true))
      "dark_oak_planks", "oak_planks", "jungle_planks" -> listOf(Workshop.Recipe(id, 4, listOf(setOf(id.replace("planks", "log"))), small = true))
      else -> emptyList()
    }
  }

  private val copies: (String) -> List<Workshop.Recipe> = { id ->
    when (id) {
      "netherite_upgrade_smithing_template" -> listOf(Workshop.Recipe(id, 2, listOf(setOf(id), setOf("netherrack")) + List(7) { setOf("diamond") }))
      "diamond" -> listOf(Workshop.Recipe(id, 9, listOf(setOf("diamond_block")), small = true))
      "diamond_block" -> listOf(Workshop.Recipe(id, 1, List(9) { setOf("diamond") }))
      "lapis_lazuli" -> listOf(Workshop.Recipe(id, 9, listOf(setOf("lapis_block")), small = true), Workshop.Recipe(id, 1, listOf(setOf("lapis_ore")), Workshop.Station.FURNACE))
      "lapis_block" -> listOf(Workshop.Recipe(id, 1, List(9) { setOf("lapis_lazuli") }))
      else -> emptyList()
    }
  }

  @Test fun `a template copies from the player's own, with diamonds from a block`() {
    val plan = assertIs<Workshop.Plan>(Workshop.plan("netherite_upgrade_smithing_template", 1, mapOf("netherite_upgrade_smithing_template" to 1, "netherrack" to 1, "diamond_block" to 1), copies))
    assertEquals(1, plan.takes["netherite_upgrade_smithing_template"])
    assertEquals(listOf("diamond", "netherite_upgrade_smithing_template"), plan.steps.map { it.recipe.result })
  }

  @Test fun `a template short of diamonds names the diamonds, not the template`() {
    val missing = assertIs<Workshop.Missing>(Workshop.plan("netherite_upgrade_smithing_template", 1, mapOf("netherite_upgrade_smithing_template" to 1, "netherrack" to 1, "diamond" to 3), copies))
    assertEquals(mapOf("diamond" to 4), missing.items)
  }

  @Test fun `a template with none to copy from says the template`() {
    val missing = assertIs<Workshop.Missing>(Workshop.plan("netherite_upgrade_smithing_template", 1, mapOf("netherrack" to 1, "diamond" to 7), copies))
    assertEquals(setOf("netherite_upgrade_smithing_template"), missing.items.keys)
  }

  @Test fun `lapis comes from blocks, and only what's short needs crafting`() {
    // 5 loose and 2 blocks: the 15 the player lacks of 20 come from the blocks.
    val plan = assertIs<Workshop.Plan>(Workshop.plan("lapis_lazuli", 15, mapOf("lapis_lazuli" to 5, "lapis_block" to 2), copies))
    assertEquals(mapOf("lapis_block" to 2), plan.takes)
    // 20 more than they have would need a third block from 9 loose lapis, and 5 are there: 4 short, not 20.
    val missing = assertIs<Workshop.Missing>(Workshop.plan("lapis_lazuli", 20, mapOf("lapis_lazuli" to 5, "lapis_block" to 2), copies))
    assertEquals(mapOf("lapis_lazuli" to 4), missing.items)
  }

  @Test fun `planks come from the wood the player carries`() {
    val plan = assertIs<Workshop.Plan>(Workshop.plan("chest", 8, mapOf("jungle_log" to 37), woods))
    assertEquals(mapOf("jungle_log" to 16), plan.takes)
    assertEquals(listOf("jungle_planks", "chest"), plan.steps.map { it.recipe.result })
    assertTrue(plan.needsTable)
  }

  @Test fun `planks of several woods add up`() {
    val plan = assertIs<Workshop.Plan>(Workshop.plan("chest", 8, mapOf("oak_log" to 6, "jungle_log" to 10, "dark_oak_planks" to 3), woods))
    // 64 planks: 3 already there, 61 from 16 logs, with 3 left over.
    assertEquals(16, plan.takes.filterKeys { it.endsWith("_log") }.values.sum())
    assertEquals(3, plan.takes["dark_oak_planks"])
    assertEquals(3, plan.makes.filterKeys { it.endsWith("_planks") }.values.sum())
  }

  @Test fun `a crafting table needs no crafting table`() {
    val plan = assertIs<Workshop.Plan>(Workshop.plan("crafting_table", 1, mapOf("oak_log" to 1), woods))
    assertEquals(mapOf("oak_log" to 1), plan.takes)
    assertFalse(plan.needsTable)
  }

  @Test fun `a stonecutter beats the table`() {
    val stone: (String) -> List<Workshop.Recipe> = { id ->
      when (id) {
        "stone_brick_stairs" -> listOf(
          Workshop.Recipe(id, 4, List(6) { setOf("stone_bricks") }),
          Workshop.Recipe(id, 1, listOf(setOf("stone_bricks")), Workshop.Station.STONECUTTER),
        )
        else -> emptyList()
      }
    }
    val plan = assertIs<Workshop.Plan>(Workshop.plan("stone_brick_stairs", 4, mapOf("stone_bricks" to 10), stone))
    assertEquals(mapOf("stone_bricks" to 4), plan.takes)
    assertEquals(setOf(Workshop.Station.STONECUTTER), plan.stations)
    assertFalse(plan.needsTable)
  }

  @Test fun `too little wood is missing`() {
    assertIs<Workshop.Missing>(Workshop.plan("chest", 8, mapOf("jungle_log" to 15), woods))
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

  @Test fun `a potion selector finds every form of that potion`() {
    val splash = ItemData("splash_potion", 1, potion = ItemData.Potion("infestation", emptyList(), emptyList()))
    val drink = ItemData("potion", 1, potion = ItemData.Potion("long_infestation", emptyList(), emptyList()))
    assertTrue(Layout.matches("potion:infestation", splash))
    assertTrue(Layout.matches("potion:infestation", drink))
    assertFalse(Layout.matches("splash_potion:infestation", drink))
    assertFalse(Layout.matches("potion:poison", splash))
  }

  @Test fun `selectors take the words players use for kinds of items`() {
    assertTrue(Layout.matches("log", ItemData("oak_log", 1)))
    assertTrue(Layout.matches("logs", ItemData("dark_oak_log", 1)))
    assertTrue(Layout.matches("planks", ItemData("birch_planks", 1)))
    assertTrue(Layout.matches("ingot", ItemData("iron_ingot", 1)))
    assertFalse(Layout.matches("log", ItemData("stick", 1)))
    assertFalse(Layout.matches("iron_ingot", ItemData("iron_block", 1)))
  }

  @Test fun `only a backend that never started a request is tried again`() {
    assertEquals(15L, Retry.after(java.io.IOException(java.net.ConnectException("refused"))))
    assertEquals(15L, Retry.after(ApiErrorException(ApiError("HTTP_ERROR", "bad gateway", statusCode = 502))))
    assertNull(Retry.after(ApiErrorException(ApiError("AI_ERROR", "the model failed"))))
    assertNull(Retry.after(IllegalStateException("mid-stream")))
  }

  @Test fun `an agent may go on once from a wake that broke off midway, never from a refusal`() {
    assertTrue(Retry.broke(ApiErrorException(ApiError("AI_REQUEST_FAILED", "provider 429"))))
    assertTrue(Retry.broke(ApiErrorException(ApiError("NETWORK_ERROR", "socket"))))
    assertTrue(Retry.broke(ApiErrorException(ApiError("HTTP_ERROR", "oops", statusCode = 500))))
    assertTrue(Retry.broke(java.io.IOException("unexpected end of stream")))
    assertFalse(Retry.broke(ApiErrorException(ApiError("QUOTA_EXCEEDED", "spent", statusCode = 402))))
    assertFalse(Retry.broke(ApiErrorException(ApiError("BAD_REQUEST", "task.kind", statusCode = 400))))
    assertFalse(Retry.broke(IllegalStateException("a bug")))
  }

  @Test fun `a wake that broke off goes on with a note, what its tools did, and its message`() {
    val task = TaskBook({ 2 }).start(player, Kind.CRAFTING, "a", "", 1, null)
    assertEquals(Task.BROKE + "\nThat wake had woken with:\n[player] да", task.resume("[player] да"))
    task.report("crafted 3 × glass_bottle into the player's inventory")
    val note = task.resume("x".repeat(3000))
    assertTrue(note.startsWith(Task.BROKE + "\nWhat your tools reported so far: crafted 3 × glass_bottle"))
    assertEquals(Task.MESSAGE_LIMIT, note.length)
  }

  @Test fun `retries back off longer each time, never sooner than the backend asks`() {
    assertEquals(listOf(15L, 45L, 135L, 300L), (1..4).map { Retry.backoff(it) })
    assertEquals(60L, Retry.backoff(1, asked = 60))
    assertEquals(300L, Retry.backoff(1, asked = 900))
  }

  @Test fun `an agent pauses in another world or past its distance, and only then`() {
    val anchor = Protocol.Position(0, 64, 0, "overworld", "world")
    assertFalse(chat.allay.core.tasks.Agents.away(Protocol.Position(40, 70, -40, "overworld", "world"), anchor, 64))
    assertTrue(chat.allay.core.tasks.Agents.away(Protocol.Position(60, 64, 60, "overworld", "world"), anchor, 64))
    assertTrue(chat.allay.core.tasks.Agents.away(Protocol.Position(0, 64, 0, "the_nether", "world_nether"), anchor, 64))
    assertTrue(chat.allay.core.tasks.Agents.away(Protocol.Position(0, 64, 0, "overworld", "other"), anchor, 64))
  }
}
