package chat.allay.spigot.tools

import chat.allay.core.*
import chat.allay.core.tools.*

import chat.allay.spigot.automation.Chests
import chat.allay.spigot.automation.Undo
import chat.allay.spigot.send
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.time.Instant

/** Harmless things any player may ask for: nothing here creates items or changes the world. */
object ActionTools {
  private fun tool(name: String, body: (ToolContext, Player, JsonObject) -> ToolOutput) = object : Tool {
    override val name = name
    override fun run(context: ToolContext, args: JsonObject) = body(context, context.requirePlayer(), args)
  }

  private val pointTo = tool("point_to") { context, player, args ->
    args.string("player")?.trim()?.ifEmpty { null }?.let { return@tool follow(context, player, args, it) }
    val x = args.double("x") ?: throw ToolError("x is required, or player")
    val z = args.double("z") ?: throw ToolError("z is required")
    val world = player.world
    // Nether coordinates in the overworld point somewhere else entirely, so a mismatch is an error, not a guess.
    args.string("dimension")?.let(Dimensions::parse)?.let { wanted ->
      if (wanted != Dimensions.id(world)) throw ToolError("the player is in ${Dimensions.id(world)}, not $wanted; convert the coordinates first (the nether is 1:8) or give a spot in their dimension")
    }
    val y = (args.double("y") ?: player.location.y).coerceIn(world.minHeight.toDouble(), world.maxHeight - 1.0)
    val limit = world.worldBorder.size / 2 + 1
    val center = world.worldBorder.center
    if (Math.abs(x - center.x) > limit || Math.abs(z - center.z) > limit) throw ToolError("the point is outside the world border")
    val target = Location(world, x, y, z)
    // Compasses point at this only in the overworld; elsewhere the trail still shows the way.
    player.compassTarget = target
    val tools = context.features.config().tools
    val seconds = args.int("seconds")?.also { if (it !in 1..tools.trailMaxSeconds) throw ToolError("seconds must be 1–${tools.trailMaxSeconds}") }
      ?: tools.trailSeconds
    val style = Trails.Style.parse(args.string("style"))
    Trails.show(context.plugin, player, target, seconds, style) { context.lang.send(player, "messages.arrived") }
    val dx = x - player.location.x
    val dz = z - player.location.z
    val distance = Math.round(Math.hypot(dx, dz))
    val direction = Facts.direction(dx, dz)
    val result = buildJsonObject {
      put("distance", distance)
      put("direction", direction)
      put("seconds", seconds)
      put("style", style.name.lowercase())
      put("compass", world.environment == org.bukkit.World.Environment.NORMAL)
    }
    ToolOutput(result, context.lang.text("summary.distance", "blocks" to distance, "direction" to context.lang.text("directions.$direction")))
  }

