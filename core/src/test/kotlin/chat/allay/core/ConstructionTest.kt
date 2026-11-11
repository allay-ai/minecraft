package chat.allay.core

import chat.allay.core.building.Blueprint
import chat.allay.core.building.Construction
import chat.allay.core.building.Shell
import chat.allay.core.building.Showing
import chat.allay.core.tasks.Kind
import chat.allay.core.tasks.TaskBook
import chat.allay.core.tools.ToolError
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

  @Test fun `a preview's blocks go by chunk, and a chunk goes again on time or once touched`() {
    val bp = Blueprint.Builder(20, 1, 1).apply { for (x in 0 until 20) set(x, 0, 0, 1) }.build(listOf("minecraft:air", "minecraft:stone"), 0, emptyList(), "", "")
    val chunks = Showing.chunks(bp, chat.allay.core.building.Placement(Triple(10, 64, 0), chat.allay.core.tools.DraftSide.NORTH))
    // x 10 to 29: chunk 0 holds 6 of them, chunk 1 the other 14.
    assertEquals(mapOf(0 to 6, 1 to 14), chunks.mapKeys { Showing.chunkX(it.key) }.mapValues { it.value.size })
    assertEquals(-3 to 7, Showing.chunk(-3, 7).let { Showing.chunkX(it) to Showing.chunkZ(it) })
    for ((x, y, z) in listOf(Triple(0, 0, 0), Triple(-29_999_999, -64, 29_999_999), Triple(123, 319, -456))) {
      assertEquals(Triple(x, y, z), Showing.spot(x, y, z).let { Triple(Showing.spotX(it), Showing.spotY(it), Showing.spotZ(it)) })
    }
    val schedule = Showing.Schedule()
    val key = Showing.chunk(0, 0)
    assertTrue(schedule.due(key, 0))
    schedule.sent(key, 0)
    assertFalse(schedule.due(key, 10))
    assertTrue(schedule.due(key, 20), "again a second later")
    schedule.sent(key, 20)
    assertFalse(schedule.due(key, 100))
    assertTrue(schedule.due(key, 120), "and five seconds after that")
    schedule.sent(key, 120)
    assertFalse(schedule.due(key, 300))
    assertTrue(schedule.due(key, 320), "then every ten seconds")
    schedule.touch(key)
    assertTrue(schedule.due(key, 321), "at once when something in it changed")
    schedule.forget(key)
    assertTrue(key !in schedule.chunks)
  }

  @Test fun `Unbreaking spares a tool's uses as the game rolls them`() {
    assertTrue(Construction.wears(0, 0.99))
    assertTrue(Construction.wears(3, 0.2))
    assertFalse(Construction.wears(3, 0.3))
    assertEquals(0, Construction.wearOrder("wooden_pickaxe", "pickaxe"))
  }

  @Test fun `a lot's tufts, flowers, and snow are decor, its ground and the house aren't`() {
    for (block in listOf("short_grass", "minecraft:snow", "red_tulip", "cornflower", "oak_sapling", "fern")) assertTrue(Construction.decor(block), block)
    for (block in listOf("grass_block", "snow_block", "oak_planks", "flower_pot", "cobblestone")) assertFalse(Construction.decor(block), block)
  }

  @Test fun `a basement under the lot is dug out, the air at the lot's edge stays the world's`() {
    // A 5×3×5 lot of dirt, a cellar of 3×1×1 in the middle of its second layer, and an empty corner at its edge.
    val air = setOf(Triple(1, 1, 2), Triple(2, 1, 2), Triple(3, 1, 2), Triple(0, 2, 0))
    val b = Blueprint.Builder(5, 3, 5)
    for (y in 0..2) for (z in 0..4) for (x in 0..4) if (Triple(x, y, z) !in air) b.set(x, y, z, 1)
    val build = b.build(listOf("minecraft:air", "minecraft:dirt"), 0, emptyList(), "", "")
    val surface = Construction.surface(build)
    assertEquals(2, surface)
    val inside = Construction.interior(build, surface)
    assertEquals(setOf(Shell.pack(1, 1, 2), Shell.pack(2, 1, 2), Shell.pack(3, 1, 2)), inside)
    assertTrue(Shell.pack(0, 2, 0) !in inside, "the corner at the edge is the world's")
  }
}
