package chat.allay.fabric.tools

import chat.allay.core.*
import chat.allay.core.tools.*
import chat.allay.fabric.Claims
import chat.allay.fabric.Texts
import chat.allay.fabric.automation.Blocks
import chat.allay.fabric.mixin.InfoAccess

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Container
import net.minecraft.world.Nameable
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.animal.Animal
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.EmptyBlockGetter
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.block.Blocks as GameBlocks
import net.minecraft.world.level.block.ComposterBlock
import net.minecraft.world.level.block.FarmlandBlock
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity
import net.minecraft.world.level.block.entity.SpawnerBlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.tags.BlockTags

/** Tools that search and inspect what is around the player: containers, items, dark spots, brewing, farms. */
object InspectTools {
  private fun tool(name: String, body: (ToolContext, ServerPlayer, JsonObject) -> ToolOutput) = object : Tool {
    override val name = name
    override fun run(context: ToolContext, args: JsonObject) = body(context, context.requirePlayer(), args)
  }

  /** A container as these tools read it: the block, and everything it holds (both halves of a double chest). */
  private class Found(val block: BlockAt, val inventory: Container) {
    val contents: List<ItemStack> get() = (0 until inventory.containerSize).map(inventory::getItem)
    val name: String? get() = (block.entity as? Nameable)?.customName?.let(Texts::legacyOf)
  }

  /**
   * Containers within the radius that the player could open themself ([Claims]), nearest first. Both halves of a
   * double chest hold the whole inventory, so the nearer half stands for it and the other is dropped.
   */
  private fun containersNear(player: ServerPlayer, radius: Int): List<Found> =
    Blocks.near(player, radius) { Blocks.container(it) != null && Claims.mayOpen(player, it.level, it.pos) }
      .distinctBy { Blocks.root(it).pos }
      .mapNotNull { block -> Blocks.inventory(block)?.let { Found(block, it) } }

