package chat.allay.spigot.tools

import chat.allay.core.*
import chat.allay.core.tools.*
import chat.allay.spigot.Claims

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bukkit.entity.Player

/**
 * `show_draft`: the model writes a build as layers of characters over a palette, the way it would draw a map, and
 * the plugin turns it to face the player and leads them through it ([Drafts]). Big builds come in parts: `add`
 * puts more layers or a wing next to what's there, as long as the whole stays within the size limit.
 */
object DraftTools {
  /** Labels over every one of a hundred planks would bury the build; they show only for rarer blocks. */
  private const val LABEL_LIMIT = 6
  private const val MAX_PALETTE = 40
  /** How far from the player a draft may start. */
  private const val REACH = 32

  private val draft = object : Tool {
    override val name = "show_draft"

    override fun run(context: ToolContext, args: JsonObject): ToolOutput {
      val player = context.requirePlayer()
      val lang = context.lang
      val action = args.string("action")?.lowercase() ?: if (args["layers"] != null) "show" else "check"
      if (action == "clear") return ToolOutput(buildJsonObject { put("cleared", Drafts.clear(player)) }, lang.text("summary.draft_cleared"))
      if (action == "show") return show(context, player, args)
      val draft = Drafts.of(player) ?: throw ToolError("the player has no draft; show one first")
      when (action) {
        "add" -> {
          val cells = cells(args, offset(args))
          val merged = HashMap(draft.cells).apply { putAll(cells) }
          limits(merged.keys)
          draft.cells.clear()
          draft.cells.putAll(merged)
          Drafts.redraw(draft)
        }
        "layer" -> {
          val wanted = args["layer"] as? JsonPrimitive ?: throw ToolError("layer is required: a number from 1, next, prev, or all")
          draft.layer = when (wanted.content.lowercase()) {
            "next" -> (draft.layer + 1).coerceAtMost(draft.layers - 1)
            "prev" -> (draft.layer - 1).coerceAtLeast(0)
            "all" -> if (draft.cells.size <= Drafts.MAX_SHOWN) -1 else throw ToolError("the draft has ${draft.cells.size} blocks; all layers at once show only below ${Drafts.MAX_SHOWN}")
            else -> (wanted.content.toIntOrNull() ?: throw ToolError("layer must be a number, next, prev, or all")).also {
              if (it !in 1..draft.layers) throw ToolError("layer must be 1–${draft.layers}")
            } - 1
          }
          Drafts.redraw(draft)
        }
        "move" -> {
          val (right, up, forward) = offset(args, allowNegative = true)
          val (fx, fz) = Drafts.step(draft.facing)
          val (rx, rz) = Drafts.step(Drafts.right(draft.facing))
          val (x, y, z) = draft.corner
          val moved = Triple(x + rx * right + fx * forward, y + up, z + rz * right + fz * forward)
          check(player, draft.world.getBlockAt(moved.first, moved.second, moved.third))
          draft.corner = moved
          Drafts.redraw(draft)
        }
        "rotate" -> {
          val turns = ((args.int("turns") ?: 1) % 4 + 4) % 4
          // Around its middle, so the build turns in place instead of swinging around a corner.
          val middle = Drafts.block(draft, Drafts.Cell((draft.width - 1) / 2, 0, (draft.depth - 1) / 2))
          repeat(turns) { draft.facing = Drafts.right(draft.facing) }
          val (fx, fz) = Drafts.step(draft.facing)
          val (rx, rz) = Drafts.step(Drafts.right(draft.facing))
          draft.corner = Triple(middle.x - rx * ((draft.width - 1) / 2) - fx * ((draft.depth - 1) / 2), draft.corner.second, middle.z - rz * ((draft.width - 1) / 2) - fz * ((draft.depth - 1) / 2))
          Drafts.redraw(draft)
        }
        "check" -> return check(context, player, draft)
        else -> throw ToolError("action must be show, add, layer, move, rotate, check, or clear, not $action")
      }
      return ToolOutput(describe(draft), lang.text("summary.draft", "blocks" to draft.cells.size, "layers" to draft.layers))
    }
  }

  private fun show(context: ToolContext, player: Player, args: JsonObject): ToolOutput {
    val title = args.string("title")?.trim()?.take(32)?.ifEmpty { null } ?: throw ToolError("title is required: a short name in the player's language")
    val cells = cells(args, offset(args))
    if (cells.isEmpty()) throw ToolError("the draft has no blocks")
    limits(cells.keys)
    val facing = Drafts.cardinal(player)
    val width = (cells.keys.maxOf { it.right }) + 1
    val corner = if (args.int("x") != null && args.int("z") != null) {
      Triple(args.int("x")!!, args.int("y") ?: player.location.blockY, args.int("z")!!)
    } else {
      // Two blocks ahead at the player's feet, centered on where they look.
      val (fx, fz) = Drafts.step(facing)
      val (rx, rz) = Drafts.step(Drafts.right(facing))
      val feet = player.location.block
      Triple(feet.x + fx * 2 - rx * (width / 2), feet.y + (args.int("y_offset") ?: 0), feet.z + fz * 2 - rz * (width / 2))
    }
    check(player, player.world.getBlockAt(corner.first, corner.second, corner.third))
    val draft = Drafts.Draft(player.uniqueId, title, player.world, corner, facing, HashMap(cells))
    Drafts.begin(player, draft)
    return ToolOutput(describe(draft), context.lang.text("summary.draft", "blocks" to draft.cells.size, "layers" to draft.layers))
  }

