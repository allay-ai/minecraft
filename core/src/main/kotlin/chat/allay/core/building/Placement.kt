package chat.allay.core.building

import chat.allay.core.tools.DraftCell
import chat.allay.core.tools.DraftSide
import chat.allay.core.tools.Drafting

/**
 * Where a build stands: its near left bottom [corner] in the world, the way it [facing]s, and whether it's [mirrored]
 * left to right. A schematic is read as if its author faced north while building it (north forward, east right), so
 * its x runs to the right and its z toward the viewer; turning the build turns its block states the same quarters.
 */
data class Placement(val corner: Triple<Int, Int, Int>, val facing: DraftSide, val mirrored: Boolean = false) {
  /** The world spot of the schematic's cell [x], [y], [z]. */
  fun world(blueprint: Blueprint, x: Int, y: Int, z: Int): Triple<Int, Int, Int> {
    val right = if (mirrored) blueprint.width - 1 - x else x
    return Drafting.place(corner, facing, DraftCell(right, y, blueprint.length - 1 - z))
  }

  /** A horizontal direction of the schematic ([dx], [dz]) as it points in the world, turned and mirrored as the build. */
  fun direction(blueprint: Blueprint, dx: Int, dz: Int): Pair<Int, Int> {
    val (ox, _, oz) = world(blueprint, 1, 0, 1)
    val (ax, _, az) = world(blueprint, 1 + dx, 0, 1 + dz)
    return (ax - ox) to (az - oz)
  }

  /** Moved [right], [up], and [forward] as the build faces; negative goes the other way. */
  fun moved(right: Int, up: Int, forward: Int): Placement {
    val (x, y, z) = corner
    val side = facing.right
    return copy(corner = Triple(x + side.dx * right + facing.dx * forward, y + up, z + side.dz * right + facing.dz * forward))
  }

  /** Turned clockwise by [turns] quarters around the build's middle, so it turns in place. */
  fun turned(blueprint: Blueprint, turns: Int): Placement {
    var placement = this
    repeat(((turns % 4) + 4) % 4) {
      val (mx, _, mz) = placement.world(blueprint, (blueprint.width - 1) / 2, 0, (blueprint.length - 1) / 2)
      val facing = placement.facing.right
      val turned = placement.copy(facing = facing)
      val (nx, _, nz) = turned.world(blueprint, (blueprint.width - 1) / 2, 0, (blueprint.length - 1) / 2)
      placement = turned.copy(corner = Triple(turned.corner.first + mx - nx, turned.corner.second, turned.corner.third + mz - nz))
    }
    return placement
  }

  /** Every block of [blueprint]'s box, its world spots spread over these sides: the box's two opposite corners. */
  fun bounds(blueprint: Blueprint): Pair<Triple<Int, Int, Int>, Triple<Int, Int, Int>> {
    val a = world(blueprint, 0, 0, 0)
    val b = world(blueprint, blueprint.width - 1, blueprint.height - 1, blueprint.length - 1)
    return Triple(minOf(a.first, b.first), minOf(a.second, b.second), minOf(a.third, b.third)) to
      Triple(maxOf(a.first, b.first), maxOf(a.second, b.second), maxOf(a.third, b.third))
  }

  companion object {
    /** In front of a player at [feet] who looks [facing]: centered, starting two blocks ahead, its floor at their feet. */
    fun before(blueprint: Blueprint, feet: Triple<Int, Int, Int>, facing: DraftSide): Placement {
      val (x, y, z) = feet
      val side = facing.right
      val half = blueprint.width / 2
      return Placement(Triple(x + facing.dx * 2 - side.dx * half, y, z + facing.dz * 2 - side.dz * half), facing)
    }
  }
}

/**
 * The cells of a build someone outside can see: those with a side open to air or to a block light passes through.
 * A wall three blocks thick shows one; a hollow house shows its outside and, through its windows, what's behind them.
 * [opaque] says, per palette entry, whether a block hides what's behind it.
 */
object Shell {
  fun of(blueprint: Blueprint, opaque: BooleanArray): IntArray {
    val out = IntArrayList()
    blueprint.forEach { x, y, z, _ ->
      if (!hides(blueprint, opaque, x + 1, y, z) || !hides(blueprint, opaque, x - 1, y, z) ||
        !hides(blueprint, opaque, x, y + 1, z) || !hides(blueprint, opaque, x, y - 1, z) ||
        !hides(blueprint, opaque, x, y, z + 1) || !hides(blueprint, opaque, x, y, z - 1)
      ) out.add(pack(x, y, z))
    }
    return out.toArray()
  }

  private fun hides(blueprint: Blueprint, opaque: BooleanArray, x: Int, y: Int, z: Int): Boolean {
    if (x !in 0 until blueprint.width || y !in 0 until blueprint.height || z !in 0 until blueprint.length) return false
    return opaque[blueprint.at(x, y, z)]
  }

  /** A cell as one Int: 11 bits each for x and z, 10 for y, enough for the catalog's 1024 a side. */
  fun pack(x: Int, y: Int, z: Int) = (x shl 21) or (z shl 10) or y
  fun x(packed: Int) = packed ushr 21
  fun z(packed: Int) = (packed ushr 10) and 0x7FF
  fun y(packed: Int) = packed and 0x3FF

  private class IntArrayList {
    private var items = IntArray(1024)
    private var size = 0

    fun add(value: Int) {
      if (size == items.size) items = items.copyOf(size * 2)
      items[size++] = value
    }

    fun toArray(): IntArray = items.copyOf(size)
  }
}
