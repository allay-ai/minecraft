package chat.allay.spigot.automation

import chat.allay.core.tasks.Task
import chat.allay.core.tools.*
import chat.allay.spigot.tools.Catalogs
import chat.allay.spigot.tools.InteractTools
import chat.allay.spigot.tools.Marks
import chat.allay.spigot.tools.Tool
import chat.allay.spigot.tools.ToolContext

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.Tag
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.inventory.FurnaceRecipe
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.RecipeChoice
import org.bukkit.inventory.ShapedRecipe
import org.bukkit.inventory.ShapelessRecipe
import org.bukkit.inventory.SmithingTransformRecipe
import org.bukkit.inventory.StonecuttingRecipe
import org.bukkit.inventory.meta.Damageable
import org.bukkit.inventory.meta.PotionMeta

/**
 * `craft`: crafts from this server's recipes, with plain ingredients from the inventory, bundles, the ender chest when
 * one is near, and chests near the player may open. Parts it lacks (sticks, planks) it crafts first, four levels deep
 * with the item itself. 2×2 recipes, the crafting table itself among them, need no table; 3×3 ones need a real crafting table the
 * player can see. With a stonecutter in sight, what it cuts comes from it first. Netherite gear comes from a smithing
 * table: the diamond piece keeps its enchantments, name, and wear, also when the player wears it. Plugins' and data
 * packs' items craft by their ids from their own recipes, their items in the slots as they ask; water bottles fill from
 * glass bottles at water or a cauldron near, a level a bottle, as clicking does.
 */
object Crafting {
  /**
   * What crafting calls a stack it may use up: the game's id for a plain item, `potion water` for a plain potion of its
   * kind, and a plugin's or data pack's item by its id (`redbull:empty_can`). Null for anything of the player's own: a
   * named or enchanted game item, a shulker box, a bundle.
   */
  fun key(stack: ItemStack): String? {
    if (Sources.plain(stack)) return stack.type.key.key
    if (Tag.SHULKER_BOXES.isTagged(stack.type) || stack.type == Material.BUNDLE || stack.type.name.endsWith("_BUNDLE")) return null
    val id = Catalogs.id(stack)
    if (id != stack.type.key.key) return id
    val meta = stack.itemMeta as? PotionMeta ?: return null
    if (meta.hasCustomEffects() || meta.hasDisplayName() || meta.hasLore() || meta.basePotionType == null) return null
    return if (stack.isSimilar(ItemStack(stack.type).apply { itemMeta = (itemMeta as PotionMeta).apply { basePotionType = meta.basePotionType } })) Catalogs.bottle(stack).label else null
  }

  /** What a recipe makes, by the id tools give it: a plugin's item by its own id, a potion as `potion water`. */
  private fun made(stack: ItemStack): String = if (stack.itemMeta is PotionMeta && Catalogs.id(stack) == stack.type.key.key) Catalogs.bottle(stack).label else Catalogs.id(stack)

  /** The server's recipes as data, by result id, with a stack of each result to hand out. */
  class Book(val recipes: Map<String, List<Workshop.Recipe>>, val results: Map<String, ItemStack>)

  /**
   * The server's recipes as data: crafting ones, stonecutting ones with [stonecutter], furnace ones for a furnaces agent
   * to smelt first (never of a tool or armor, which melts to a nugget), and filling a glass bottle when [water] is near. A slot that wants an exact item (a plugin's taurine, a water bottle) takes it by its [key]; one that
   * wants something [key] can't name is left out.
   */
  fun book(server: Server, stonecutter: Boolean = false, water: Boolean = false): Book {
    val found = mutableMapOf<String, MutableList<Workshop.Recipe>>()
    val results = mutableMapOf<String, ItemStack>()
    val iterator = server.recipeIterator()
    while (iterator.hasNext()) {
      val recipe = iterator.next()
      val slots: List<RecipeChoice?> = when (recipe) {
        is ShapedRecipe -> recipe.shape.flatMap { row -> row.map { recipe.choiceMap[it] } }
        is ShapelessRecipe -> recipe.choiceList
        is StonecuttingRecipe -> if (stonecutter) listOf(recipe.inputChoice) else continue
        is FurnaceRecipe -> (recipe.inputChoice as? RecipeChoice.MaterialChoice)?.takeIf { choice -> choice.choices.all { it.maxStackSize > 1 } }?.let(::listOf) ?: continue
        else -> continue
      }
      val station = when (recipe) {
        is StonecuttingRecipe -> Workshop.Station.STONECUTTER
        is FurnaceRecipe -> Workshop.Station.FURNACE
        is ShapedRecipe -> if (recipe.shape.size <= 2 && recipe.shape.all { it.length <= 2 }) Workshop.Station.INVENTORY else Workshop.Station.TABLE
        else -> if (slots.size <= 4) Workshop.Station.INVENTORY else Workshop.Station.TABLE
      }
      val choices = slots.filterNotNull().map(::ids)
      if (choices.isEmpty() || choices.any { it.isEmpty() }) continue
      val result = recipe.result.takeUnless { it.type.isAir } ?: continue
      val id = made(result)
      found.getOrPut(id) { mutableListOf() } += Workshop.Recipe(id, result.amount, choices, station)
      results.putIfAbsent(id, result.clone().apply { amount = 1 })
    }
    if (water) {
      found.getOrPut(Workshop.WATER_BOTTLE) { mutableListOf() } += Workshop.FILL
      results.putIfAbsent(Workshop.WATER_BOTTLE, Water.bottle())
    }
    return Book(found, results)
  }

