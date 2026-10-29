package chat.allay.modcore.tools

import chat.allay.core.*
import chat.allay.core.tools.*
import chat.allay.modcore.audience
import chat.allay.modcore.automation.Chests
import chat.allay.modcore.automation.Undo

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.phys.Vec3
import java.time.Instant

/** Harmless things any player may ask for: nothing here creates items or changes the world. */
object ActionTools {
  private fun tool(name: String, body: (ToolContext, ServerPlayer, JsonObject) -> ToolOutput) = object : Tool {
    override val name = name
    override fun run(context: ToolContext, args: JsonObject) = body(context, context.requirePlayer(), args)
  }

  private val pointTo = tool("point_to") { context, player, args ->
    args.string("player")?.trim()?.ifEmpty { null }?.let { return@tool follow(context, player, args, it) }
    val x = args.double("x") ?: throw ToolError("x is required, or player")
    val z = args.double("z") ?: throw ToolError("z is required")
    val level = player.level()
    // Nether coordinates in the overworld point somewhere else entirely, so a mismatch is an error, not a guess.
    args.string("dimension")?.let(Dimensions::parse)?.let { wanted ->
      if (wanted != Dimensions.id(level)) throw ToolError("the player is in ${Dimensions.id(level)}, not $wanted; convert the coordinates first (the nether is 1:8) or give a spot in their dimension")
    }
    val y = (args.double("y") ?: player.y).coerceIn(level.minY.toDouble(), level.maxY.toDouble())
    val border = level.worldBorder
    val limit = border.size / 2 + 1
    if (Math.abs(x - border.centerX) > limit || Math.abs(z - border.centerZ) > limit) throw ToolError("the point is outside the world border")
    val target = Vec3(x, y, z)
    val tools = context.features.config().tools
    val seconds = args.int("seconds")?.also { if (it !in 1..tools.trailMaxSeconds) throw ToolError("seconds must be 1–${tools.trailMaxSeconds}") }
      ?: tools.trailSeconds
    val style = Trails.Style.parse(args.string("style"))
    val audience = player.audience
    Trails.show(context.scheduler, player, Trails.Target(level.dimension(), target), seconds, style, TrailBar.of(context.features.config(), audience, context.lang),
      label = args.string("label"), surface = args.double("y") == null, ended = { TrailNotes.ended(context.lang, audience, it) }) { TrailNotes.arrived(context.lang, audience, it) }
    // After the trail starts, which puts back what a trail to a player left: compasses point at this only in the
    // overworld; elsewhere the trail still shows the way.
    Trails.compass(player, level.dimension(), BlockPos.containing(target))
    val dx = x - player.x
    val dz = z - player.z
    val distance = Math.round(Math.hypot(dx, dz))
    val direction = Facts.direction(dx, dz)
    val result = buildJsonObject {
      put("distance", distance)
      put("direction", direction)
      put("seconds", seconds)
      put("style", style.name.lowercase())
      put("compass", level.dimension() == Level.OVERWORLD)
      put("action_bar", tools.trailBar)
    }
    ToolOutput(result, context.lang.text("summary.distance", "blocks" to distance, "direction" to context.lang.text("directions.$direction")))
  }

  /** `point_to` with `player`: a trail that follows someone on the asker's locator bar. */
  private fun follow(context: ToolContext, player: ServerPlayer, args: JsonObject, name: String): ToolOutput {
    val target = context.playerNamed(name)
      ?: throw ToolError("nobody called $name is online; match the name against get_nearby_players, or ask the player who they mean")
    if (!Locator.shows(player, target)) {
      throw ToolError("${context.nameOf(target)} is online but not on the asker's locator bar right now: in another dimension, hidden (crouching, a carved pumpkin or a mob head, invisibility), or out of range. Say so; don't guess where they are")
    }
    val tools = context.features.config().tools
    val seconds = args.int("seconds")?.also { if (it !in 1..tools.trailMaxSeconds) throw ToolError("seconds must be 1–${tools.trailMaxSeconds}") }
      ?: tools.followSeconds
    val style = Trails.Style.parse(args.string("style"))
    val lang = context.lang
    val theirName = target.gameProfile.name
    Trails.follow(context.scheduler, player, target, seconds, style, TrailBar.of(context.features.config(), player.audience, lang),
      found = { lang.send(player.audience, "messages.found", "name" to theirName) },
      lost = { lang.send(player.audience, "messages.trail_lost", "name" to theirName) })
    val dx = target.x - player.x
    val dz = target.z - player.z
    val distance = Math.round(target.position().distanceTo(player.position()))
    val direction = Facts.direction(dx, dz)
    val result = buildJsonObject {
      put("player", context.nameOf(target))
      put("distance", distance)
      put("direction", direction)
      put("seconds", seconds)
      put("style", style.name.lowercase())
      put("follows", true)
      put("compass", player.level().dimension() == Level.OVERWORLD)
      put("action_bar", tools.trailBar)
    }
    return ToolOutput(result, lang.text("summary.distance_to", "name" to theirName, "blocks" to distance, "direction" to lang.text("directions.$direction")))
  }

