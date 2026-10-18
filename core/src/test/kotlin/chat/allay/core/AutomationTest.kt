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

  @Test fun `quota tiers give shares and operators the biggest`() {
    val settings = quota(
      """
      quota:
        enabled: true
        monthly_usd: 20
        tiers:
          default: "0.05"
          p10: "0.10"
          vip: 0.51222229
      """.trimIndent(),
    )
    assertNull(settings.error)
    assertEquals(0.05, settings.share(false) { false })
    assertEquals(0.10, settings.share(false) { it == "p10" })
    assertEquals(0.51222229, settings.share(true) { false })
  }

  @Test fun `shares above 100 percent make the config wrong`() {
    val settings = quota("quota:\n  enabled: true\n  monthly_usd: 10\n  tiers:\n    a: \"0.6\"\n    b: \"0.5\"")
    assertNotNull(settings.error)
    assertNull(quota("quota:\n  enabled: false\n  tiers:\n    a: \"0.6\"\n    b: \"0.5\"").error)
    assertNotNull(quota("quota:\n  enabled: true\n  monthly_usd: 10\n  tiers:\n    a: lots").error)
  }

  @Test fun `models go by the first tier a player holds`() {
    val models = ModelTiers.parse(YamlConfig(YamlConfig.parse("models:\n  vip: \"a/big\"\n  p10: \"a/small\"")))
    assertEquals("a/small", models.of(false) { it == "p10" })
    assertEquals("a/big", models.of(false) { true })
    assertEquals("a/big", models.of(true) { false })
    assertNull(models.of(false) { false })
  }
}
