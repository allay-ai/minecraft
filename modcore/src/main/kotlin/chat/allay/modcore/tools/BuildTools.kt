package chat.allay.modcore.tools

import chat.allay.modcore.Perms
import chat.allay.core.Permissions
import chat.allay.core.building.Blueprints
import chat.allay.core.building.EstimateSheet
import chat.allay.core.building.Families
import chat.allay.core.building.Materials
import chat.allay.core.building.Placement
import chat.allay.core.tasks.SourceScope
import chat.allay.core.tools.*
import chat.allay.modcore.automation.Crafting
import chat.allay.modcore.automation.Sources
import chat.allay.modcore.menus.EstimateMenu

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer

/**
 * `build`: a build from Allay's catalog as a preview ([Previews]) the player moves, turns, mirrors, and makes of other
 * materials before an agent builds it from their blocks. The backend names the file and its hash; this downloads
 * nothing else, and never on the server thread. A build's shape never changes here: only where it stands, how it
 * faces, and what it's made of.
 */
object BuildTools {
  /** What an estimate reads from: everything the player could reach themself, the ender chest too, counted apart. */
  private val LOOK = SourceScope(ender_chest = true)

  private val build = object : Tool {
    override val name = "build"
    override val offMain = true

    override fun run(context: ToolContext, args: JsonObject): ToolOutput {
      val config = context.features.config().building
      if (!config.enabled) throw ToolError("building is switched off on this server")
      val action = args.string("action")?.lowercase() ?: throw ToolError("action is required")
      if (action == "preview") return preview(context, args)
      return context.main<ToolOutput> {
        val player = context.requirePlayer()
        val preview = Previews.of(player) ?: throw ToolError("the player has no build in preview; preview one first")
        when (action) {
          "move" -> {
            val moved = preview.placement.moved(args.int("right") ?: 0, args.int("up") ?: 0, args.int("forward") ?: 0)
            if (!Previews.fits(preview.level, preview.blueprint, moved)) throw ToolError(height(preview))
            preview.placement = moved
            Previews.changed(preview)
          }
          "rotate" -> {
            preview.placement = preview.placement.turned(preview.blueprint, args.int("turns") ?: 1)
            Previews.changed(preview)
          }
          "mirror" -> {
            preview.placement = preview.placement.copy(mirrored = !preview.placement.mirrored)
            Previews.changed(preview)
          }
          "swap" -> {
            val from = args.string("from")?.lowercase()?.removePrefix("minecraft:") ?: throw ToolError("swap needs from: the block it has, like cobblestone")
            val to = args.string("to")?.lowercase()?.removePrefix("minecraft:") ?: throw ToolError("swap needs to: the block to build with, like stone_bricks")
            Previews.refusal(to)?.let { throw ToolError(it) }
            if (!Families.has(preview.states, from)) throw ToolError("the build has no $from")
            val missing = Previews.swap(preview, from, to)
            return@main ToolOutput(buildJsonObject {
              put("swapped", "$from → $to")
              if (missing.isNotEmpty()) put("missing", buildJsonArray { missing.forEach { add(it) } })
              put("note", "the preview shows it now; the estimate counts it")
            }, context.lang.text("summary.build_swapped", "from" to from, "to" to to))
          }
          "estimate" -> return@main estimate(context, player, preview)
          "clear" -> {
            Previews.clear(player)
            return@main ToolOutput(buildJsonObject { put("cleared", true) }, context.lang.text("summary.build_cleared"))
          }
          else -> throw ToolError("action is preview, move, rotate, mirror, swap, estimate, or clear, not $action")
        }
        ToolOutput(describe(preview), context.lang.text("summary.build_moved"))
      }
    }
  }

