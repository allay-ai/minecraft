package chat.allay.core.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One cell of a draft, in the draft's own frame: `right` from the player's left to right, `up`, and `forward` away from them. */
data class DraftCell(val right: Int, val up: Int, val forward: Int)

/** What belongs in a cell: a block of the platform's kind (states written facing north), or with null [data], nothing at all. */
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
 * The parts of `show_draft` that don't touch the world: the size limit, reading layers over a palette, and block
 * states as the game writes them. Both platforms build their drafts from these, so the model reads the same rules.
 */
object Drafting {
  const val WIDTH = 20
  const val HEIGHT = 15
  const val DEPTH = 20
  /** More ghosts than this at once would weigh on the client; “all” shows every layer only below it. */
  const val MAX_SHOWN = 800
  /** Labels over every one of a hundred planks would bury the build; they show only for rarer blocks. */
  const val LABEL_LIMIT = 6
  const val MAX_PALETTE = 40
  /** How far from the player a draft may start. */
  const val REACH = 32

  /** A palette entry as written, without `minecraft:`. */
  fun clean(text: String) = text.trim().lowercase().removePrefix("minecraft:")

  /** Block states as `key=value`, from the game's text for a block like `repeater[delay=2,facing=north]`. */
  fun states(text: String): Map<String, String> =
    text.substringAfter('[', "").substringBefore(']').split(',').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }

  /** The states a palette entry spells out, which are the ones checked. Dust's power follows what's around it, so it never counts. */
  fun spelled(text: String): Set<String> =
    clean(text).substringAfter('[', "").substringBefore(']').split(',').filter { '=' in it }.map { it.substringBefore('=').trim() }.filterNot { it == "power" }.toSet()

  /** The world spot of [cell] for a draft whose cell 0, 0, 0 sits at [corner] and faces [facing]. */
  fun place(corner: Triple<Int, Int, Int>, facing: DraftSide, cell: DraftCell): Triple<Int, Int, Int> {
    val right = facing.right
    val (x, y, z) = corner
    return Triple(x + right.dx * cell.right + facing.dx * cell.forward, y + cell.up, z + right.dz * cell.right + facing.dz * cell.forward)
  }

  fun offset(args: JsonObject, allowNegative: Boolean = false): Triple<Int, Int, Int> {
    val offset = args["offset"] as? JsonObject
    val values = Triple(offset?.int("right") ?: 0, offset?.int("up") ?: 0, offset?.int("forward") ?: 0)
    if (!allowNegative && (values.first < 0 || values.second < 0 || values.third < 0)) throw ToolError("offset can't be negative here; move the draft instead")
    return values
  }

  fun limits(cells: Collection<DraftCell>) {
    val width = cells.maxOf { it.right } + 1
    val height = cells.maxOf { it.up } + 1
    val depth = cells.maxOf { it.forward } + 1
    if (width > WIDTH || height > HEIGHT || depth > DEPTH) {
      throw ToolError("the draft would be ${width}×${height}×${depth} (width × height × depth); the limit is ${WIDTH}×${HEIGHT}×${DEPTH}. Split the build: show one part, and the player can build another later")
    }
  }

  /** The cells of `layers` over `palette`, shifted by [offset]; [parse] reads one palette entry's block and label. */
  fun <B> cells(args: JsonObject, offset: Triple<Int, Int, Int>, parse: (String, String?) -> DraftBlock<B>): Map<DraftCell, DraftBlock<B>> {
    // A list of {char, block, label}; a {char: block} object works too.
    val raw: List<Triple<String, String, String?>> = when (val palette = args["palette"]) {
      is JsonArray -> palette.map { entry ->
        val item = entry as? JsonObject ?: throw ToolError("palette entries are {char, block, label?}")
        Triple(item.string("char") ?: throw ToolError("each palette entry needs char"), item.string("block") ?: throw ToolError("palette entry ${item.string("char")} needs block"), item.string("label")?.take(32))
      }
      is JsonObject -> palette.map { (key, value) ->
        when (value) {
          is JsonPrimitive -> Triple(key, value.content, null)
          is JsonObject -> Triple(key, value.string("block") ?: throw ToolError("palette entry $key needs block"), value.string("label")?.take(32))
          else -> throw ToolError("palette entry $key must be a block id or {block, label}")
        }
      }
      else -> throw ToolError("palette is required: one entry per character, like [{\"char\": \"p\", \"block\": \"oak_planks\"}]")
    }
    if (raw.size > MAX_PALETTE) throw ToolError("at most $MAX_PALETTE palette entries")
    val entries = raw.associate { (key, block, label) ->
      if (key.length != 1 || key == "." || key == " ") throw ToolError("palette chars are single characters other than . and space; got \"$key\"")
      key to parse(block, label)
    }
    val layers = args["layers"] as? JsonArray ?: throw ToolError("layers is required: from the bottom up, each a list of rows from the far one to the near one")
    val (dr, du, df) = offset
    val result = HashMap<DraftCell, Pair<Char, DraftBlock<B>>>()
    layers.forEachIndexed { up, layer ->
      val rows = (layer as? JsonArray)?.map { (it as? JsonPrimitive)?.content ?: throw ToolError("layer ${up + 1}: rows are strings") }
        ?: throw ToolError("layer ${up + 1} must be a list of rows")
      rows.forEachIndexed { index, row ->
        // Rows run from the far one to the near one, as on a map with forward at the top.
        val forward = rows.size - 1 - index
        row.forEachIndexed { right, char ->
          if (char == '.' || char == ' ') return@forEachIndexed
          val planned = entries[char.toString()] ?: throw ToolError("layer ${up + 1}, row ${index + 1}: \"$char\" isn't in the palette")
          result[DraftCell(right + dr, up + du, forward + df)] = char to planned
        }
      }
    }
    val uses = result.values.groupingBy { it.first }.eachCount()
    return result.mapValues { (_, value) ->
      val (char, planned) = value
      if (planned.label != null && uses.getValue(char) > LABEL_LIMIT) DraftBlock(planned.data, null, planned.keys) else planned
    }
  }
}
