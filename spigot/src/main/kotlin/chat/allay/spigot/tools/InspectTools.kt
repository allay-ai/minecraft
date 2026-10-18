package chat.allay.spigot.tools

import chat.allay.core.*
import chat.allay.core.tools.*

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.bukkit.Material
import org.bukkit.Nameable
import org.bukkit.block.BrewingStand
import org.bukkit.block.Container
import org.bukkit.block.CreatureSpawner
import org.bukkit.block.data.Ageable
import org.bukkit.block.data.Levelled
import org.bukkit.block.data.type.Farmland
import org.bukkit.entity.Animals
import org.bukkit.entity.Player
import org.bukkit.inventory.DoubleChestInventory
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import chat.allay.core.tools.Facts.place
import chat.allay.core.tools.Facts.short

/** Tools that search and inspect what is around the player: containers, items, dark spots, brewing, farms. */
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

  /**
   * Containers within the radius that the player could open themself ([chat.allay.spigot.Claims]), nearest first.
   * Both halves of a double chest hold the whole inventory, so the nearer half stands for it and the other is dropped.
   */
  private fun containersNear(player: Player, radius: Int): List<Container> =
    blockEntitiesNear(player.location, radius).filterIsInstance<Container>().filter { chat.allay.spigot.Claims.mayOpen(player, it) }
      .sortedBy { it.location.distanceSquared(player.location) }
      .distinctBy { ((it.inventory as? DoubleChestInventory)?.leftSide?.location ?: it.location).toVector() }

  private val scanContainers = tool("scan_nearby_containers") { context, player, args ->
    val radius = (args.int("radius") ?: 8).coerceIn(1, 12)
    val found = containersNear(player, radius)
    val rows = found.take(20).map { container ->
      val items = container.inventory.contents.asIterable()
      buildJsonObject {
        put("type", container.type.key.key)
        place(container.x, container.y, container.z, player.location)
        (container as? Nameable)?.customName?.let { put("name", it) }
        put("used_slots", items.count { it != null && !it.type.isAir })
        val counts = Items.counts(items.data(), limit = 4)
        put("top", counts["items"]!!)
        Items.inside(items.data()).takeIf { it.isNotEmpty() }?.let { totals ->
          putJsonObject("inside_containers") { totals.entries.sortedByDescending { it.value }.take(4).forEach { (id, n) -> put(id, n) } }
        }
      }
    }
    val result = buildJsonObject {
      put("radius", radius)
      put("containers", buildJsonArray { rows.forEach(::add) })
      if (found.size > rows.size) put("more_containers", found.size - rows.size)
    }
    ToolOutput(result, context.lang.text("summary.containers", "count" to found.size))
  }

  /** Places listed by find_item at most; the total counts them all. */
  private const val MAX_PLACES = 30

  private val findItem = tool("find_item") { context, player, args ->
    val query = args.string("item")?.lowercase()?.removePrefix("minecraft:")?.replace(' ', '_') ?: throw ToolError("item is required, such as diamond or log")
    val radius = (args.int("radius") ?: 8).coerceIn(1, 12)
    // An exact id finds that item; anything else finds every item whose id contains it: “log” finds oak_log and birch_log.
    val exact = Material.matchMaterial(query)?.takeIf { it.isItem && !it.isAir }?.key?.key
    val match: (String) -> Boolean = if (exact != null) { id -> id == exact } else { id -> query in id }
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
    containersNear(player, radius).forEach { container ->
      var direct = 0
      var nested = 0
      container.inventory.contents.forEach { item -> count(item).let { (d, n) -> direct += d; nested += n } }
      if (direct + nested == 0) return@forEach
      total += direct + nested
      places += buildJsonObject {
        put("where", "container")
        put("type", container.type.key.key)
        place(container.x, container.y, container.z, player.location)
        (container as? Nameable)?.customName?.let { put("name", it) }
        put("count", direct + nested)
        if (nested > 0) put("inside_boxes", nested)
      }
    }
    val result = buildJsonObject {
      put("query", query)
      if (exact == null) put("matched_ids", buildJsonArray { ids.take(20).forEach { add(JsonPrimitive(it)) } })
      put("total", total)
      put("radius", radius)
      put("places", buildJsonArray { places.take(MAX_PLACES).forEach(::add) })
      if (places.size > MAX_PLACES) put("more_places", places.size - MAX_PLACES)
    }
    ToolOutput(result, if (total == 0) context.lang.text("summary.find_none") else context.lang.text("summary.find", "total" to total, "places" to places.size))
  }

  private val spawnCheck = tool("get_light_spawn_check") { context, player, args ->
    val radius = (args.int("radius") ?: 8).coerceIn(1, 16)
    val height = minOf(radius, 6)
    val origin = player.location
    val world = player.world
    val spots = mutableListOf<IntArray>()
    for (dx in -radius..radius) for (dy in -height..height) for (dz in -radius..radius) {
      val y = origin.blockY + dy
      if (y - 1 < world.minHeight || y + 1 >= world.maxHeight) continue
      val feet = world.getBlockAt(origin.blockX + dx, y, origin.blockZ + dz)
      val below = feet.getRelative(0, -1, 0).type
      val head = feet.getRelative(0, 1, 0)
      if (!below.isSolid || !below.isOccluding) continue
      if (!feet.isPassable || feet.isLiquid || !head.isPassable || head.isLiquid) continue
      if (feet.lightFromBlocks.toInt() != 0) continue
      spots += intArrayOf(dx, dy, dz)
    }
    val nearest = spots.sortedBy { it[0] * it[0] + it[1] * it[1] + it[2] * it[2] }.take(10)
    val result = buildJsonObject {
      put("radius", radius)
      put("dark_spots", spots.size)
      put("nearest", buildJsonArray {
        nearest.forEach { add(buildJsonObject { place(origin.blockX + it[0], origin.blockY + it[1], origin.blockZ + it[2], origin) }) }
      })
      put("rule", "hostile mobs spawn at block light 0; on the surface sky light also has to be dark, so these spots matter at night and underground")
    }
    ToolOutput(result, if (spots.isEmpty()) context.lang.text("summary.dark_none") else context.lang.text("summary.dark", "count" to spots.size))
  }

  private val brewingIds = setOf(
    "nether_wart", "blaze_powder", "blaze_rod", "glass_bottle", "potion", "splash_potion", "lingering_potion", "dragon_breath",
    "redstone", "glowstone_dust", "gunpowder", "fermented_spider_eye", "spider_eye", "sugar", "rabbit_foot",
    "glistering_melon_slice", "golden_carrot", "magma_cream", "ghast_tear", "pufferfish", "phantom_membrane", "turtle_helmet",
    "breeze_rod", "cobweb", "slime_block", "stone", "brown_mushroom", "brewing_stand", "cauldron", "water_bucket",
  )

  private val brewing = tool("get_brewing_items") { context, player, _ ->
    val items = player.inventory.contents.filterNotNull().filter { it.type.key.key in brewingIds }
    val origin = player.location
    val stands = blockEntitiesNear(origin, 8).filterIsInstance<BrewingStand>()
      .sortedBy { it.location.distanceSquared(origin) }.take(4)
    val result = buildJsonObject {
      put("inventory", buildJsonArray { items.forEach { it.compact()?.let(::add) } })
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

  private val farm = tool("scan_farm") { context, player, args ->
    val radius = (args.int("radius") ?: 8).coerceIn(1, 16)
    val height = minOf(radius, 4)
    val origin = player.location
    val world = player.world
    val crops = mutableMapOf<String, MutableMap<Int, Int>>()
    val maxAge = mutableMapOf<String, Int>()
    var wet = 0
    var dry = 0
    var hoppers = 0
    val composters = mutableListOf<Int>()
    val spawners = mutableMapOf<String, Int>()
    for (dx in -radius..radius) for (dy in -height..height) for (dz in -radius..radius) {
      val y = origin.blockY + dy
      if (y < world.minHeight || y >= world.maxHeight) continue
      val block = world.getBlockAt(origin.blockX + dx, y, origin.blockZ + dz)
      when (val data = block.blockData) {
        is Farmland -> if (data.moisture >= data.maximumMoisture) wet++ else dry++
        is Ageable -> if (block.type != Material.FIRE && block.type != Material.FROSTED_ICE) {
          val id = block.type.key.key
          crops.getOrPut(id) { sortedMapOf() }.merge(data.age, 1, Int::plus)
          maxAge[id] = data.maximumAge
        }
        is Levelled -> if (block.type == Material.COMPOSTER) composters += data.level
        else -> {}
      }
      when (block.type) {
        Material.HOPPER -> hoppers++
        Material.SPAWNER -> (block.state as? CreatureSpawner)?.spawnedType?.let { spawners.merge(short(it.key.toString()), 1, Int::plus) }
        else -> {}
      }
    }
    val animals = world.getNearbyEntities(origin, radius.toDouble(), height.toDouble(), radius.toDouble())
      .filterIsInstance<Animals>().groupingBy { short(it.type.key.toString()) }.eachCount()
    val result = buildJsonObject {
      put("radius", radius)
      putJsonObject("crops") {
        crops.forEach { (id, ages) ->
          putJsonObject(id) {
            put("total", ages.values.sum())
            put("ripe", ages[maxAge[id]] ?: 0)
            put("max_age", maxAge[id] ?: 0)
            putJsonObject("by_age") { ages.forEach { (age, n) -> put(age.toString(), n) } }
          }
        }
      }
      putJsonObject("farmland") { put("hydrated", wet); put("dry", dry) }
      if (composters.isNotEmpty()) put("composter_levels", buildJsonArray { composters.forEach { add(JsonPrimitive(it)) } })
      if (hoppers > 0) put("hoppers", hoppers)
      if (spawners.isNotEmpty()) putJsonObject("spawners") { spawners.forEach { (k, v) -> put(k, v) } }
      putJsonObject("animals") { animals.forEach { (k, v) -> put(k, v) } }
    }
    ToolOutput(result, context.lang.text("summary.farm", "plants" to crops.values.sumOf { it.values.sum() }, "animals" to animals.values.sum()))
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

  val all = listOf(scanContainers, findItem, spawnCheck, brewing, farm, inspectItem)
}
