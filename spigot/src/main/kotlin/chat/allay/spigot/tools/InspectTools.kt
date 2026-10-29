package chat.allay.spigot.tools

import chat.allay.core.*
import chat.allay.core.tools.*

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.bukkit.Nameable
import org.bukkit.block.BrewingStand
import org.bukkit.block.Container
import org.bukkit.entity.Player
import org.bukkit.inventory.DoubleChestInventory
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import chat.allay.core.tools.Facts.place

/** Tools that search and inspect what is around the player: containers, items, brewing. */
object InspectTools {
  private fun tool(name: String, body: (ToolContext, Player, JsonObject) -> ToolOutput) = object : Tool {
    override val name = name
    override fun run(context: ToolContext, args: JsonObject) = body(context, context.requirePlayer(), args)
  }

  /** Block entities within the radius, reading only the chunks the radius touches. */
  private fun blockEntitiesNear(origin: org.bukkit.Location, radius: Int): List<org.bukkit.block.BlockState> {
    val world = origin.world ?: return emptyList()
    val result = mutableListOf<org.bukkit.block.BlockState>()
    for (cx in ((origin.blockX - radius) shr 4)..((origin.blockX + radius) shr 4)) for (cz in ((origin.blockZ - radius) shr 4)..((origin.blockZ + radius) shr 4)) {
      if (!world.isChunkLoaded(cx, cz)) continue
      world.getChunkAt(cx, cz).tileEntities.filterTo(result) { it.location.distanceSquared(origin) <= radius * radius }
    }
    return result
  }

  /** Chunks the radius touches that aren't loaded, so a search there read nothing. */
  private fun unloadedNear(origin: org.bukkit.Location, radius: Int): Int {
    val world = origin.world ?: return 0
    var unloaded = 0
    for (cx in ((origin.blockX - radius) shr 4)..((origin.blockX + radius) shr 4)) for (cz in ((origin.blockZ - radius) shr 4)..((origin.blockZ + radius) shr 4)) {
      if (!world.isChunkLoaded(cx, cz)) unloaded++
    }
    return unloaded
  }

  /**
   * What a container search reached: the containers the player could open themself, nearest first, and what it
   * couldn't look into, so the model never reads “none here” as “none”. [locked] counts claimed containers only;
   * an unopened structure chest stays out of every count, as anti-xray hides it.
   */
  private class Reach(val containers: List<Container>, val locked: Int, val unloaded: Int) {
    fun report(into: kotlinx.serialization.json.JsonObjectBuilder) {
      if (locked > 0) into.put("locked_containers", locked)
      if (unloaded > 0) into.put("unloaded_chunks", unloaded)
    }
  }

  /**
   * Containers within the radius that the player could open themself ([chat.allay.spigot.Claims]), nearest first.
   * Both halves of a double chest hold the whole inventory, so the nearer half stands for it and the other is dropped.
   */
  private fun containersNear(player: Player, radius: Int): Reach {
    val (open, closed) = blockEntitiesNear(player.location, radius).filterIsInstance<Container>()
      .partition { chat.allay.spigot.Claims.mayOpen(player, it) }
    fun distinct(list: List<Container>) = list.sortedBy { it.location.distanceSquared(player.location) }
      .distinctBy { ((it.inventory as? DoubleChestInventory)?.leftSide?.location ?: it.location).toVector() }
    val locked = distinct(closed.filter { (it as? org.bukkit.loot.Lootable)?.lootTable == null }).size
    return Reach(distinct(open), locked, unloadedNear(player.location, radius))
  }

  private val scanContainers = tool("scan_nearby_containers") { context, player, args ->
    val radius = (args.int("radius") ?: 32).coerceIn(1, 52)
    val reach = containersNear(player, radius)
    val found = reach.containers
    val rows = found.take(20).map { container ->
      val items = container.inventory.contents.asIterable()
      buildJsonObject {
        put("type", container.type.key.key)
        place(container.x, container.y, container.z, player.location)
        (container as? Nameable)?.customName?.let { put("name", it) }
        put("used_slots", items.count { it != null && !it.type.isAir })
        val counts = Items.counts(items.data(), limit = 4)
        put("top", counts["items"]!!)
        counts["more_kinds"]?.let { put("more_kinds", it) }
        Items.inside(items.data()).takeIf { it.isNotEmpty() }?.let { totals ->
          putJsonObject("inside_containers") { totals.entries.sortedByDescending { it.value }.take(4).forEach { (id, n) -> put(id, n) } }
          if (totals.size > 4) put("more_kinds_inside", totals.size - 4)
        }
      }
    }
    val result = buildJsonObject {
      put("radius", radius)
      put("containers", buildJsonArray { rows.forEach(::add) })
      if (found.size > rows.size) put("more_containers", found.size - rows.size)
      reach.report(this)
    }
    ToolOutput(result, context.lang.text("summary.containers", "count" to found.size))
  }

  /** Places listed by find_item at most; the total counts them all. */
  private const val MAX_PLACES = 30