  /** `point_to` with `player`: a trail that follows someone on the asker's locator bar. */
  private fun follow(context: ToolContext, player: Player, args: JsonObject, name: String): ToolOutput {
    val target = Locator.named(player, name)
      ?: throw ToolError("nobody called $name is online; match the name against get_nearby_players, or ask the player who they mean")
    if (!Locator.shows(player, target)) {
      throw ToolError("${target.name} is online but not on the asker's locator bar right now: in another dimension, hidden (crouching, a carved pumpkin or a mob head, invisibility), or out of range. Say so; don't guess where they are")
    }
    val tools = context.features.config().tools
    val seconds = args.int("seconds")?.also { if (it !in 1..tools.trailMaxSeconds) throw ToolError("seconds must be 1–${tools.trailMaxSeconds}") }
      ?: tools.followSeconds
    val style = Trails.Style.parse(args.string("style"))
    val lang = context.lang
    Trails.follow(context.plugin, player, target, seconds, style,
      found = { lang.send(player, "messages.found", "name" to target.name) },
      lost = { lang.send(player, "messages.trail_lost", "name" to target.name) })
    val dx = target.location.x - player.location.x
    val dz = target.location.z - player.location.z
    val distance = Math.round(target.location.distance(player.location))
    val direction = Facts.direction(dx, dz)
    val result = buildJsonObject {
      put("player", target.name)
      put("distance", distance)
      put("direction", direction)
      put("seconds", seconds)
      put("style", style.name.lowercase())
      put("follows", true)
      put("compass", player.world.environment == org.bukkit.World.Environment.NORMAL)
    }
    return ToolOutput(result, lang.text("summary.distance_to", "name" to target.name, "blocks" to distance, "direction" to lang.text("directions.$direction")))
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
    val pins = (args["pins"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it as? JsonObject }?.map { pin ->
      val slot = (pin["slot"] as? JsonPrimitive)?.content?.let(Pins::slot)
        ?: throw ToolError("a pin's slot is 1–9 for the hotbar, inventory:1–inventory:27 for the main inventory, or offhand")
      Pins.Pin(slot, Pins.normalize(pin.string("item") ?: throw ToolError("each pin needs an item: an id or a group (${Pins.GROUPS.keys.joinToString()})")))
    }.orEmpty()
    if (pins.map { it.slot }.toSet().size != pins.size) throw ToolError("two pins name the same slot")
    val item = { slot: Int -> inventory.getItem(slot)?.takeUnless { it.type.isAir } }
    // A pinned item may come from anywhere in the inventory but the armor; the slot it leaves stays empty.
    val stacks = ((0..35) + Pins.OFFHAND).mapNotNull { slot -> item(slot)?.let { slot to it } }.toMap()
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
    if (player.gameMode == org.bukkit.GameMode.CREATIVE) player.closeInventory()
    sorted.slots.forEach { (slot, stack) -> inventory.setItem(slot, stack) }
    val after = Chests.pack(player)
    if (after != before) {
      undo.keep(player.uniqueId, Undo.Change(emptyList(), emptyMap(), emptyMap(), before to after))
      context.buttons += Button.Undo(context.lang.text("automation.undo"))
    }
    val merged = sorted.merged.toLong()
    val result = buildJsonObject {
      put("merged_stacks", merged)
      if (pins.isNotEmpty()) put("pinned", buildJsonObject { picked.forEach { (slot, from) -> put(Pins.name(slot), stacks.getValue(from).type.key.key) } })
      // The hotbar as it ends up, so the answer says what's really there.
      put("hotbar", buildJsonArray { (0..8).forEach { slot -> add(item(slot)?.type?.key?.key?.let(::JsonPrimitive) ?: JsonNull) } })
      put("offhand", item(Pins.OFFHAND)?.type?.key?.key)
      // And the main inventory's three rows, for “iron in the first row” and the like.
      put("rows", buildJsonArray { (0..2).forEach { row -> add(buildJsonArray { (0..8).forEach { add(item(9 + row * 9 + it)?.type?.key?.key?.let(::JsonPrimitive) ?: JsonNull) } }) } })
      pins.filter { it.slot !in picked }.takeIf { it.isNotEmpty() }?.let { missing -> put("not_found", missing.joinToString { it.item }) }
    }
    ToolOutput(result, if (merged > 0) context.lang.text("summary.sorted_merged", "stacks" to merged) else context.lang.text("summary.sorted"))
  }

  /** Reminders in one tool: `action` add, list, or cancel. */
  private val reminders = tool("reminders") { context, player, args ->
    val lang = context.lang
    when (val action = args.string("action")?.lowercase() ?: "list") {
      "add" -> {
        val minutes = args.int("minutes") ?: throw ToolError("minutes is required to add a reminder")
        if (minutes !in 1..120) throw ToolError("minutes must be 1–120")
        val text = args.string("text")?.take(100) ?: throw ToolError("text is required to add a reminder")
        val reminder = context.reminders.add(player, minutes, text)
        ToolOutput(buildJsonObject { put("id", reminder.id); put("minutes", minutes); put("text", text) }, lang.text("summary.reminder_set", "minutes" to minutes))
      }
      "list" -> {
        val list = context.reminders.of(player).list()
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
        val book = context.reminders.of(player)
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
    val above = player.location.add(0.0, 2.3, 0.0)
    repeat(3) { pulse ->
      context.plugin.server.scheduler.runTaskLater(context.plugin, Runnable {
        if (!player.isOnline) return@Runnable
        val spot = player.location.add(0.0, 2.3, 0.0)
        player.world.spawnParticle(Particle.NOTE, spot, 6, 0.4, 0.3, 0.4, 1.0)
        player.world.spawnParticle(Particle.END_ROD, spot, 12, 0.2, 0.6, 0.2, 0.02)
        player.world.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.2f + pulse * 0.3f)
      }, pulse * 8L)
    }
    ToolOutput(buildJsonObject { put("pinged", true); put("y", above.blockY) }, context.lang.text("summary.ping"))
  }

  val all = listOf(pointTo, sortInventory, reminders, ping)
}
