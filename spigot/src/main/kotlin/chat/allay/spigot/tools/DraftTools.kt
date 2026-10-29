package chat.allay.spigot.tools

import chat.allay.core.*
import chat.allay.core.tools.*
import chat.allay.spigot.Claims

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
  private const val REACH = Drafting.REACH

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
          val middle = Drafts.block(draft, DraftCell((draft.width - 1) / 2, 0, (draft.depth - 1) / 2))
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
    val corner = Drafts.block(draft, DraftCell(0, 0, 0))
    // Away from the build the draft is still there; say where, rather than read blocks a world away.
    val away = if (player.world != draft.world) "in ${Dimensions.id(draft.world)}" else Math.round(corner.location.distance(player.location)).takeIf { it > Drafts.WATCHED }?.let { "$it blocks away" }
    if (away != null) throw ToolError("the draft stands at ${corner.x}, ${corner.y}, ${corner.z} $away and is still there; it can be checked once the player is within ${Drafts.WATCHED.toInt()} blocks of it")
    if (!Claims.mayBuild(player, corner.location)) throw ToolError("that's someone else's claim where the player may not build; drafts go only where they could build")
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

  private fun offset(args: JsonObject, allowNegative: Boolean = false) = Drafting.offset(args, allowNegative)

  private fun limits(cells: Collection<DraftCell>) = Drafting.limits(cells)

  /** The cells of `layers` over `palette`, shifted by [offset]. */
  private fun cells(args: JsonObject, offset: Triple<Int, Int, Int>) = Drafting.cells(args, offset, Drafts::parse)

  val all = listOf(draft)
}