  private val findItem = tool("find_item") { context, player, args ->
    val asked = args.string("item") ?: throw ToolError("item is required, such as diamond or log")
    val query = Names.normalize(asked)
    val radius = (args.int("radius") ?: 32).coerceIn(1, 52)
    // An exact id finds that item, a plural its singular; anything else finds every item whose id contains it: “log”
    // finds oak_log and birch_log, “torches” the torch.
    val exact = runCatching { InteractTools.material(asked).key.key }.getOrNull()?.takeIf { it in Names.forms(query) }
    val parts = Names.forms(query).filter { part -> InteractTools.itemIds.any { part in it } }.take(1)
    if (exact == null && parts.isEmpty()) throw ToolError(Names.missing(asked, InteractTools.itemIds))
    val match: (String) -> Boolean = if (exact != null) { id -> id == exact } else { id -> parts.any { it in id } }
    val ids = sortedSetOf<String>()
    val places = mutableListOf<JsonObject>()
    var total = 0
    fun count(item: ItemStack?): Pair<Int, Int> = Items.matching(item.data()) { id -> match(id).also { if (it) ids += id } }
    fun stack(where: String, slot: Any, item: ItemStack?) {
      val (direct, nested) = count(item)
      if (direct + nested == 0) return
      total += direct + nested
      places += buildJsonObject {
        put("where", where)
        when (slot) {
          is Int -> put("slot", slot)
          else -> put("slot", slot.toString())
        }
        put("count", direct + nested)
        if (nested > 0) put("inside", item!!.type.key.key)
      }
    }
    val inventory = player.inventory
    inventory.storageContents.forEachIndexed { slot, item -> stack("inventory", slot, item) }
    stack("offhand", "offhand", inventory.itemInOffHand)
    listOf("head" to EquipmentSlot.HEAD, "chest" to EquipmentSlot.CHEST, "legs" to EquipmentSlot.LEGS, "feet" to EquipmentSlot.FEET)
      .forEach { (name, slot) -> stack("armor", name, inventory.getItem(slot)) }
    player.enderChest.contents.forEachIndexed { slot, item -> stack("enderchest", slot, item) }
    val found = mutableListOf<Pair<Container, Int>>()
    val reach = containersNear(player, radius)
    reach.containers.forEach { container ->
      var direct = 0
      var nested = 0
      container.inventory.contents.forEach { item -> count(item).let { (d, n) -> direct += d; nested += n } }
      if (direct + nested == 0) return@forEach
      total += direct + nested
      found += container to direct + nested
      places += buildJsonObject {
        put("where", "container")
        put("type", container.type.key.key)
        place(container.x, container.y, container.z, player.location)
        (container as? Nameable)?.customName?.let { put("name", it) }
        put("count", direct + nested)
        if (nested > 0) put("inside_boxes", nested)
      }
    }
    val highlighted = highlight(context, player, found, query)
    val result = buildJsonObject {
      put("query", query)
      if (highlighted) put("highlighted", "the chests glow with a label for the player; a way button is under the answer when the nearest is far or out of sight")
      if (exact == null) put("matched_ids", buildJsonArray { ids.take(20).forEach { add(JsonPrimitive(it)) } })
      put("total", total)
      put("radius", radius)
      put("places", buildJsonArray { places.take(MAX_PLACES).forEach(::add) })
      if (places.size > MAX_PLACES) put("more_places", places.size - MAX_PLACES)
      reach.report(this)
    }
    ToolOutput(result, if (total == 0) context.lang.text("summary.find_none") else context.lang.text("summary.find", "total" to total, "places" to places.size))
  }

  /**
   * The chests find_item found glow for the asker with “diamond ×12” over them, so the model needn't call show_marks;
   * when the nearest is far or behind a wall, a way button goes under the answer. False when marks are switched off.
   */
  private fun highlight(context: ToolContext, player: Player, found: List<Pair<Container, Int>>, query: String): Boolean {
    val settings = context.features.config().tools
    if (found.isEmpty() || !settings.enabled("show_marks")) return false
    val name = query.replace('_', ' ')
    val marks = found.take(settings.maxMarks).flatMap { (container, count) ->
      chat.allay.spigot.automation.Blocks.halves(container.block).mapIndexed { index, half -> Marks.Mark(half, null, if (index == 0) "$name ×$count" else null, Marks.Hue.YELLOW) }
    }
    Marks.show(context.plugin, player, marks, settings.marksSeconds)
    val nearest = found.first().first.block
    if (nearest.location.distance(player.location) > 12 || !chat.allay.spigot.automation.Blocks.sees(player, nearest)) {
      if (context.buttons.size < 6) context.buttons += Button.Point(context.lang.text("buttons.way_to_chest"), nearest.x, nearest.y, nearest.z, Dimensions.id(player.world))
    }
    return true
  }