  private fun ids(choice: RecipeChoice?): Set<String> = when (choice) {
    is RecipeChoice.MaterialChoice -> choice.choices.map { it.key.key }.toSet()
    is RecipeChoice.ExactChoice -> choice.choices.mapNotNull(::key).toSet()
    else -> emptySet()
  }

  /** The smithing recipe that makes [item], like a netherite chestplate from a diamond one; null for anything else. */
  private fun smithing(server: Server, item: Material): SmithingTransformRecipe? {
    val iterator = server.recipeIterator()
    while (iterator.hasNext()) {
      val recipe = iterator.next()
      if (recipe is SmithingTransformRecipe && recipe.result.type == item) return recipe
    }
    return null
  }

  /** The nearest block [accepts] takes that the player may use and can see, or null. Tables aren't block entities, so this looks at blocks. */
  fun station(player: Player, radius: Int, sight: Boolean, accepts: (Material) -> Boolean): Block? {
    val origin = player.location.block
    var best: Block? = null
    var bestDistance = Double.MAX_VALUE
    val reach = minOf(radius, 24)
    for (dx in -reach..reach) for (dy in -reach..reach) for (dz in -reach..reach) {
      val block = origin.getRelative(dx, dy, dz)
      if (!accepts(block.type)) continue
      val distance = block.location.distanceSquared(player.location)
      if (distance < bestDistance && Blocks.unusable(player, block, radius, sight) == null) {
        best = block
        bestDistance = distance
      }
    }
    return best
  }

  private fun spot(block: Block) = buildJsonObject { put("x", block.x); put("y", block.y); put("z", block.z) }

  private fun missing(lang: chat.allay.core.Lang, missing: Workshop.Missing) = ToolOutput(JsonObject(buildJsonObject {
    put("crafted", false)
    put("note", "tell the player what's missing; nothing was taken")
  } + short(missing)), lang.text("summary.craft_missing", "count" to missing.items.size))

  /**
   * The item [name] means, as crafting calls it: a water bottle, a plugin's or data pack's item by its id
   * (`redbull:empty_can`), else the game's item, with its [Material] for smithing.
   */
  private fun target(context: ToolContext, name: String): Pair<String, Material?> {
    if (Workshop.water(name)) return Workshop.WATER_BOTTLE to null
    val custom = Catalogs.build(context).find(name.trim().lowercase(), chat.allay.core.tools.Catalog.Kind.CUSTOM)
    if (custom != null) return custom.id to null
    return InteractTools.material(name).let { it.key.key to it }
  }

  /** A plan for one item and what carrying it out needs: the stations near the player, the water, the pieces to upgrade. */
  private class Planned(
    val itemId: String, val item: Material?, val plan: Workshop.Plan, val book: Book, val sources: Sources, val water: Pair<Int, List<Block>>?,
    val table: Block?, val cutter: Block?, val smithy: Block?, val upgrade: Workshop.Recipe?, val bases: List<Pair<Int, ItemStack>>, val described: JsonObject,
  ) {
    /** What the plan takes from the player's things: everything but their own pieces to upgrade, which stay in place. */
    val takes get() = plan.takes.filterKeys { it != Workshop.base(itemId) }

    /** The player's own pieces it upgrades in place; the rest it crafts on the way. */
    val upgraded get() = bases.take(plan.takes[Workshop.base(itemId)] ?: 0)
  }

