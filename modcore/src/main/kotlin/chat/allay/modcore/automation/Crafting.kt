package chat.allay.modcore.automation

import chat.allay.core.tasks.Task
import chat.allay.core.tools.*
import chat.allay.modcore.tools.BlockAt
import chat.allay.modcore.tools.Catalogs
import chat.allay.modcore.tools.FireworkItems
import chat.allay.modcore.tools.InteractTools
import chat.allay.modcore.tools.Marks
import chat.allay.modcore.tools.Recipes
import chat.allay.modcore.tools.Tool
import chat.allay.modcore.tools.ToolContext
import chat.allay.modcore.tools.Worlds
import chat.allay.modcore.tools.at
import chat.allay.modcore.tools.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.minecraft.core.component.DataComponents
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.Item
import net.minecraft.tags.ItemTags
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.alchemy.PotionContents
import net.minecraft.world.item.crafting.AbstractCookingRecipe
import net.minecraft.world.item.crafting.Ingredient
import net.minecraft.world.item.crafting.RecipeType
import net.minecraft.world.item.crafting.ShapedRecipe
import net.minecraft.world.item.crafting.ShapelessRecipe
import net.minecraft.world.item.crafting.SmithingTransformRecipe
import net.minecraft.world.item.crafting.StonecutterRecipe
import net.minecraft.world.level.block.Blocks as GameBlocks
import net.minecraft.world.level.block.state.BlockState

/**
 * `craft`: crafts from this server's recipes, with plain ingredients from the inventory, bundles, the ender chest when
 * one is near, and chests near the player may open. Parts it lacks (sticks, planks) it crafts first, four levels deep
 * with the item itself. 2×2 recipes, the crafting table itself among them, need no table; 3×3 ones need a real crafting table the
 * player can see. With a stonecutter in sight, what it cuts comes from it first. Netherite gear comes from a smithing
 * table: the diamond piece keeps its enchantments, name, and wear, also when the player wears it. Mods' and data
 * packs' items craft by their ids from their own recipes, their items in the slots as they ask; water bottles fill from
 * glass bottles at water or a cauldron near, a level a bottle, as clicking does.
 */
object Crafting {
  /** The most one craft call makes: a full inventory of stacks of 64. */
  const val MAX_COUNT = 2304

  /** What Bukkit's `PotionMeta` covers. */
  private val POTIONS = setOf(Items.POTION, Items.SPLASH_POTION, Items.LINGERING_POTION, Items.TIPPED_ARROW)

  /** A plain bottle of its potion, as `potion water`; null for anything else. */
  private fun bottle(stack: ItemStack): String? {
    if (stack.item !in POTIONS) return null
    val potion = stack.get(DataComponents.POTION_CONTENTS)?.potion()?.orElse(null) ?: return null
    val key = potion.unwrapKey().orElse(null) ?: return null
    if (!Slots.similar(stack, PotionContents.createItemStack(stack.item, potion))) return null
    return Brewing.Bottle(Slots.id(stack), Worlds.key(key.identifier())).label
  }

  /**
   * What crafting calls a stack it may use up: the item's id for a plain one (a mod's items too), `potion water` for a
   * plain potion of its kind, and a data pack's item built on the game's own by its model's id. Null for anything of
   * the player's own: a named or enchanted item, a shulker box, a bundle.
   */
  fun key(stack: ItemStack): String? {
    if (stack.isEmpty) return null
    if (Sources.plain(stack)) return Slots.id(stack)
    if (stack.`is`(ItemTags.SHULKER_BOXES) || stack.`is`(ItemTags.BUNDLES)) return null
    val id = Catalogs.id(stack)
    if (id != Slots.id(stack)) return id
    return bottle(stack)
  }

  /** What a recipe makes, by the id tools give it: a data pack's item by its own id, a potion as `potion water`. */
  private fun made(stack: ItemStack): String = Catalogs.id(stack).takeIf { it != Slots.id(stack) } ?: bottle(stack) ?: Slots.id(stack)

  /** The server's recipes as data, by result id, with a stack of each result to hand out. */
  class Book(val recipes: Map<String, List<Workshop.Recipe>>, val results: Map<String, ItemStack>)