  private val scanContainers = tool("scan_nearby_containers") { context, player, args ->
    val radius = (args.int("radius") ?: 32).coerceIn(1, 52)
    val found = containersNear(player, radius)
    val rows = found.take(20).map { container ->
      val items = container.contents
      buildJsonObject {
        put("type", container.block.key)
        place(container.block.x, container.block.y, container.block.z, player)
        container.name?.let { put("name", it) }
        put("used_slots", items.count { !it.isEmpty })
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

  private val armorSlots = mapOf("head" to EquipmentSlot.HEAD, "chest" to EquipmentSlot.CHEST, "legs" to EquipmentSlot.LEGS, "feet" to EquipmentSlot.FEET)

  private val findItem = tool("find_item") { context, player, args ->
    val asked = args.string("item") ?: throw ToolError("item is required, such as diamond or log")
    val query = Names.normalize(asked)
    val radius = (args.int("radius") ?: 32).coerceIn(1, 52)
    // An exact id finds that item, a plural its singular; anything else finds every item whose id contains it: “log”
    // finds oak_log and birch_log, “torches” the torch.
    val exact = runCatching { Recipes.id(InteractTools.item(asked)) }.getOrNull()?.takeIf { it in Names.forms(query) }
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
        if (nested > 0) put("inside", Recipes.id(item!!.item))
      }
    }
    PlayerTools.storage(player).forEachIndexed { slot, item -> stack("inventory", slot, item) }
    stack("offhand", "offhand", player.offhandItem)
    armorSlots.forEach { (name, slot) -> stack("armor", name, player.getItemBySlot(slot)) }
    val ender = player.enderChestInventory
    (0 until ender.containerSize).forEach { slot -> stack("enderchest", slot, ender.getItem(slot)) }
    val found = mutableListOf<Pair<Found, Int>>()
    containersNear(player, radius).forEach { container ->
      var direct = 0
      var nested = 0
      container.contents.forEach { item -> count(item).let { (d, n) -> direct += d; nested += n } }
      if (direct + nested == 0) return@forEach
      total += direct + nested
      found += container to direct + nested
      places += buildJsonObject {
        put("where", "container")
        put("type", container.block.key)
        place(container.block.x, container.block.y, container.block.z, player)
        container.name?.let { put("name", it) }
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
    }
    ToolOutput(result, if (total == 0) context.lang.text("summary.find_none") else context.lang.text("summary.find", "total" to total, "places" to places.size))
  }

  /**
   * The chests find_item found glow for the asker with “diamond ×12” over them, so the model needn't call show_marks;
   * when the nearest is far or behind a wall, a way button goes under the answer. False when marks are switched off.
   */
  private fun highlight(context: ToolContext, player: ServerPlayer, found: List<Pair<Found, Int>>, query: String): Boolean {
    val settings = context.features.config().tools
    if (found.isEmpty() || !settings.enabled("show_marks")) return false
    val name = query.replace('_', ' ')
    val marks = found.take(settings.maxMarks).flatMap { (container, count) ->
      Blocks.halves(container.block).mapIndexed { index, half -> Marks.Mark(half, null, if (index == 0) "$name ×$count" else null, Marks.Hue.YELLOW) }
    }
    Marks.show(context.scheduler, player, marks, settings.marksSeconds)
    val nearest = found.first().first.block
    if (nearest.corner.distanceTo(player.position()) > 12 || !Blocks.sees(player, nearest)) {
      if (context.buttons.size < 6) context.buttons += Button.Point(context.lang.text("buttons.way_to_chest"), nearest.x, nearest.y, nearest.z, Dimensions.id(player.level()))
    }
    return true
  }

  /** Bukkit's `Material.isSolid` and `isOccluding`: by the block's type, not its state. */
  private fun solid(state: BlockState) = state.block.defaultBlockState().let { it.`is`(BlockTags.BLOCKS_MOTION) && it.isRedstoneConductor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO) }

  private val spawnCheck = tool("get_light_spawn_check") { context, player, args ->
    val radius = (args.int("radius") ?: 8).coerceIn(1, 16)
    val height = minOf(radius, 6)
    val origin = player.blockPosition()
    val level = player.level()
    val spots = mutableListOf<IntArray>()
    for (dx in -radius..radius) for (dy in -height..height) for (dz in -radius..radius) {
      val y = origin.y + dy
      if (y - 1 < level.minY || y + 1 > level.maxY) continue
      val feet = level.at(origin.x + dx, y, origin.z + dz)
      val below = feet.relative(net.minecraft.core.Direction.DOWN).state
      val head = feet.relative(net.minecraft.core.Direction.UP)
      if (!solid(below)) continue
      if (!feet.passable || feet.liquid || !head.passable || head.liquid) continue
      if (level.getBrightness(LightLayer.BLOCK, feet.pos) != 0) continue
      spots += intArrayOf(dx, dy, dz)
    }
    val nearest = spots.sortedBy { it[0] * it[0] + it[1] * it[1] + it[2] * it[2] }.take(10)
    val result = buildJsonObject {
      put("radius", radius)
      put("dark_spots", spots.size)
      put("nearest", buildJsonArray {
        nearest.forEach { add(buildJsonObject { place(origin.x + it[0], origin.y + it[1], origin.z + it[2], player) }) }
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
    val items = PlayerTools.contents(player).filter { !it.isEmpty && Recipes.id(it.item) in brewingIds }
    // A stand is a container like any other: only the ones the player could open show.
    val stands = Blocks.near(player, 8) { it.entity is BrewingStandBlockEntity && Claims.mayOpen(player, it.level, it.pos) }.take(4)
    val result = buildJsonObject {
      put("inventory", buildJsonArray { items.forEach { it.compact()?.let(::add) } })
      put("brewing_stands", buildJsonArray {
        stands.forEach { block ->
          val stand = block.entity as BrewingStandBlockEntity
          val access = stand as chat.allay.fabric.mixin.BrewingAccess
          add(buildJsonObject {
            place(block.x, block.y, block.z, player)
            put("fuel_level", access.`allay$fuel`())
            if (access.`allay$brewTime`() > 0) put("seconds_left", access.`allay$brewTime`() / 20)
            stand.getItem(3).compact()?.let { put("ingredient", it) }
            stand.getItem(4).compact()?.let { put("fuel", it) }
            put("bottles", buildJsonArray { (0..2).forEach { slot -> stand.getItem(slot).compact()?.let(::add) } })
          })
        }
      })
    }
    ToolOutput(result, context.lang.text("summary.brewing", "stacks" to items.size, "stands" to stands.size))
  }

  /** The mob a spawner makes, as Bukkit's `CreatureSpawner.getSpawnedType`; null for an empty one. */
  private fun spawned(spawner: SpawnerBlockEntity): EntityType<*>? =
    (spawner.spawner as InfoAccess.Spawner).`allay$nextSpawnData`()?.entityToSpawn()?.getString("id")?.orElse(null)?.let(Identifier::tryParse)?.let { BuiltInRegistries.ENTITY_TYPE.getOptional(it).orElse(null) }

  private val farm = tool("scan_farm") { context, player, args ->
    val radius = (args.int("radius") ?: 8).coerceIn(1, 16)
    val height = minOf(radius, 4)
    val origin = player.blockPosition()
    val level = player.level()
    val crops = mutableMapOf<String, MutableMap<Int, Int>>()
    val maxAge = mutableMapOf<String, Int>()
    var wet = 0
    var dry = 0
    var hoppers = 0
    val composters = mutableListOf<Int>()
    val spawners = mutableMapOf<String, Int>()
    for (dx in -radius..radius) for (dy in -height..height) for (dz in -radius..radius) {
      val y = origin.y + dy
      if (y < level.minY || y > level.maxY) continue
      val block = level.at(origin.x + dx, y, origin.z + dz)
      val state = block.state
      val age = LookTools.age(state)
      when {
        state.block is FarmlandBlock -> if (state.getValue(FarmlandBlock.MOISTURE) >= FarmlandBlock.MAX_MOISTURE) wet++ else dry++
        age != null -> if (!state.`is`(GameBlocks.FIRE) && !state.`is`(GameBlocks.FROSTED_ICE)) {
          val id = block.key
          crops.getOrPut(id) { sortedMapOf() }.merge(state.getValue(age), 1, Int::plus)
          maxAge[id] = age.possibleValues.max()
        }
        state.`is`(GameBlocks.COMPOSTER) -> composters += state.getValue(ComposterBlock.LEVEL)
      }
      when {
        state.`is`(GameBlocks.HOPPER) -> hoppers++
        state.`is`(GameBlocks.SPAWNER) -> (block.entity as? SpawnerBlockEntity)?.let(::spawned)?.let { spawners.merge(Worlds.key(EntityType.getKey(it)), 1, Int::plus) }
      }
    }
    val animals = level.getEntitiesOfClass(Animal::class.java, AABB(player.position(), player.position()).inflate(radius.toDouble(), height.toDouble(), radius.toDouble()))
      .groupingBy { PlayerTools.type(it) }.eachCount()
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

  private val slotted = setOf("inventory", "enderchest", "container")

  /**
   * One item the player can reach: in hand, worn, in the inventory or ender chest, or in the container under the
   * crosshair; with `inner_slot`, the item at that slot inside a shulker box or bundle found there.
   */
  private val inspectItem = tool("inspect_item") { context, player, args ->
    val from = args.string("from")?.lowercase() ?: "hand"
    val slot = args.int("slot")
    fun slotIn(size: Int): Int = slot?.takeIf { it in 0 until size } ?: throw ToolError("$from needs a slot from 0 to ${size - 1}")
    val outer = when (from) {
      "hand" -> player.mainHandItem
      "offhand" -> player.offhandItem
      in armorSlots -> player.getItemBySlot(armorSlots.getValue(from))
      "inventory" -> PlayerTools.storage(player).let { it[slotIn(it.size)] }
      "enderchest" -> player.enderChestInventory.let { it.getItem(slotIn(it.containerSize)) }
      "container" -> LookTools.targetContainer(player).second.let { it.getItem(slotIn(it.containerSize)) }
      else -> throw ToolError("from must be hand, offhand, head, chest, legs, feet, inventory, enderchest, or container")
    }
    if (outer.isEmpty) throw ToolError(if (slot != null && from in slotted) "slot $slot of $from is empty" else "nothing in $from")
    val innerSlot = args.int("inner_slot")
    val item = if (innerSlot == null) outer else {
      val inside = contents(outer) ?: throw ToolError("${Recipes.id(outer.item)} is not a shulker box or bundle")
      inside.getOrNull(innerSlot)?.takeUnless { it.isEmpty } ?: throw ToolError("slot $innerSlot inside ${Recipes.id(outer.item)} is empty; its contents list the slots")
    }
    val result = buildJsonObject {
      put("from", from)
      slot?.takeIf { from in slotted }?.let { put("slot", it) }
      innerSlot?.let { put("inside", Recipes.id(outer.item)); put("inner_slot", it) }
      detail(item, player).forEach { (key, value) -> put(key, value) }
    }
    ToolOutput(result, context.lang.text("summary.item", "id" to Recipes.id(item.item)))
  }

  val all = listOf(scanContainers, findItem, spawnCheck, brewing, farm, inspectItem)
}