  /**
   * How [count] of [name] would be crafted for [player], read and never taken. For an agent's [task] a station or water
   * it lacks is an error and its own reservations stay usable; a look from chat ([task] null) names them in `needs`
   * instead. [spent] is what earlier plans of the same look already counted on. Returns [Planned], or
   * [Workshop.Missing] with what's short.
   */
  private fun plan(context: ToolContext, player: Player, name: String, count: Int, task: Task?, spent: Map<String, Int> = emptyMap()): Any {
    val settings = context.features.automation.settings()
    val holds = context.features.automation.reservations
    val (itemId, item) = target(context, name)
    val find = { type: Material -> station(player, settings.radius, settings.lineOfSight) { it == type } }
    val needs = mutableListOf<String>()
    val cutter = find(Material.STONECUTTER)
    val water = Water.near(player)
    val book = book(player.server, stonecutter = cutter != null, water = water != null || task == null)
    val recipes = book.recipes
    val smithing = item?.let { smithing(player.server, it) }
    if (smithing == null && recipes[itemId].isNullOrEmpty()) throw ToolError(
      if (itemId == Workshop.WATER_BOTTLE) "no water source or filled cauldron within ${Water.REACH} blocks of the player to fill glass bottles at"
      else "$itemId has no crafting${if (cutter == null) "" else " or stonecutter"} recipe on this server",
    )
    val sources = Sources.around(player, settings.radius)
    val have = sources.list.flatMap { it.slots() }.filterNotNull().mapNotNull(::key).distinct()
      .associateWith { id -> sources.free({ key(it) == id }, id, holds, task?.id ?: "") - (spent[id] ?: 0) }
      .filterValues { it > 0 }.toMutableMap()

    // Smithing: the template and the ingot come through the planner, which crafts an ingot from scrap and gold. The
    // diamond piece is the player's own, enchanted or worn, or a plain one crafted on the way from diamonds.
    val smithy = smithing?.let {
      find(Material.SMITHING_TABLE) ?: if (task == null) null.also { needs += "smithing_table" }
        else throw ToolError("$itemId is made on a smithing table, and there's none near the player in plain sight")
    }
    val upgrade = smithing?.let { Workshop.smithing(itemId, ids(it.template), ids(it.addition), ids(it.base)) }
    val bases = smithing?.let { recipe ->
      val kinds = ids(recipe.base)
      // The richest pieces first: most enchantment levels, then the least worn. Slots 36–39 are what they wear; a plain
      // piece in the inventory is an ingredient like any other.
      (0..40).mapNotNull { slot -> player.inventory.getItem(slot)?.takeIf { it.type.key.key in kinds && (slot >= 36 || key(it) == null) }?.let { slot to it } }
        .sortedWith(compareByDescending<Pair<Int, ItemStack>> { it.second.enchantments.values.sum() }.thenBy { (it.second.itemMeta as? Damageable)?.damage ?: 0 })
    }.orEmpty()
    if (upgrade != null) have[Workshop.base(itemId)] = bases.size - (spent[Workshop.base(itemId)] ?: 0)

    val target = upgrade?.first()?.result ?: itemId
    val plan = when (val planned = Workshop.plan(target, count, have, { id -> upgrade?.filter { it.result == id }?.takeIf { it.isNotEmpty() } ?: recipes[id].orEmpty() })) {
      // A piece to upgrade that can't be crafted at all reads as the piece.
      is Workshop.Missing -> return Workshop.Missing(
        planned.items.mapKeys { (id, _) -> if (id == Workshop.base(itemId)) ids(smithing!!.base).first() else id },
        planned.parts.mapValues { (_, part) -> if (part == Workshop.base(itemId)) itemId else part }.filterValues { it != itemId },
      )
      is Workshop.Plan -> planned
      else -> throw ToolError("couldn't plan the craft")
    }
    val table = if (!plan.needsTable) null else find(Material.CRAFTING_TABLE) ?: if (task == null) null.also { needs += "crafting_table" }
      else throw ToolError("no crafting table near the player in plain sight; 3×3 recipes need a real one")
    // A cauldron gives a bottle a level; water itself never runs dry.
    if (plan.fills > 0 && (water?.first ?: 0) < plan.fills) {
      if (task != null) throw ToolError("${plan.fills} glass bottles to fill, but the cauldrons near hold only ${water!!.first} levels of water, a bottle each")
      needs += "water"
    }
    if (plan.smelts.isNotEmpty() && listOf(Material.FURNACE, Material.BLAST_FURNACE).all { find(it) == null }) needs += "furnace"
    val takes = plan.takes.filterKeys { it != Workshop.base(itemId) }
    val upgraded = bases.take(plan.takes[Workshop.base(itemId)] ?: 0)
    val extra = plan.makes.filterKeys { it != target }
    val described = buildJsonObject {
      putJsonObject("takes") { takes.forEach { (id, n) -> put(id, n) } }
      putJsonObject("makes") { extra.forEach { (id, n) -> put(id, n) }; if (upgrade != null) put(itemId, count) }
      put("steps", buildJsonArray {
        plan.steps.forEach { step ->
          when {
            step.recipe.result == Workshop.base(itemId) -> {}
            step.recipe.result == target && upgrade != null -> add("${step.times} × $itemId on the smithing table")
            step.recipe.station == Workshop.Station.STONECUTTER -> add("${step.times} × ${step.recipe.result} on the stonecutter")
            step.recipe.station == Workshop.Station.FURNACE -> add("${step.times} × ${step.recipe.result} smelted from ${step.used.keys.joinToString(" and ")}")
            step.recipe.station == Workshop.Station.WATER -> add("${step.times} × glass_bottle filled with water${if (water?.first == Int.MAX_VALUE) "" else " at a cauldron, a level each"}")
            else -> add("${step.times} × ${step.recipe.result}")
          }
        }
      })
      if (plan.smelts.isNotEmpty()) putJsonObject("smelt") { plan.smelts.forEach { (id, n) -> put(id, n) } }
      if (table != null) put("table", spot(table))
      if (cutter != null && Workshop.Station.STONECUTTER in plan.stations) put("stonecutter", spot(cutter))
      if (smithy != null) put("smithing_table", spot(smithy))
      if (upgrade != null) put("upgrades", buildJsonArray {
        upgraded.forEach { (slot, base) -> add(buildJsonObject { put("item", base.type.key.key); put("where", where(slot)); put("enchantments", base.enchantments.size) }) }
      })
      if (needs.isNotEmpty()) put("needs", buildJsonArray { needs.forEach { add(it) } })
    }
    return Planned(itemId, item, plan, book, sources, water, table, cutter, smithy, upgrade?.first(), bases, described)
  }

