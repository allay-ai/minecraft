package chat.allay.core

import chat.allay.core.building.Blueprint
import chat.allay.core.building.Construction
import chat.allay.core.building.Shell
import chat.allay.core.tasks.Kind
import chat.allay.core.tasks.TaskBook
import chat.allay.core.tools.ToolError
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The order a builder places blocks in, the tools it wears first, and its one slot beside the other agents. */
class ConstructionTest {
  @Test fun `solid blocks go up first, layer by layer, then what hangs on them, then water`() {
    // stone at the bottom, a torch on it, water beside the torch, stone again on top
    val palette = listOf("minecraft:air", "minecraft:stone", "minecraft:torch", "minecraft:water[level=0]")
    val blueprint = Blueprint.Builder(2, 3, 1).apply {
      set(0, 0, 0, 1); set(1, 0, 0, 1)
      set(0, 1, 0, 2); set(1, 1, 0, 3)
      set(0, 2, 0, 1)
    }.build(palette, 0, emptyList(), "", "")
    val stage = intArrayOf(Construction.SOLID, Construction.SOLID, Construction.ATTACHED, Construction.LIQUID)
    val order = Construction.order(blueprint, stage).map { Triple(Shell.x(it), Shell.y(it), Shell.z(it)) }
    assertEquals(listOf(Triple(0, 0, 0), Triple(1, 0, 0), Triple(0, 2, 0), Triple(0, 1, 0), Triple(1, 1, 0)), order)
  }

  @Test fun `the worst hoe wears first, and nothing else counts as one`() {
    val hoes = listOf("netherite_hoe", "wooden_hoe", "iron_hoe", "golden_hoe").sortedBy { Construction.wearOrder(it, "hoe") }
    assertEquals(listOf("wooden_hoe", "golden_hoe", "iron_hoe", "netherite_hoe"), hoes)
    assertNull(Construction.wearOrder("wooden_shovel", "hoe"))
    assertEquals("oak_stairs", Construction.kind("minecraft:oak_stairs[facing=north,half=bottom]"))
  }

  @Test fun `a player has one builder, beside the agents their limit counts`() {
    val book = TaskBook({ 1 })
    val me = UUID.randomUUID()
    book.start(me, Kind.CHESTS, "sort", "sort", 1, null)
    // The limit of one is taken, yet the builder starts.
    book.start(me, Kind.BUILDING, "villa", "build the villa", 1, null)
    assertFailsWith<ToolError> { book.start(me, Kind.BUILDING, "another", "build", 1, null) }
    assertFailsWith<ToolError> { book.start(me, Kind.FURNACES, "smelt", "smelt", 1, null) }
  }
}
