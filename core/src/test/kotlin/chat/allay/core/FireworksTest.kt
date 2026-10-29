package chat.allay.core

import chat.allay.core.tools.Fireworks
import chat.allay.core.tools.ToolError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FireworksTest {
  private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

  @Test fun `a grand rocket takes what its stars and flight need, three rockets a craft`() {
    val spec = Fireworks.parse(json("""{"flight":3,"stars":[{"shape":"large ball","colors":["red","yellow_dye"],"fade":["white"],"trail":true,"twinkle":true}]}"""), star = false)
    val needs = Fireworks.needs(spec, 6, star = false)
    assertEquals(2, needs.crafts)
    assertEquals(6, needs.makes)
    // Per craft: 1 paper, 3 + 1 gunpowder, a fire charge, three dyes, a diamond, glowstone.
    assertEquals(2, needs.items[setOf("paper")])
    assertEquals(8, needs.items[setOf("gunpowder")])
    assertEquals(2, needs.items[setOf("fire_charge")])
    assertEquals(2, needs.items[setOf("red_dye")])
    assertEquals(2, needs.items[setOf("white_dye")])
    assertTrue(needs.table)
  }

  @Test fun `a plain elytra rocket fits the hand grid`() {
    val needs = Fireworks.needs(Fireworks.parse(json("""{"flight":2}"""), star = false), 3, star = false)
    assertEquals(mapOf(setOf("paper") to 1, setOf("gunpowder") to 2), needs.items)
    assertFalse(needs.table)
  }

  @Test fun `a creeper face takes any mob head`() {
    val needs = Fireworks.needs(Fireworks.parse(json("""{"stars":[{"shape":"creeper","colors":["green"]}]}"""), star = true), 1, star = true)
    assertEquals(1, needs.items[Fireworks.SKULLS])
  }

  @Test fun `the grid's nine slots and the game's names hold`() {
    assertFailsWith<ToolError> { Fireworks.parse(json("""{"flight":4}"""), star = false) }
    assertFailsWith<ToolError> { Fireworks.parse(json("""{"stars":[{"colors":["chartreuse"]}]}"""), star = false) }
    assertFailsWith<ToolError> { Fireworks.parse(json("""{"stars":[{"shape":"heart","colors":["red"]}]}"""), star = false) }
    val many = (1..8).joinToString(",") { "\"red\"" }
    assertFailsWith<ToolError> { Fireworks.parse(json("""{"stars":[{"shape":"star","colors":[$many],"trail":true}]}"""), star = false) }
    assertFailsWith<ToolError> { Fireworks.parse(json("""{"stars":[{"colors":["red"]},{"colors":["blue"]}]}"""), star = true) }
  }
}