  /** What's short, and what each part on the way was for: `{"diamond": 8}` for a `diamond_chestplate`. */
  private fun short(missing: Workshop.Missing) = buildJsonObject {
    putJsonObject("missing") { missing.items.forEach { (id, n) -> put(id, n) } }
    if (missing.parts.isNotEmpty()) putJsonObject("missing_for") { missing.parts.forEach { (id, part) -> put(id, part) } }
  }

  /**
   * `get_recipes` with `plan`: what crafting [count] of [name] would take from the player's things around, as `craft`
   * plans it, the parts on the way included; nothing is taken. What's short comes as `missing`, stations as `needs`.
   * [spent] carries what the items before it in the same call took, so a set of armor shares the ingots.
   */
  fun look(context: ToolContext, name: String, count: Int, spent: MutableMap<String, Int>): JsonObject =
    when (val planned = plan(context, context.requirePlayer(), name, count, null, spent)) {
      is Planned -> planned.described.also { planned.plan.takes.forEach { (id, n) -> spent.merge(id, n, Int::plus) } }
      is Workshop.Missing -> short(planned)
      else -> throw ToolError("couldn't plan the craft")
    }

  /** Stacks of what crafting made, by id, each as big as the game allows. */
  private fun stacks(book: Book, items: Map<String, Int>): List<ItemStack> = items.flatMap { (id, n) ->
    val stack = book.results[id] ?: Material.matchMaterial(id)?.let(::ItemStack) ?: return@flatMap emptyList()
    val max = stack.maxStackSize
    (0 until (n + max - 1) / max).map { stack.clone().apply { amount = minOf(max, n - it * max) } }
  }

  /** Buckets and bottles that crafting hands back, like the game does: a cake leaves three empty buckets. */
  private fun remains(takes: Map<String, Int>) = takes.mapNotNull { (id, n) -> Material.matchMaterial(id)?.craftingRemainingItem?.let { ItemStack(it, n) } }

