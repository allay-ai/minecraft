package chat.allay.core

import chat.allay.core.building.Blueprint
import chat.allay.core.building.Construction
import chat.allay.core.building.Placement
import chat.allay.core.building.Shapes
import chat.allay.core.building.Shell
import chat.allay.core.tools.DraftSide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the game settles from neighbours, as an old schematic lacks it, and what the builder clears away. */
class ShapesTest {
  /** Stone is sturdy on every face; nothing else here is. */
  private val sturdy = { state: String, _: String -> Blueprint.block(state) == "minecraft:stone" }

  private fun blueprint(width: Int, height: Int, length: Int, palette: List<String>, cells: Map<Triple<Int, Int, Int>, Int>) =
    Blueprint.Builder(width, height, length).apply { cells.forEach { (at, index) -> set(at.first, at.second, at.third, index) } }
      .build(listOf("minecraft:air") + palette, 0, emptyList(), "", "")

  @Test fun `fences join each other and a stone wall, panes join bars, and an old schematic's bare posts become a fence`() {
    val fence = "minecraft:oak_fence[east=false,north=false,south=false,waterlogged=false,west=false]"
    val pane = "minecraft:glass_pane[east=false,north=false,south=false,waterlogged=false,west=false]"
    val bars = "minecraft:iron_bars[east=false,north=false,south=false,waterlogged=false,west=false]"
    val bp = blueprint(4, 2, 1, listOf(fence, "minecraft:stone", pane, bars), mapOf(
      Triple(0, 0, 0) to 1, Triple(1, 0, 0) to 1, Triple(2, 0, 0) to 2,
      Triple(0, 1, 0) to 3, Triple(1, 1, 0) to 4,
    ))
    val settled = Shapes.settle(bp, bp.palette, sturdy)
    assertEquals("minecraft:oak_fence[east=true,north=false,south=false,waterlogged=false,west=false]", settled[Shell.pack(0, 0, 0)])
    assertEquals("minecraft:oak_fence[east=true,north=false,south=false,waterlogged=false,west=true]", settled[Shell.pack(1, 0, 0)])
    assertEquals("minecraft:glass_pane[east=true,north=false,south=false,waterlogged=false,west=false]", settled[Shell.pack(0, 1, 0)])
    assertEquals("minecraft:iron_bars[east=false,north=false,south=false,waterlogged=false,west=true]", settled[Shell.pack(1, 1, 0)])
    assertNull(settled[Shell.pack(2, 0, 0)])
  }

  @Test fun `a door's halves agree, the hinge from the top, open and facing from the bottom`() {
    val lower = "minecraft:oak_door[facing=west,half=lower,hinge=right,open=true,powered=false]"
    val upper = "minecraft:oak_door[facing=east,half=upper,hinge=left,open=false,powered=false]"
    val bp = blueprint(1, 2, 1, listOf(lower, upper), mapOf(Triple(0, 0, 0) to 1, Triple(0, 1, 0) to 2))
    val settled = Shapes.settle(bp, bp.palette, sturdy)
    assertEquals("minecraft:oak_door[facing=west,half=lower,hinge=left,open=true,powered=false]", settled[Shell.pack(0, 0, 0)])
    assertEquals("minecraft:oak_door[facing=west,half=upper,hinge=left,open=true,powered=false]", settled[Shell.pack(0, 1, 0)])
  }

  @Test fun `a door stacked by an old conversion becomes a whole door, its top half the upper one`() {
    // 1.12 converted: a lower half on a lower half, the top one closed, the bottom one open and facing west.
    val bottom = "minecraft:oak_door[facing=west,half=lower,hinge=right,open=true,powered=false]"
    val top = "minecraft:oak_door[facing=east,half=lower,hinge=right,open=false,powered=false]"
    val bp = blueprint(1, 2, 1, listOf(bottom, top), mapOf(Triple(0, 0, 0) to 1, Triple(0, 1, 0) to 2))
    val settled = Shapes.settle(bp, bp.palette, sturdy)
    assertEquals(null, settled[Shell.pack(0, 0, 0)], "the bottom half is right as it is")
    assertEquals("minecraft:oak_door[facing=west,half=upper,hinge=right,open=true,powered=false]", settled[Shell.pack(0, 1, 0)])
  }

  @Test fun `stairs turn into corners as the game turns them`() {
    // Facing north at (0, 0, 1) with stairs facing west in front of it, at (0, 0, 0): an outer corner, left.
    val north = "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]"
    val west = "minecraft:oak_stairs[facing=west,half=bottom,shape=outer_right,waterlogged=false]"
    val bp = blueprint(1, 1, 2, listOf(north, west), mapOf(Triple(0, 0, 1) to 1, Triple(0, 0, 0) to 2))
    val settled = Shapes.settle(bp, bp.palette, sturdy)
    assertEquals("minecraft:oak_stairs[facing=north,half=bottom,shape=outer_left,waterlogged=false]", settled[Shell.pack(0, 0, 1)])
    assertEquals("minecraft:oak_stairs[facing=west,half=bottom,shape=straight,waterlogged=false]", settled[Shell.pack(0, 0, 0)])
  }

  @Test fun `a wall stands low between walls, its post down in a straight run`() {
    val wall = "minecraft:cobblestone_wall[east=none,north=none,south=none,up=true,waterlogged=false,west=none]"
    val bp = blueprint(3, 1, 1, listOf(wall), mapOf(Triple(0, 0, 0) to 1, Triple(1, 0, 0) to 1, Triple(2, 0, 0) to 1))
    val settled = Shapes.settle(bp, bp.palette, sturdy)
    assertEquals("minecraft:cobblestone_wall[east=low,north=none,south=none,up=false,waterlogged=false,west=low]", settled[Shell.pack(1, 0, 0)])
    assertEquals("minecraft:cobblestone_wall[east=low,north=none,south=none,up=true,waterlogged=false,west=none]", settled[Shell.pack(0, 0, 0)])
  }

  @Test fun `the builder clears the world's ground and plants, never what a player builds`() {
    assertTrue(Construction.natural("minecraft:grass_block"))
    assertTrue(Construction.natural("short_grass"))
    assertTrue(Construction.natural("iron_ore"))
    assertTrue(Construction.natural("red_terracotta"))
    assertTrue(Construction.natural("cobblestone"), "a claim keeps a player's")
    assertFalse(Construction.natural("white_glazed_terracotta"))
    assertFalse(Construction.natural("oak_planks"))
    assertFalse(Construction.natural("chest"))
    assertTrue(Construction.log("birch_log"))
    assertFalse(Construction.log("stripped_birch_log"))
  }

  @Test fun `a world spot maps back to the cell it came from, however the build stands`() {
    val bp = blueprint(3, 2, 4, listOf("minecraft:stone"), emptyMap())
    for (facing in DraftSide.entries) for (mirrored in listOf(false, true)) {
      val placement = Placement(Triple(10, 64, -5), facing, mirrored)
      for (x in 0 until 3) for (y in 0 until 2) for (z in 0 until 4) {
        val (wx, wy, wz) = placement.world(bp, x, y, z)
        assertEquals(Triple(x, y, z), placement.local(bp, wx, wy, wz), "$facing $mirrored")
      }
    }
  }
}