  /** Downloads the build the backend named, off the server thread, then shows it in front of the player. */
  private fun preview(context: ToolContext, args: JsonObject): ToolOutput {
    val named = args["schematic"] as? JsonObject ?: throw ToolError("preview needs schematic from the catalog; update Allay's backend")
    val source = Blueprints.Source(
      named.string("id") ?: throw ToolError("the catalog named no id"),
      named.string("url") ?: throw ToolError("the catalog named no file"),
      named.string("sha256") ?: throw ToolError("the catalog named no hash"),
      (named["bytes"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: throw ToolError("the catalog named no size"),
    )
    val (player, limits) = context.main {
      val player = context.requirePlayer()
      if (!Perms.check(player, Permissions.BUILD)) throw ToolError("the player may not build here")
      player to context.features.config().building.tiers.of(OperatorTools.operator(player)) { Perms.check(player, Permissions.build(it)) }
    }
    val blueprint = try {
      Previews.blueprints.load(source)
    } catch (error: Blueprints.Refused) {
      throw ToolError(error.message ?: "the build can't be shown")
    }
    if (blueprint.blocks > limits.maxBlocks || maxOf(blueprint.width, blueprint.height, blueprint.length) > limits.maxSize) {
      throw ToolError("${blueprint.blocks} blocks, ${blueprint.width}×${blueprint.height}×${blueprint.length}: this player may build up to ${limits.maxBlocks} blocks, ${limits.maxSize} a side here; offer a smaller one")
    }
    val title = named.string("title")?.take(60) ?: source.id
    return context.main<ToolOutput> {
      val facing = Drafts.cardinal(player)
      val feet = player.blockPosition()
      val level = player.level()
      val room = level.maxY - level.minY + 1
      if (blueprint.height > room) throw ToolError("the build is ${blueprint.height} blocks tall, this world $room: offer a lower one")
      // Too high or too low for this world: it moves up or down until it fits.
      var placement = Placement.before(blueprint, Triple(feet.x, feet.y, feet.z), facing)
      val (low, high) = placement.bounds(blueprint)
      if (low.second < level.minY) placement = placement.moved(0, level.minY - low.second, 0)
      if (high.second > level.maxY) placement = placement.moved(0, level.maxY - high.second, 0)
      val preview = Previews.Preview(player.uuid, source, title, blueprint, player.level(), placement)
      Previews.show(player, preview)
      ToolOutput(describe(preview).let { JsonObject(it + ("author" to JsonPrimitive(named.string("author") ?: blueprint.author))) }, context.lang.text("summary.build_shown", "title" to title))
    }
  }

  /** Why a move can't go there: it would take the build past the world's height, from where it stands now. */
  private fun height(preview: Previews.Preview): String {
    val level = preview.level
    val (low, high) = preview.placement.bounds(preview.blueprint)
    return "the build would reach past this world's height (y ${level.minY} to ${level.maxY}); it stands from y ${low.second} to ${high.second}, so it moves up ${level.maxY - high.second} and down ${low.second - level.minY} at most"
  }

  private fun describe(preview: Previews.Preview) = buildJsonObject {
    val b = preview.blueprint
    put("title", preview.title)
    put("size", "${b.width}×${b.height}×${b.length}")
    put("blocks", b.blocks)
    val (low, high) = preview.placement.bounds(b)
    put("from", buildJsonObject { put("x", low.first); put("y", low.second); put("z", low.third) })
    put("to", buildJsonObject { put("x", high.first); put("y", high.second); put("z", high.third) })
    put("facing", preview.placement.facing.name.lowercase())
    if (preview.placement.mirrored) put("mirrored", true)
    if (preview.swaps.isNotEmpty()) put("swaps", buildJsonArray { preview.swaps.forEach { (from, to) -> add("$from → $to") } })
  }

  /** What the preview needs against what the player has: in the inventory, the ender chest, and the chests around. */
  private fun estimate(context: ToolContext, player: ServerPlayer, preview: Previews.Preview): ToolOutput {
    // The lot's ground that lands on the world's own ground costs nothing: the world has it there.
    val covered = Previews.covered(preview)
    val estimate = Materials.estimate(preview.blueprint, preview.states).let { all ->
      all.copy(items = all.items.mapValues { (item, n) -> n - (covered[item] ?: 0) }.filterValues { it > 0 })
    }
    val sources = Sources.around(player, context.features.automation.settings().radius, LOOK)
    // The ender chest apart: the builder takes from it only once the player allows it.
    val have = HashMap<String, Int>()
    val ender = HashMap<String, Int>()
    for (source in sources.list) for (stack in source.slots()) {
      val key = stack?.let(Crafting::key) ?: continue
      if (key in estimate.items) (if (source.place.startsWith("ender:")) ender else have).merge(key, stack.count, Int::plus)
    }
    var inEnder = 0
    var short = 0
    val unknown = estimate.items.keys.filter { !item(it) }.toSet()
    val unobtainable = estimate.unobtainable + unknown.associateWith { estimate.items.getValue(it) }
    val result = buildJsonObject {
      put("needs", buildJsonArray {
        estimate.items.entries.sortedByDescending { it.value }.forEach { (item, need) ->
          if (item in unknown) return@forEach
          val got = have[item] ?: 0
          if (got < need) short += need - got
          val there = minOf(ender[item] ?: 0, (need - got).coerceAtLeast(0))
          inEnder += there
          add(buildJsonObject { put("item", item); put("need", need); put("have", minOf(got, need)); if (there > 0) put("in_ender_chest", there) })
        }
      })
      put("missing_in_all", short)
      if (inEnder > 0) put("ender_chest_note", "$inEnder of what's short lie in the ender chest: the builder takes them only if the player allows it, said in the builder's brief")
      if (estimate.tools.isNotEmpty()) put("tool_uses", buildJsonObject { estimate.tools.forEach { (tool, n) -> put(tool, n) } })
      if (estimate.water) put("water_bucket", "one, kept: water sources and waterlogged blocks")
      if (unobtainable.isNotEmpty()) {
        put("unobtainable", buildJsonObject { unobtainable.forEach { (block, n) -> put(block, n) } })
        put("unobtainable_note", "no survival player gets these: ask the player what to build them with (swap), or skip them")
      }
      if (estimate.free.isNotEmpty()) put("free", buildJsonObject { estimate.free.forEach { (block, n) -> put(block, n) } })
      if (player.isCreative) put("creative", "the player is in creative: the builder takes nothing, so nothing is short; build it when they say")
    }
    // The player reads it in a window; the model gets the same numbers to talk about.
    val catalog = Catalogs.build(context)
    val rows = estimate.items.filterKeys { it !in unknown }.map { (item, need) -> EstimateSheet.Row(item, need, minOf(have[item] ?: 0, need), ender[item] ?: 0) }
    EstimateMenu(player, context.lang, preview.title, rows, estimate, unobtainable, player.isCreative) { catalog.label(it, context.nameLocale) ?: it.replace('_', ' ') }.open()
    return ToolOutput(result, context.lang.text("summary.build_estimate", "missing" to short))
  }

  /** Whether this game has an item by that name. */
  private fun item(name: String): Boolean {
    val id = Identifier.tryParse(if (':' in name) name else "minecraft:$name") ?: return false
    return BuiltInRegistries.ITEM.containsKey(id)
  }

  val all = listOf(build)
}