  /**
   * The server's recipes as data: crafting ones, stonecutting ones with [stonecutter], furnace ones for a furnaces agent
   * to smelt first (never of a tool or armor, which melts to a nugget), and filling a glass bottle when [water] is near. A slot that wants an exact item (the loader's components ingredient: a water bottle) takes what
   * of [candidates] it accepts, by its [key]; one that accepts none of them is left out.
   */
  fun book(server: MinecraftServer, candidates: List<ItemStack>, stonecutter: Boolean = false, water: Boolean = false): Book {
    val found = mutableMapOf<String, MutableList<Workshop.Recipe>>()
    val results = mutableMapOf<String, ItemStack>()
    val holders = Recipes.all(server)
    // What an exact slot may be: what the player has, a water bottle, and whatever a recipe makes.
    val known = (candidates + Water.bottle() + holders.mapNotNull { Recipes.result(it.value())?.takeUnless(ItemStack::isEmpty) })
      .mapNotNull { stack -> key(stack)?.let { it to stack } }.distinctBy { it.first }
    for (holder in holders) {
      val recipe = holder.value()
      val slots: List<Ingredient?> = when (recipe) {
        is ShapedRecipe -> Recipes.grid(recipe)
        is ShapelessRecipe -> Recipes.ingredients(recipe)
        is StonecutterRecipe -> if (stonecutter) listOf(recipe.input()) else continue
        is AbstractCookingRecipe -> if (recipe.type == RecipeType.SMELTING && recipe.input().items().allMatch { it.value().defaultMaxStackSize > 1 }) listOf(recipe.input()) else continue
        else -> continue
      }
      val station = when (recipe) {
        is StonecutterRecipe -> Workshop.Station.STONECUTTER
        is AbstractCookingRecipe -> Workshop.Station.FURNACE
        is ShapedRecipe -> if (recipe.height <= 2 && recipe.width <= 2) Workshop.Station.INVENTORY else Workshop.Station.TABLE
        else -> if (slots.size <= 4) Workshop.Station.INVENTORY else Workshop.Station.TABLE
      }
      val choices = slots.filterNotNull().map { ids(it, known) }
      if (choices.isEmpty() || choices.any { it.isEmpty() }) continue
      val result = Recipes.result(recipe)?.takeUnless { it.isEmpty } ?: continue
      val id = made(result)
      found.getOrPut(id) { mutableListOf() } += Workshop.Recipe(id, result.count, choices, station)
      results.putIfAbsent(id, result.copyWithCount(1))
    }
    if (water) {
      found.getOrPut(Workshop.WATER_BOTTLE) { mutableListOf() } += Workshop.FILL
      results.putIfAbsent(Workshop.WATER_BOTTLE, Water.bottle())
    }
    return Book(found, results)
  }

  /** The items an ingredient takes; for the loader's own kind, those of [known] it accepts. */
  private fun ids(choice: Ingredient?, known: List<Pair<String, ItemStack>> = emptyList()): Set<String> {
    if (choice == null) return emptySet()
    if (chat.allay.modcore.Mod.loader.custom(choice)) return known.filter { choice.test(it.second) }.map { it.first }.toSet()
    return choice.items().map { Recipes.id(it.value()) }.toList().toSet()
  }

  /** The smithing recipe that makes [item], like a netherite chestplate from a diamond one; null for anything else. */
  private fun smithing(server: MinecraftServer, item: Item): SmithingTransformRecipe? =
    Recipes.all(server).map { it.value() }.filterIsInstance<SmithingTransformRecipe>().firstOrNull { Recipes.result(it)?.item == item }

  /** The blocks [Stations] looks for: where automation works, and the furnaces a plan asks about. */
  private val STATIONS = setOf(
    GameBlocks.CRAFTING_TABLE, GameBlocks.STONECUTTER, GameBlocks.SMITHING_TABLE, GameBlocks.GRINDSTONE,
    GameBlocks.ANVIL, GameBlocks.CHIPPED_ANVIL, GameBlocks.DAMAGED_ANVIL, GameBlocks.FURNACE, GameBlocks.BLAST_FURNACE,
  )