  private val sortInventory = tool("sort_inventory") { context, player, args ->
    val inventory = player.inventory
    val undo = context.features.automation.undo
    val minutes = context.features.config().automation.undoMinutes
    // “Put it back”: the last sort or chest change, newest first.
    if (args.bool("undo") == true) {
      val restored = undo.restore(player, minutes)
      return@tool ToolOutput(buildJsonObject { put("restored", true); if (restored.chests > 0) put("chests_restored", restored.chests) }, context.lang.text("summary.sort_undone"))
    }
    val before = Chests.pack(player)
    // Slots 9–35 by default: the hotbar is muscle memory, armor and offhand live elsewhere. The player may ask for both,
    // and the hotbar then holds the pins and what's held in hand, not the first items of the alphabet.
    val pins = (args["pins"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.map { pin ->
      val slot = (pin["slot"] as? JsonPrimitive)?.content?.let(Pins::slot)
        ?: throw ToolError("a pin's slot is 1–9 for the hotbar, inventory:1–inventory:27 for the main inventory, or offhand")
      Pins.Pin(slot, Pins.normalize(pin.string("item") ?: throw ToolError("each pin needs an item: an id or a group (${Pins.GROUPS.keys.joinToString()})")))
    }.orEmpty()
    if (pins.map { it.slot }.toSet().size != pins.size) throw ToolError("two pins name the same slot")
    val item = { slot: Int -> inventory.getItem(slot).takeUnless { it.isEmpty } }
    // A pinned item may come from anywhere in the inventory but the armor; the slot it leaves stays empty.
    val stacks = ((0..35) + Pins.OFFHAND).mapNotNull { slot -> item(slot)?.let { slot to it.copy() } }.toMap()
    val data = stacks.mapValues { it.value.data()!! }
    val picked = Pins.pick(pins, data, Chests.block)
    val layout = Layout.parse(args["layout"] as? JsonObject, Layout.By.NAME)
    Layout.problem(layout, 27)?.let { throw ToolError(it) }
    val sorted = Pins.arrange(
      items = stacks,
      picked = picked,
      hotbar = args.bool("hotbar") == true,
      offhand = args.bool("offhand") == true,
      spec = layout,
      stacks = Chests.stacks,
      block = Chests.block,
    ) ?: throw ToolError("the inventory has overstacked items or wouldn't fit back, so it can't be sorted safely")
    // In creative the client keeps its own copy of an open inventory and would write it back over this.
    if (player.isCreative) player.closeContainer()
    sorted.slots.forEach { (slot, stack) -> inventory.setItem(slot, stack?.copy() ?: ItemStack.EMPTY) }
    inventory.setChanged()
    val after = Chests.pack(player)
    if (!sameSlots(after, before)) {
      undo.keep(player.uuid, Undo.Change(emptyList(), emptyMap(), emptyMap(), before to after))
      context.buttons += Button.Undo(context.lang.text("automation.undo"))
    }
    val merged = sorted.merged.toLong()
    val result = buildJsonObject {
      put("merged_stacks", merged)
      if (pins.isNotEmpty()) put("pinned", buildJsonObject { picked.forEach { (slot, from) -> put(Pins.name(slot), Recipes.id(stacks.getValue(from).item)) } })
      // The hotbar as it ends up, so the answer says what's really there.
      put("hotbar", buildJsonArray { (0..8).forEach { slot -> add(item(slot)?.let { JsonPrimitive(Recipes.id(it.item)) } ?: JsonNull) } })
      put("offhand", item(Pins.OFFHAND)?.let { Recipes.id(it.item) })
      // And the main inventory's three rows, for “iron in the first row” and the like.
      put("rows", buildJsonArray { (0..2).forEach { row -> add(buildJsonArray { (0..8).forEach { add(item(9 + row * 9 + it)?.let { stack -> JsonPrimitive(Recipes.id(stack.item)) } ?: JsonNull) } }) } })
      pins.filter { it.slot !in picked }.takeIf { it.isNotEmpty() }?.let { missing -> put("not_found", missing.joinToString { it.item }) }
    }
    ToolOutput(result, if (merged > 0) context.lang.text("summary.sorted_merged", "stacks" to merged) else context.lang.text("summary.sorted"))
  }

  /** The same items, components, and counts in every slot, as Bukkit's list equality reads it. */
  private fun sameSlots(a: List<ItemStack?>, b: List<ItemStack?>) =
    a.size == b.size && a.indices.all { index -> val x = a[index]; val y = b[index]; if (x == null || y == null) x == null && y == null else ItemStack.matches(x, y) }

  /** Reminders in one tool: `action` add, list, or cancel. */
  private val reminders = tool("reminders") { context, player, args ->
    val lang = context.lang
    when (val action = args.string("action")?.lowercase() ?: "list") {
      "add" -> {
        val minutes = args.int("minutes") ?: throw ToolError("minutes is required to add a reminder")
        if (minutes !in 1..120) throw ToolError("minutes must be 1–120")
        val text = args.string("text")?.take(100) ?: throw ToolError("text is required to add a reminder")
        val reminder = context.reminders.add(player.audience, minutes, text)
        ToolOutput(buildJsonObject { put("id", reminder.id); put("minutes", minutes); put("text", text) }, lang.text("summary.reminder_set", "minutes" to minutes))
      }
      "list" -> {
        val list = context.reminders.of(player.uuid).list()
        val now = System.currentTimeMillis()
        val result = buildJsonObject {
          put("reminders", buildJsonArray {
            list.forEach { reminder ->
              add(buildJsonObject {
                put("id", reminder.id)
                put("text", reminder.text)
                put("minutes_left", ((reminder.dueAt - now).coerceAtLeast(0) + 59_999) / 60_000)
                put("due_at", Instant.ofEpochMilli(reminder.dueAt).toString())
              })
            }
          })
        }
        ToolOutput(result, if (list.isEmpty()) lang.text("summary.reminders_none") else lang.text("summary.reminders", "count" to list.size))
      }
      "cancel" -> {
        val book = context.reminders.of(player.uuid)
        val all = args.bool("all") == true
        val cancelled = if (all) book.cancelAll() else book.cancel(args.string("id") ?: throw ToolError("id is required to cancel a reminder, or all: true"))
        if (cancelled == 0 && !all) throw ToolError("there is no reminder with this id")
        ToolOutput(buildJsonObject { put("cancelled", cancelled) }, lang.text("summary.reminders_cancelled", "count" to cancelled))
      }
      else -> throw ToolError("action must be add, list, or cancel, not $action")
    }
  }

  private val ping = tool("ping_me") { context, player, _ ->
    // Heard and seen by everyone nearby: the point is to find the player in a crowd or a dark cave.
    val above = player.position().add(0.0, 2.3, 0.0)
    val id = player.uuid
    repeat(3) { pulse ->
      context.scheduler.later(pulse * 8L) {
        val now = player.level().server.playerList.getPlayer(id)?.takeUnless { it.hasDisconnected() } ?: return@later
        val level = now.level()
        val spot = now.position().add(0.0, 2.3, 0.0)
        level.sendParticles(ParticleTypes.NOTE, spot.x, spot.y, spot.z, 6, 0.4, 0.3, 0.4, 1.0)
        level.sendParticles(ParticleTypes.END_ROD, spot.x, spot.y, spot.z, 12, 0.2, 0.6, 0.2, 0.02)
        level.playSound(null, now.x, now.y, now.z, SoundEvents.NOTE_BLOCK_PLING, SoundSource.MASTER, 1f, 1.2f + pulse * 0.3f)
      }
    }
    ToolOutput(buildJsonObject { put("pinged", true); put("y", BlockPos.containing(above).y) }, context.lang.text("summary.ping"))
  }

  val all: List<Tool> = listOf(pointTo, sortInventory, reminders, ping)
}
