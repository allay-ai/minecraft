package chat.allay.core

import chat.allay.core.tools.DraftBlock
import chat.allay.core.tools.DraftCell
import chat.allay.core.tools.DraftSide
import chat.allay.core.tools.Drafting
import chat.allay.core.tools.ToolError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A draft is written as if the player faced north; these hold the turn to where they really look, and how layers read. */
class DraftingTest {
  private fun args(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
  private fun parse(block: String, label: String?) = DraftBlock(Drafting.clean(block).takeUnless { it == "air" }, label, Drafting.spelled(block))

  @Test fun `right is a quarter turn clockwise from forward`() {
    assertEquals(DraftSide.EAST, DraftSide.NORTH.right)
    assertEquals(DraftSide.SOUTH, DraftSide.EAST.right)
    assertEquals(DraftSide.WEST, DraftSide.SOUTH.right)
    assertEquals(DraftSide.NORTH, DraftSide.WEST.right)
  }

  @Test fun `block states turn by the quarters from north`() {
    assertEquals(listOf(0, 1, 2, 3), DraftSide.entries.map { it.turns })
  }

  @Test fun `the player's yaw picks the nearest side`() {
    assertEquals(DraftSide.SOUTH, DraftSide.of(0f))
    assertEquals(DraftSide.WEST, DraftSide.of(90f))
    assertEquals(DraftSide.NORTH, DraftSide.of(180f))
    assertEquals(DraftSide.NORTH, DraftSide.of(-180f))
    assertEquals(DraftSide.EAST, DraftSide.of(-90f))
    assertEquals(DraftSide.SOUTH, DraftSide.of(-30f))
  }

  @Test fun `cells land in the world the way the draft faces`() {
    // Facing east, forward is +x and right is +z.
    assertEquals(Triple(13, 66, 22), Drafting.place(Triple(10, 64, 20), DraftSide.EAST, DraftCell(2, 2, 3)))
    assertEquals(Triple(12, 64, 17), Drafting.place(Triple(10, 64, 20), DraftSide.NORTH, DraftCell(2, 0, 3)))
  }

  @Test fun `states read from the game's text`() {
    assertEquals(mapOf("delay" to "2", "facing" to "north", "locked" to "false", "powered" to "false"), Drafting.states("minecraft:repeater[delay=2,facing=north,locked=false,powered=false]"))
    assertEquals(emptyMap(), Drafting.states("minecraft:stone"))
  }

  @Test fun `only the states written are checked, never dust's power`() {
    assertEquals(setOf("facing", "delay"), Drafting.spelled("minecraft:Repeater[facing=north, delay=2]"))
    assertEquals(setOf("north"), Drafting.spelled("redstone_wire[north=side,power=15]"))
    assertEquals(emptySet(), Drafting.spelled("oak_planks"))
  }

  @Test fun `rows run from the far one to the near one`() {
    val cells = Drafting.cells(args("""{"palette": {"p": "oak_planks", "a": "air"}, "layers": [["pa", "p."]]}"""), Triple(0, 0, 0), ::parse)
    assertEquals(setOf(DraftCell(0, 0, 1), DraftCell(1, 0, 1), DraftCell(0, 0, 0)), cells.keys)
    assertNull(cells.getValue(DraftCell(1, 0, 1)).data)
    assertEquals("oak_planks", cells.getValue(DraftCell(0, 0, 0)).data)
  }

  @Test fun `offsets shift and never go negative outside a move`() {
    val cells = Drafting.cells(args("""{"palette": [{"char": "s", "block": "stone"}], "layers": [["s"]]}"""), Drafting.offset(args("""{"offset": {"right": 2, "up": 1}}""")), ::parse)
    assertEquals(setOf(DraftCell(2, 1, 0)), cells.keys)
    assertFailsWith<ToolError> { Drafting.offset(args("""{"offset": {"forward": -1}}""")) }
    assertEquals(Triple(0, 0, -1), Drafting.offset(args("""{"offset": {"forward": -1}}"""), allowNegative = true))
  }

  @Test fun `labels go from blocks used often`() {
    val rare = Drafting.cells(args("""{"palette": [{"char": "t", "block": "torch", "label": "Torch"}], "layers": [["tt"]]}"""), Triple(0, 0, 0), ::parse)
    assertTrue(rare.values.all { it.label == "Torch" })
    val common = Drafting.cells(args("""{"palette": [{"char": "t", "block": "torch", "label": "Torch"}], "layers": [["ttttttt"]]}"""), Triple(0, 0, 0), ::parse)
    assertTrue(common.values.all { it.label == null })
  }

  @Test fun `the size limit is the owner's`() {
    Drafting.limits(listOf(DraftCell(19, 14, 19)))
    val error = assertFailsWith<ToolError> { Drafting.limits(listOf(DraftCell(20, 0, 0))) }
    assertTrue("21×1×1" in error.message!!)
  }

  @Test fun `a palette char must be one character other than dot and space`() {
    assertFailsWith<ToolError> { Drafting.cells(args("""{"palette": {"pp": "stone"}, "layers": [["p"]]}"""), Triple(0, 0, 0), ::parse) }
    assertFailsWith<ToolError> { Drafting.cells(args("""{"palette": {".": "stone"}, "layers": [["."]]}"""), Triple(0, 0, 0), ::parse) }
    assertFailsWith<ToolError> { Drafting.cells(args("""{"palette": {"s": "stone"}, "layers": [["x"]]}"""), Triple(0, 0, 0), ::parse) }
  }
}