  /**
   * The stations around [player], read once for a whole call: tables aren't block entities, so this reads the blocks
   * themselves, within [radius] (24 at most) and only in loaded chunks, skipping sections whose palette holds none.
   * [find] then gives the nearest one the player may use and can see.
   */
  class Stations(private val player: ServerPlayer, private val radius: Int, private val sight: Boolean) {
    private val blocks: List<BlockAt> = run {
      val origin = player.blockPosition()
      val level = player.level()
      val reach = minOf(radius, 24)
      val low = maxOf(origin.y - reach, level.minY)
      val high = minOf(origin.y + reach, level.maxY)
      val found = mutableListOf<BlockAt>()
      for (cx in ((origin.x - reach) shr 4)..((origin.x + reach) shr 4)) for (cz in ((origin.z - reach) shr 4)..((origin.z + reach) shr 4)) {
        val chunk = level.chunkSource.getChunkNow(cx, cz) ?: continue
        for (sy in (low shr 4)..(high shr 4)) {
          val section = chunk.getSection(chunk.getSectionIndexFromSectionY(sy))
          if (section.hasOnlyAir() || !section.maybeHas { it.block in STATIONS }) continue
          for (y in maxOf(low, sy shl 4)..minOf(high, (sy shl 4) + 15)) {
            for (x in maxOf(origin.x - reach, cx shl 4)..minOf(origin.x + reach, (cx shl 4) + 15)) for (z in maxOf(origin.z - reach, cz shl 4)..minOf(origin.z + reach, (cz shl 4) + 15)) {
              if (section.getBlockState(x and 15, y and 15, z and 15).block in STATIONS) found += level.at(x, y, z)
            }
          }
        }
      }
      found.sortedBy { it.corner.distanceToSqr(player.position()) }
    }

    /** The nearest block [accepts] takes that the player may use and can see, or null. */
    fun find(accepts: (BlockState) -> Boolean): BlockAt? = blocks.firstOrNull { accepts(it.state) && Blocks.unusable(player, it, radius, sight) == null }
  }

  /** The nearest block [accepts] takes that the player may use and can see, or null; one look, for a call that needs one station. */
  fun station(player: ServerPlayer, radius: Int, sight: Boolean, accepts: (BlockState) -> Boolean): BlockAt? = Stations(player, radius, sight).find(accepts)

  private fun spot(block: BlockAt) = buildJsonObject {
    put("x", block.x)
    put("y", block.y)
    put("z", block.z)
  }

  /**
   * What's missing, and what of it waits in the player's ender chest, bundles there included, when no ender chest
   * stands near enough for crafting to take from it: the model then asks them to stand by one instead of saying “none”.
   */
  private fun missing(context: ToolContext, player: ServerPlayer, missing: Workshop.Missing): ToolOutput {
    val radius = context.features.automation.settings().radius
    val stored = if (Sources.enderNear(player, radius)) emptyList() else Slots.read(player.enderChestInventory).data()
    val ender = missing.items.keys.associateWith { id -> stored.sumOf { chat.allay.core.tools.Items.matching(it) { found -> found == id }.let { (direct, nested) -> direct + nested } } }
      .filterValues { it > 0 }
    return ToolOutput(JsonObject(buildJsonObject {
      put("crafted", false)
      put("note", "tell the player what's missing; nothing was taken")
      if (ender.isNotEmpty()) {
        putJsonObject("in_ender_chest") { ender.forEach { (id, n) -> put(id, n) } }
        put("ender_chest", "no ender chest within $radius blocks, and crafting takes from the player's only beside one: ask them to stand by an ender chest, then craft again")
      }
    } + short(missing)), context.lang.text("summary.craft_missing", "count" to missing.items.size), refused = true)
  }

  /** Enchantment levels on an item, as Bukkit's `getEnchantments` sums them. */
  private fun levels(item: ItemStack) = item.get(DataComponents.ENCHANTMENTS)?.entrySet()?.sumOf { it.intValue } ?: 0

