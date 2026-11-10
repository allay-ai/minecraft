package chat.allay.core.tools

/** One cell of a draft, in the draft's own frame: `right` from the player's left to right, `up`, and `forward` away from them. */
data class DraftCell(val right: Int, val up: Int, val forward: Int)

/**
 * What belongs in a cell: a block of the platform's kind (states written facing north), or with null [data], nothing at
 * all. [keys] are the states compared with the world, and [label] shows over its ghost.
 */
class DraftBlock<B>(val data: B?, val label: String?, val keys: Set<String>)

/** The sides of the world a draft can face, clockwise from north, with a step toward each. */
enum class DraftSide(val dx: Int, val dz: Int) {
  NORTH(0, -1), EAST(1, 0), SOUTH(0, 1), WEST(-1, 0);

  /** A quarter turn clockwise: the draft's right when it faces this way. */
  val right: DraftSide get() = entries[(ordinal + 1) % 4]

  /** Quarter turns clockwise from north: how far a draft written facing north turns its block states. */
  val turns: Int get() = ordinal

  companion object {
    /** The way a player looks, to the nearest side; the game's yaw is 0 toward south and grows clockwise. */
    fun of(yaw: Float): DraftSide {
      val turned = ((yaw % 360) + 360) % 360
      return when {
        turned >= 315 || turned < 45 -> SOUTH
        turned < 135 -> WEST
        turned < 225 -> NORTH
        else -> EAST
      }
    }
  }
}

/**
 * The parts of a draft that don't touch the world: where a cell lands, and block states as the game writes them. Both
 * platforms draw their drafts with these.
 */
object Drafting {
  /** Block states as `key=value`, from the game's text for a block like `repeater[delay=2,facing=north]`. */
  fun states(text: String): Map<String, String> =
    text.substringAfter('[', "").substringBefore(']').split(',').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }

  /** The world spot of [cell] for a draft whose cell 0, 0, 0 sits at [corner] and faces [facing]. */
  fun place(corner: Triple<Int, Int, Int>, facing: DraftSide, cell: DraftCell): Triple<Int, Int, Int> {
    val right = facing.right
    val (x, y, z) = corner
    return Triple(x + right.dx * cell.right + facing.dx * cell.forward, y + cell.up, z + right.dz * cell.right + facing.dz * cell.forward)
  }
}