  val tool = object : Tool {
    override val name = "craft"

    override fun run(context: ToolContext, args: JsonObject): ToolOutput {
      val player = context.requirePlayer()
      val task = context.requireTask()
      val automation = context.features.automation
      val holds = automation.reservations
      automation.require(player, task, chat.allay.core.tasks.Kind.CRAFTING)
      val count = (args.int("count") ?: 1).coerceIn(1, 64)
      val lang = context.lang
      val planned = when (val planned = plan(context, player, args.string("item") ?: throw ToolError("item is required"), count, task)) {
        is Workshop.Missing -> return missing(lang, planned)
        else -> planned as Planned
      }
      val itemId = planned.itemId
      val plan = planned.plan
      if (args.bool("dry_run") == true) return ToolOutput(JsonObject(planned.described + ("crafted" to JsonPrimitive(false))), lang.text("summary.craft_plan"))

      // Smelting comes first, from a furnaces agent: craft only what the furnaces wait for (raw iron from its block).
      if (plan.smelts.isNotEmpty()) {
        val first = plan.beforeSmelting()
        if (first.steps.isNotEmpty()) {
          takeAll(player, task, planned.sources, holds, first.takes)
          give(player, stacks(planned.book, first.makes) + remains(first.takes))
        }
        return ToolOutput(buildJsonObject {
          put("crafted", false)
          putJsonObject("smelt") { plan.smelts.forEach { (id, n) -> put(id, n) } }
          if (first.steps.isNotEmpty()) put("crafted_for_the_furnace", buildJsonArray { first.steps.forEach { add("${it.times} × ${it.recipe.result}") } })
          put("note", "smelt these first: start_task kind furnaces with them, wait for its report, then call craft again")
        }, lang.text("summary.craft_plan"))
      }

      takeAll(player, task, planned.sources, holds, planned.takes)
      if (plan.fills > 0) Water.drain(planned.water!!, plan.fills)
      val extra = plan.makes.filterKeys { it != planned.upgrade?.result }
      // The player's own pieces stay where they were, on the player too, with their enchantments, name, and wear; the
      // ones crafted on the way come out new.
      val upgraded = planned.upgraded
      @Suppress("DEPRECATION")
      upgraded.forEach { (slot, base) -> player.inventory.setItem(slot, base.clone().apply { type = planned.item!! }) }
      val fresh = if (planned.upgrade != null) List(count - upgraded.size) { ItemStack(planned.item!!) } else emptyList()
      give(player, stacks(planned.book, extra) + fresh + remains(planned.takes))
      task.report("crafted $count × $itemId into the player's inventory (at their feet when it was full)")
      val label = "$count × ${itemId.substringAfter(':').replace('_', ' ')}"
      val used = listOfNotNull(planned.table, planned.cutter?.takeIf { Workshop.Station.STONECUTTER in plan.stations }, planned.smithy)
      if (used.isNotEmpty()) Marks.show(context.plugin, player, used.map { Marks.Mark(it, null, label, Marks.Hue.GREEN) }, 10)
      return ToolOutput(JsonObject(planned.described + ("crafted" to JsonPrimitive(true))), lang.text("summary.crafted", "count" to count, "item" to itemId))
    }
  }

  /** Where a slot of the player's inventory is, as they'd look for it. */
  private fun where(slot: Int) = when (slot) {
    in 0..8 -> "hotbar ${slot + 1}"
    in 9..35 -> "inventory row ${(slot - 9) / 9 + 1} column ${(slot - 9) % 9 + 1}"
    36 -> "worn: boots"
    37 -> "worn: leggings"
    38 -> "worn: chestplate"
    39 -> "worn: helmet"
    else -> "offhand"
  }

  /** Takes everything first; if anything falls short, it all goes back and nothing is crafted. */
  private fun takeAll(player: Player, task: Task, sources: Sources, holds: chat.allay.core.tasks.Reservations, takes: Map<String, Int>) {
    val taken = mutableListOf<ItemStack>()
    for ((id, n) in takes) {
      val match = { stack: ItemStack -> key(stack) == id }
      if (!sources.reserve(match, id, n, holds, task.id)) {
        give(player, taken)
        holds.release(task.id)
        throw ToolError("someone took the $id meanwhile")
      }
      val (got, short) = sources.takeReserved(match, id, n, holds, task.id)
      taken += got
      if (short != null) {
        give(player, taken)
        holds.release(task.id)
        throw ToolError("the $id changed meanwhile")
      }
    }
  }
}
