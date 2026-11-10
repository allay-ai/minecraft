package chat.allay.core

import chat.allay.core.building.Blueprint
import chat.allay.core.building.Placement
import chat.allay.core.building.Schem
import chat.allay.core.building.Shell
import chat.allay.core.tools.DraftSide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Where a previewed build stands, how it turns and mirrors, and which of its blocks show from outside. */
class PlacementTest {
  private val hut = Schem.read(PlacementTest::class.java.getResourceAsStream("/schematics/starter-hut.schem")!!.readBytes())

  @Test fun `a build stands two blocks ahead of the player, centered, on their floor`() {
    val placement = Placement.before(hut, Triple(0, 64, 0), DraftSide.NORTH)
    val (low, high) = placement.bounds(hut)
    // Facing north, ahead is -z: the near edge two blocks away, five deep, centered on x.
    assertEquals(Triple(-2, 64, -6), low)
    assertEquals(Triple(2, 68, -2), high)
  }

  @Test fun `facing north, a build stands as it stood where it was saved, and turning keeps it in place`() {
    val placement = Placement.before(hut, Triple(0, 64, 0), DraftSide.NORTH)
    // The doorway is at the schematic's z 0, its north side, so it stays the far side from a player looking north.
    val (_, _, doorZ) = placement.world(hut, 2, 1, 0)
    assertEquals(-6, doorZ)
    val turned = placement.turned(hut, 4)
    assertEquals(placement.corner, turned.corner, "four quarters bring it back")
    val quarter = placement.turned(hut, 1)
    assertEquals(placement.bounds(hut), quarter.bounds(hut), "a square build turns in its own footprint")
  }

  @Test fun `moving and mirroring shift and flip as the build faces`() {
    val placement = Placement.before(hut, Triple(0, 64, 0), DraftSide.EAST)
    val moved = placement.moved(right = 1, up = 2, forward = 3)
    // Facing east, right is +z and forward +x.
    assertEquals(Triple(placement.corner.first + 3, 66, placement.corner.third + 1), moved.corner)
    val mirrored = placement.copy(mirrored = true)
    assertEquals(placement.world(hut, 0, 0, 0), mirrored.world(hut, hut.width - 1, 0, 0))
  }

  @Test fun `a build on a lot of its own sinks so the lot's surface meets the ground`() {
    val villa = Schem.read(PlacementTest::class.java.getResourceAsStream("/schematics/modern-villa.schem")!!.readBytes())
    val surface = chat.allay.core.building.Construction.surface(villa)
    assertTrue(surface >= 0, "the villa stands on a lot of dirt and grass")
    val placement = Placement.before(villa, Triple(0, 64, 0), DraftSide.NORTH)
    assertEquals(63, placement.corner.second + surface, "its grass lies where the world's ground was")
    assertEquals(-1, chat.allay.core.building.Construction.surface(hut), "the hut has no lot")
  }

  @Test fun `only what shows from outside is drawn`() {
    val opaque = BooleanArray(hut.palette.size) { !hut.empty[it] && !hut.palette[it].contains("glass") && !hut.palette[it].contains("slab") }
    val shell = Shell.of(hut, opaque)
    assertTrue(shell.size <= hut.blocks)
    // The hut is hollow inside, so every block shows: from outside or from its room.
    assertEquals(hut.blocks, shell.size)
    val packed = Shell.pack(1023, 511, 2047)
    assertEquals(Triple(1023, 511, 2047), Triple(Shell.x(packed), Shell.y(packed), Shell.z(packed)))
    val solid = Blueprint.Builder(3, 3, 3).apply { for (x in 0..2) for (y in 0..2) for (z in 0..2) set(x, y, z, 1) }.build(listOf("minecraft:air", "minecraft:stone"), 0, emptyList(), "", "")
    assertEquals(26, Shell.of(solid, booleanArrayOf(false, true)).size, "the middle of a solid cube never shows")
  }
}