  /**
   * The item [name] means, as crafting calls it: a water bottle, a data pack's item by its id, else the game's or a
   * mod's item, with its [Item] for smithing.
   */
  private fun target(context: ToolContext, name: String): Pair<String, Item?> {
    if (Workshop.water(name)) return Workshop.WATER_BOTTLE to null
    val custom = Catalogs.build(context).find(name.trim().lowercase(), Catalog.Kind.CUSTOM)
    if (custom != null) return custom.id to null
    return InteractTools.item(name).let { Recipes.id(it) to it }
  }

  /** A plan for one item and what carrying it out needs: the stations near the player, the water, the pieces to upgrade. */
  private class Planned(
    val itemId: String, val item: Item?, val plan: Workshop.Plan, val book: Book, val sources: Sources, val water: Pair<Int, List<BlockAt>>?,
    val table: BlockAt?, val cutter: BlockAt?, val smithy: BlockAt?, val upgrade: Workshop.Recipe?, val bases: List<Pair<Int, ItemStack>>, val described: JsonObject,
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
  private fun plan(context: ToolContext, player: ServerPlayer, name: String, count: Int, task: Task?, spent: Map<String, Int> = emptyMap()): Any {
    val settings = context.features.automation.settings()
    val holds = context.features.automation.reservations
    val server = player.level().server
    val (itemId, item) = target(context, name)
    val stations = Stations(player, settings.radius, settings.lineOfSight)
    val find = { type: net.minecraft.world.level.block.Block -> stations.find { it.`is`(type) } }
    val needs = mutableListOf<String>()
    val cutter = find(GameBlocks.STONECUTTER)
    val water = Water.near(player)
    val sources = Sources.around(player, settings.radius)
    val stacks = sources.list.flatMap { it.slots() }.filterNotNull()
    val book = book(server, stacks, stonecutter = cutter != null, water = water != null || task == null)
    val recipes = book.recipes
    val smithing = item?.let { smithing(server, it) }
    if (smithing == null && recipes[itemId].isNullOrEmpty()) throw ToolError(
      if (itemId == Workshop.WATER_BOTTLE) "no water source or filled cauldron within ${Water.REACH} blocks of the player to fill glass bottles at"
      else "$itemId has no crafting${if (cutter == null) "" else " or stonecutter"} recipe on this server",
    )
    val have = stacks.mapNotNull(::key).distinct()
      .associateWith { id -> sources.free({ key(it) == id }, id, holds, task?.id ?: "") - (spent[id] ?: 0) }
      .filterValues { it > 0 }.toMutableMap()

    // Smithing: the template and the ingot come through the planner, which crafts an ingot from scrap and gold. The
    // diamond piece is the player's own, enchanted or worn, or a plain one crafted on the way from diamonds.
    val smithy = smithing?.let {
      find(GameBlocks.SMITHING_TABLE) ?: if (task == null) null.also { needs += "smithing_table" }
        else throw ToolError("$itemId is made on a smithing table, and there's none near the player in plain sight")
    }
    val kinds = smithing?.let { ids(it.baseIngredient()) }
    val upgrade = smithing?.let { Workshop.smithing(itemId, ids(it.templateIngredient().orElse(null)), ids(it.additionIngredient().orElse(null)), kinds!!) }
    val bases = kinds?.let {
      // The richest pieces first: most enchantment levels, then the least worn. Slots 36–39 are what they wear; a plain
      // piece in the inventory is an ingredient like any other.
      (0..40).mapNotNull { slot -> player.inventory.getItem(slot).takeIf { !it.isEmpty && Slots.id(it) in kinds && (slot >= 36 || key(it) == null) }?.let { slot to it } }
        .sortedWith(compareByDescending<Pair<Int, ItemStack>> { levels(it.second) }.thenBy { it.second.get(DataComponents.DAMAGE) ?: 0 })
    }.orEmpty()
    if (upgrade != null) have[Workshop.base(itemId)] = bases.size - (spent[Workshop.base(itemId)] ?: 0)

    val target = upgrade?.first()?.result ?: itemId
    val plan = when (val planned = Workshop.plan(target, count, have, { id -> upgrade?.filter { it.result == id }?.takeIf { it.isNotEmpty() } ?: recipes[id].orEmpty() })) {
      // A piece to upgrade that can't be crafted at all reads as the piece.
      is Workshop.Missing -> return Workshop.Missing(
        planned.items.mapKeys { (id, _) -> if (id == Workshop.base(itemId)) kinds!!.first() else id },
        planned.parts.mapValues { (_, part) -> if (part == Workshop.base(itemId)) itemId else part }.filterValues { it != itemId },
      )
      is Workshop.Plan -> planned
      else -> throw ToolError("couldn't plan the craft")
    }
    val table = if (!plan.needsTable) null else find(GameBlocks.CRAFTING_TABLE) ?: if (task == null) null.also { needs += "crafting_table" }
      else throw ToolError("no crafting table near the player in plain sight; 3×3 recipes need a real one")
    // A cauldron gives a bottle a level; water itself never runs dry.
    if (plan.fills > 0 && (water?.first ?: 0) < plan.fills) {
      if (task != null) throw ToolError("${plan.fills} glass bottles to fill, but the cauldrons near hold only ${water!!.first} levels of water, a bottle each")
      needs += "water"
    }
    if (plan.smelts.isNotEmpty() && listOf(GameBlocks.FURNACE, GameBlocks.BLAST_FURNACE).all { find(it) == null }) needs += "furnace"
    val takes = plan.takes.filterKeys { it != Workshop.base(itemId) }
    val upgraded = bases.take(plan.takes[Workshop.base(itemId)] ?: 0)
    val extra = plan.makes.filterKeys { it != target }
    val described = buildJsonObject {
      putJsonObject("takes") { takes.forEach { (id, n) -> put(id, n) } }
      putJsonObject("makes") {
        extra.forEach { (id, n) -> put(id, n) }
        if (upgrade != null) put(itemId, count)
      }
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
        upgraded.forEach { (slot, base) ->
          add(buildJsonObject {
            put("item", Slots.id(base))
            put("where", where(slot))
            put("enchantments", base.get(DataComponents.ENCHANTMENTS)?.size() ?: 0)
          })
        }
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
    val stack = book.results[id] ?: InteractTools.known(id)?.let(::ItemStack) ?: return@flatMap emptyList()
    val max = stack.maxStackSize
    (0 until (n + max - 1) / max).map { stack.copyWithCount(minOf(max, n - it * max)) }
  }

  /** Buckets and bottles that crafting hands back, like the game does: a cake leaves three empty buckets. */
  private fun remains(takes: Map<String, Int>) =
    takes.mapNotNull { (id, n) -> InteractTools.known(id)?.craftingRemainder?.create()?.takeUnless { it.isEmpty }?.copyWithCount(n) }.flatMap(::split)

  val tool = object : Tool {
    override val name = "craft"

    override fun run(context: ToolContext, args: JsonObject): ToolOutput {
      val player = context.requirePlayer()
      val task = context.requireTask()
      val automation = context.features.automation
      val holds = automation.reservations
      automation.require(player, task, chat.allay.core.tasks.Kind.CRAFTING)
      // Crafting is instant, so a whole order goes in one call: 36 stacks, a full inventory.
      val count = (args.int("count") ?: 1).coerceIn(1, MAX_COUNT)
      val lang = context.lang
      // Fireworks follow the game's special recipes: what goes in is the player's description of the show.
      Names.normalize(args.string("item").orEmpty()).takeIf { it in SpecialRecipes.FIREWORK_ITEMS }?.let { return fireworks(context, player, task, args, it, count) }
      val planned = when (val planned = plan(context, player, args.string("item") ?: throw ToolError("item is required"), count, task)) {
        is Workshop.Missing -> return missing(context, player, planned)
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
      upgraded.forEach { (slot, base) -> player.inventory.setItem(slot, base.transmuteCopy(planned.item!!, base.count)) }
      val fresh = if (planned.upgrade != null) List(count - upgraded.size) { ItemStack(planned.item!!) } else emptyList()
      give(player, stacks(planned.book, extra) + fresh + remains(planned.takes))
      // A copy recipe makes more than asked (one template, two out): say what really came.
      val made = plan.makes[itemId] ?: count
      task.report("crafted $made × $itemId into the player's inventory (at their feet when it was full)")
      val label = "$count × ${itemId.substringAfter(':').replace('_', ' ')}"
      val used = listOfNotNull(planned.table, planned.cutter?.takeIf { Workshop.Station.STONECUTTER in plan.stations }, planned.smithy)
      if (used.isNotEmpty()) Marks.show(context.scheduler, player, used.map { Marks.Mark(it, null, label, Marks.Hue.GREEN) }, 10)
      return ToolOutput(JsonObject(planned.described + ("crafted" to JsonPrimitive(true))), lang.text("summary.crafted", "count" to count, "item" to itemId))
    }
  }

  /** A stack as stacks the game allows: three buckets become three single buckets. */
  private fun split(item: ItemStack): List<ItemStack> {
    val max = item.maxStackSize
    if (item.count <= max) return listOf(item)
    return (0 until (item.count + max - 1) / max).map { item.copyWithCount(minOf(max, item.count - it * max)) }
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
  /**
   * Fireworks to the player's description ([Fireworks]), crafted from their things as the game's firework recipes
   * would: the stars first, then the rockets, three a craft; a crafting table near when a grid holds more than 4.
   */
  private fun fireworks(context: ToolContext, player: ServerPlayer, task: Task, args: JsonObject, id: String, count: Int): ToolOutput {
    val star = id == "firework_star"
    val spec = Fireworks.parse(args["firework"] as? JsonObject ?: if (star) null else JsonObject(emptyMap()), star)
    val needs = Fireworks.needs(spec, count, star)
    val settings = context.features.automation.settings()
    val holds = context.features.automation.reservations
    val table = if (needs.table) station(player, settings.radius, settings.lineOfSight) { it.`is`(GameBlocks.CRAFTING_TABLE) } else null
    if (needs.table && table == null) throw ToolError("these fireworks need a crafting table near the player: a grid holds more than 4 items")
    val sources = Sources.around(player, settings.radius)
    fun free(item: String) = sources.free({ key(it) == item }, item, holds, task.id)
    val takes = mutableMapOf<String, Int>()
    val missing = mutableMapOf<String, Int>()
    needs.items.forEach { (choices, n) ->
      // Any mob head makes a creeper face: the kind the player has most of.
      val item = choices.maxBy(::free)
      val short = n - free(item)
      if (short > 0) missing[if (choices.size > 1) "any_mob_head" else item] = short else takes[item] = n
    }
    val lang = context.lang
    if (missing.isNotEmpty()) return ToolOutput(buildJsonObject {
      put("crafted", false)
      put("note", "tell the player what's missing; nothing was taken")
      putJsonObject("missing") { missing.forEach { (item, n) -> put(item, n) } }
    }, lang.text("summary.craft_missing", "count" to missing.size), refused = true)
    val described = buildJsonObject {
      put("item", id)
      put("makes", needs.makes)
      putJsonObject("takes") { takes.forEach { (item, n) -> put(item, n) } }
      table?.let { put("crafting_table", spot(it)) }
    }
    if (args.bool("dry_run") == true) return ToolOutput(JsonObject(described + ("crafted" to JsonPrimitive(false))), lang.text("summary.craft_plan"))
    takeAll(player, task, sources, holds, takes)
    val made = FireworkItems.of(id, spec, 1)
    give(player, generateSequence(needs.makes) { (it - 64).takeIf { left -> left > 0 } }.map { made.copyWithCount(minOf(it, 64)) }.toList())
    task.report("crafted ${needs.makes} × $id into the player's inventory (at their feet when it was full)")
    table?.let { Marks.show(context.scheduler, player, listOf(Marks.Mark(it, null, "${needs.makes} × ${id.replace('_', ' ')}", Marks.Hue.GREEN)), 10) }
    return ToolOutput(JsonObject(described + ("crafted" to JsonPrimitive(true))), lang.text("summary.crafted", "count" to needs.makes, "item" to id))
  }

  private fun takeAll(player: ServerPlayer, task: Task, sources: Sources, holds: chat.allay.core.tasks.Reservations, takes: Map<String, Int>) {
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