  private val brewingIds = setOf(
    "nether_wart", "blaze_powder", "blaze_rod", "glass_bottle", "potion", "splash_potion", "lingering_potion", "dragon_breath",
    "redstone", "glowstone_dust", "gunpowder", "fermented_spider_eye", "spider_eye", "sugar", "rabbit_foot",
    "glistering_melon_slice", "golden_carrot", "magma_cream", "ghast_tear", "pufferfish", "phantom_membrane", "turtle_helmet",
    "breeze_rod", "cobweb", "slime_block", "stone", "brown_mushroom", "brewing_stand", "cauldron", "water_bucket",
  )

  private const val MAX_STANDS = 4

  private val brewing = tool("get_brewing_items") { context, player, _ ->
    // The game's ingredients, and whatever data packs and plugins brew with on this server.
    val ids = brewingIds + BrewingRecipes.all(player.server).map { it.reagent }
    val contents = player.inventory.contents.filterNotNull()
    val items = contents.filter { it.type.key.key in ids }
    val inside = Items.inside(contents.data()).filterKeys { it in ids }
    val origin = player.location
    // A stand is a container like any other: only the ones the player could open show.
    val near = blockEntitiesNear(origin, 8).filterIsInstance<BrewingStand>().filter { chat.allay.spigot.Claims.mayOpen(player, it) }
      .sortedBy { it.location.distanceSquared(origin) }
    val stands = near.take(MAX_STANDS)
    val result = buildJsonObject {
      put("inventory", buildJsonArray { items.forEach { it.compact()?.let(::add) } })
      if (inside.isNotEmpty()) putJsonObject("inside_boxes_and_bundles") { inside.forEach { (id, n) -> put(id, n) } }
      if (near.size > stands.size) put("more_stands", near.size - stands.size)
      put("brewing_stands", buildJsonArray {
        stands.forEach { stand ->
          val inventory = stand.inventory
          add(buildJsonObject {
            place(stand.x, stand.y, stand.z, origin)
            put("fuel_level", stand.fuelLevel)
            if (stand.brewingTime > 0) put("seconds_left", stand.brewingTime / 20)
            inventory.ingredient.compact()?.let { put("ingredient", it) }
            inventory.fuel.compact()?.let { put("fuel", it) }
            put("bottles", buildJsonArray { (0..2).forEach { slot -> inventory.getItem(slot).compact()?.let(::add) } })
          })
        }
      })
    }
    ToolOutput(result, context.lang.text("summary.brewing", "stacks" to items.size, "stands" to stands.size))
  }

  private val armorSlots = mapOf("head" to EquipmentSlot.HEAD, "chest" to EquipmentSlot.CHEST, "legs" to EquipmentSlot.LEGS, "feet" to EquipmentSlot.FEET)
  private val slotted = setOf("inventory", "enderchest", "container")

  /**
   * One item the player can reach: in hand, worn, in the inventory or ender chest, or in the container under the
   * crosshair; with `inner_slot`, the item at that slot inside a shulker box or bundle found there.
   */
  private val inspectItem = tool("inspect_item") { context, player, args ->
    val from = args.string("from")?.lowercase() ?: "hand"
    val slot = args.int("slot")
    fun slotIn(size: Int): Int = slot?.takeIf { it in 0 until size } ?: throw ToolError("$from needs a slot from 0 to ${size - 1}")
    val inventory = player.inventory
    val outer = when (from) {
      "hand" -> inventory.itemInMainHand
      "offhand" -> inventory.itemInOffHand
      in armorSlots -> inventory.getItem(armorSlots.getValue(from))
      "inventory" -> inventory.storageContents.let { it[slotIn(it.size)] }
      "enderchest" -> player.enderChest.let { it.getItem(slotIn(it.size)) }
      "container" -> LookTools.targetContainer(player).second.inventory.let { it.getItem(slotIn(it.size)) }
      else -> throw ToolError("from must be hand, offhand, head, chest, legs, feet, inventory, enderchest, or container")
    }
    if (outer == null || outer.type.isAir) throw ToolError(if (slot != null && from in slotted) "slot $slot of $from is empty" else "nothing in $from")
    val innerSlot = args.int("inner_slot")
    val item = if (innerSlot == null) outer else {
      val inside = contents(outer, outer.itemMeta) ?: throw ToolError("${outer.type.key.key} is not a shulker box or bundle")
      inside.getOrNull(innerSlot)?.takeUnless { it.type.isAir } ?: throw ToolError("slot $innerSlot inside ${outer.type.key.key} is empty; its contents list the slots")
    }
    val result = buildJsonObject {
      put("from", from)
      slot?.takeIf { from in slotted }?.let { put("slot", it) }
      innerSlot?.let { put("inside", outer.type.key.key); put("inner_slot", it) }
      detail(item, player).forEach { (key, value) -> put(key, value) }
    }
    ToolOutput(result, context.lang.text("summary.item", "id" to item.type.key.key))
  }

  val all: List<Tool> = listOf(scanContainers, findItem, brewing, inspectItem)
}
