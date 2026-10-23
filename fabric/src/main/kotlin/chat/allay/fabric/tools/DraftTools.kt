package chat.allay.fabric.tools

import chat.allay.core.*
import chat.allay.core.tools.*
import chat.allay.fabric.Claims

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.server.level.ServerPlayer

/**
 * `show_draft`: the model writes a build as layers of characters over a palette, the way it would draw a map, and
 * the mod turns it to face the player and leads them through it ([Drafts]). Big builds come in parts: `add`
 * puts more layers or a wing next to what's there, as long as the whole stays within the size limit.
 */
object DraftTools {
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
          val cells = cells(args, Drafting.offset(args))
          val merged = HashMap(draft.cells).apply { putAll(cells) }
          Drafting.limits(merged.keys)
          draft.cells.clear()
          draft.cells.putAll(merged)
          Drafts.redraw(draft)
        }
        "layer" -> {
          val wanted = args["layer"] as? JsonPrimitive ?: throw ToolError("layer is required: a number from 1, next, prev, or all")
          draft.layer = when (wanted.content.lowercase()) {
            "next" -> (draft.layer + 1).coerceAtMost(draft.layers - 1)
            "prev" -> (draft.layer - 1).coerceAtLeast(0)
            "all" -> if (draft.cells.size <= Drafting.MAX_SHOWN) -1 else throw ToolError("the draft has ${draft.cells.size} blocks; all layers at once show only below ${Drafting.MAX_SHOWN}")
            else -> (wanted.content.toIntOrNull() ?: throw ToolError("layer must be a number, next, prev, or all")).also {
              if (it !in 1..draft.layers) throw ToolError("layer must be 1–${draft.layers}")
            } - 1
          }
          Drafts.redraw(draft)
        }
        "move" -> {
          val (right, up, forward) = Drafting.offset(args, allowNegative = true)
          val moved = Drafting.place(draft.corner, draft.facing, DraftCell(right, up, forward))
          check(player, draft.level.at(moved.first, moved.second, moved.third))
          draft.corner = moved
          Drafts.redraw(draft)
        }
        "rotate" -> {
          val turns = ((args.int("turns") ?: 1) % 4 + 4) % 4
          // Around its middle, so the build turns in place instead of swinging around a corner.
          val middle = Drafts.block(draft, DraftCell((draft.width - 1) / 2, 0, (draft.depth - 1) / 2))
          repeat(turns) { draft.facing = draft.facing.right }
          val facing = draft.facing
          val right = facing.right
          draft.corner = Triple(middle.x - right.dx * ((draft.width - 1) / 2) - facing.dx * ((draft.depth - 1) / 2), draft.corner.second, middle.z - right.dz * ((draft.width - 1) / 2) - facing.dz * ((draft.depth - 1) / 2))
          Drafts.redraw(draft)
        }
        "check" -> return check(context, player, draft)
        else -> throw ToolError("action must be show, add, layer, move, rotate, check, or clear, not $action")
      }
      return ToolOutput(describe(draft), lang.text("summary.draft", "blocks" to draft.cells.size, "layers" to draft.layers))
    }
  }

  private fun show(context: ToolContext, player: ServerPlayer, args: JsonObject): ToolOutput {
    val title = args.string("title")?.trim()?.take(32)?.ifEmpty { null } ?: throw ToolError("title is required: a short name in the player's language")
    val cells = cells(args, Drafting.offset(args))
    if (cells.isEmpty()) throw ToolError("the draft has no blocks")
    Drafting.limits(cells.keys)
    val facing = Drafts.cardinal(player)
    val width = (cells.keys.maxOf { it.right }) + 1
    val corner = if (args.int("x") != null && args.int("z") != null) {
      Triple(args.int("x")!!, args.int("y") ?: player.blockY, args.int("z")!!)
    } else {
      // Two blocks ahead at the player's feet, centered on where they look.
      val right = facing.right
      val feet = player.blockPosition()
      Triple(feet.x + facing.dx * 2 - right.dx * (width / 2), feet.y + (args.int("y_offset") ?: 0), feet.z + facing.dz * 2 - right.dz * (width / 2))
    }
    check(player, player.level().at(corner.first, corner.second, corner.third))
    val draft = Drafts.Draft(player.uuid, title, player.level(), corner, facing, HashMap(cells))
    Drafts.begin(player, draft)
    return ToolOutput(describe(draft), context.lang.text("summary.draft", "blocks" to draft.cells.size, "layers" to draft.layers))
  }

  /** The corner must be near and on ground the player may build on. */
  private fun check(player: ServerPlayer, corner: BlockAt) {
    if (corner.y < corner.level.minY || corner.y > corner.level.maxY) throw ToolError("outside the world's height")
    if (corner.corner.distanceTo(player.position()) > Drafting.REACH) throw ToolError("the draft must start within ${Drafting.REACH} blocks of the player")
    if (!Claims.mayBuild(player, corner.level, corner.pos)) throw ToolError("that's someone else's claim where the player may not build; drafts go only where they could build")
  }

  private fun check(context: ToolContext, player: ServerPlayer, draft: Drafts.Draft): ToolOutput {
    check(player, Drafts.block(draft, DraftCell(0, 0, 0)))
    val problems = buildJsonArray {
      var listed = 0
      draft.cells.entries.sortedWith(compareBy({ it.key.up }, { it.key.forward }, { it.key.right })).forEach { (cell, planned) ->
        if (listed >= 20 || Drafts.state(draft, cell, planned) != Drafts.State.WRONG) return@forEach
        val block = Drafts.block(draft, cell)
        add(buildJsonObject {
          put("layer", cell.up + 1); put("x", block.x); put("y", block.y); put("z", block.z)
          put("wanted", Drafts.wanted(draft, planned)); put("found", BlockStateParser.serialize(block.state).removePrefix("minecraft:"))
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

  /** The cells of `layers` over `palette`, shifted by [offset]. */
  private fun cells(args: JsonObject, offset: Triple<Int, Int, Int>) = Drafting.cells(args, offset, Drafts::parse)

  val all = listOf(draft)
}