  /** The corner must be near and on ground the player may build on. */
  private fun check(player: Player, corner: org.bukkit.block.Block) {
    if (corner.y < corner.world.minHeight || corner.y >= corner.world.maxHeight) throw ToolError("outside the world's height")
    if (corner.location.distance(player.location) > REACH) throw ToolError("the draft must start within $REACH blocks of the player")
    if (!Claims.mayBuild(player, corner.location)) throw ToolError("that's someone else's claim where the player may not build; drafts go only where they could build")
  }

  private fun check(context: ToolContext, player: Player, draft: Drafts.Draft): ToolOutput {
    check(player, Drafts.block(draft, Drafts.Cell(0, 0, 0)))
    val problems = buildJsonArray {
      var listed = 0
      draft.cells.entries.sortedWith(compareBy({ it.key.up }, { it.key.forward }, { it.key.right })).forEach { (cell, planned) ->
        if (listed >= 20 || Drafts.state(draft, cell, planned) != Drafts.State.WRONG) return@forEach
        val block = Drafts.block(draft, cell)
        add(buildJsonObject {
          put("layer", cell.up + 1); put("x", block.x); put("y", block.y); put("z", block.z)
          put("wanted", Drafts.wanted(draft, planned)); put("found", block.blockData.getAsString(true).removePrefix("minecraft:"))
        })
        listed++
      }
    }
    var done = 0
    val layers = buildJsonArray {
      (0 until draft.layers).forEach { layer ->
        val cells = Drafts.cellsOf(draft, layer)
        val placed = cells.count { (cell, planned) -> Drafts.state(draft, cell, planned) == Drafts.State.DONE }
        done += placed
        add(buildJsonObject { put("layer", layer + 1); put("done", placed); put("total", cells.size) })
      }
    }
    val result = buildJsonObject {
      put("title", draft.title)
      put("done", done); put("total", draft.cells.size)
      put("layers", layers)
      put("wrong", problems)
    }
    return ToolOutput(result, context.lang.text("summary.draft_check", "done" to done, "total" to draft.cells.size))
  }

  private fun describe(draft: Drafts.Draft) = buildJsonObject {
    put("title", draft.title)
    put("size", "${draft.width}×${draft.layers}×${draft.depth}")
    put("blocks", draft.cells.size)
    put("layer", if (draft.layer < 0) "all" else "${draft.layer + 1}/${draft.layers}")
    val (x, y, z) = draft.corner
    put("corner", buildJsonObject { put("x", x); put("y", y); put("z", z) })
    put("facing", draft.facing.name.lowercase())
  }

  private fun offset(args: JsonObject, allowNegative: Boolean = false): Triple<Int, Int, Int> {
    val offset = args["offset"] as? JsonObject
    val values = Triple(offset?.int("right") ?: 0, offset?.int("up") ?: 0, offset?.int("forward") ?: 0)
    if (!allowNegative && (values.first < 0 || values.second < 0 || values.third < 0)) throw ToolError("offset can't be negative here; move the draft instead")
    return values
  }

  private fun limits(cells: Collection<Drafts.Cell>) {
    val width = cells.maxOf { it.right } + 1
    val height = cells.maxOf { it.up } + 1
    val depth = cells.maxOf { it.forward } + 1
    if (width > Drafts.WIDTH || height > Drafts.HEIGHT || depth > Drafts.DEPTH) {
      throw ToolError("the draft would be ${width}×${height}×${depth} (width × height × depth); the limit is ${Drafts.WIDTH}×${Drafts.HEIGHT}×${Drafts.DEPTH}. Split the build: show one part, and the player can build another later")
    }
  }

  /** The cells of `layers` over `palette`, shifted by [offset]. */
  private fun cells(args: JsonObject, offset: Triple<Int, Int, Int>): Map<Drafts.Cell, Drafts.Planned> {
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
      key to Drafts.parse(block, label)
    }
    val layers = args["layers"] as? JsonArray ?: throw ToolError("layers is required: from the bottom up, each a list of rows from the far one to the near one")
    val (dr, du, df) = offset
    val result = HashMap<Drafts.Cell, Pair<Char, Drafts.Planned>>()
    layers.forEachIndexed { up, layer ->
      val rows = (layer as? JsonArray)?.map { (it as? JsonPrimitive)?.content ?: throw ToolError("layer ${up + 1}: rows are strings") }
        ?: throw ToolError("layer ${up + 1} must be a list of rows")
      rows.forEachIndexed { index, row ->
        // Rows run from the far one to the near one, as on a map with forward at the top.
        val forward = rows.size - 1 - index
        row.forEachIndexed { right, char ->
          if (char == '.' || char == ' ') return@forEachIndexed
          val planned = entries[char.toString()] ?: throw ToolError("layer ${up + 1}, row ${index + 1}: \"$char\" isn't in the palette")
          result[Drafts.Cell(right + dr, up + du, forward + df)] = char to planned
        }
      }
    }
    val uses = result.values.groupingBy { it.first }.eachCount()
    return result.mapValues { (_, value) ->
      val (char, planned) = value
      if (planned.label != null && uses.getValue(char) > LABEL_LIMIT) Drafts.Planned(planned.data, null, planned.keys) else planned
    }
  }

  val all = listOf(draft)
}
