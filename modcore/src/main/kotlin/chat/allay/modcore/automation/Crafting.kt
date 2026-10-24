package chat.allay.modcore.automation

import chat.allay.core.tasks.Task
import chat.allay.core.tools.*
import chat.allay.modcore.tools.BlockAt
import chat.allay.modcore.tools.Catalogs
import chat.allay.modcore.tools.InteractTools
import chat.allay.modcore.tools.Marks
import chat.allay.modcore.tools.Recipes
import chat.allay.modcore.tools.Tool
import chat.allay.modcore.tools.ToolContext
import chat.allay.modcore.tools.Worlds
import chat.allay.modcore.tools.at

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
import net.minecraft.world.item.crafting.Ingredient
import net.minecraft.world.item.crafting.ShapedRecipe
import net.minecraft.world.item.crafting.ShapelessRecipe
import net.minecraft.world.item.crafting.SmithingTransformRecipe
import net.minecraft.world.item.crafting.StonecutterRecipe
import net.minecraft.world.level.block.Blocks as GameBlocks
import net.minecraft.world.level.block.state.BlockState

/**
 * `craft`: crafts from this server's recipes, with plain ingredients from the inventory, bundles, the ender chest when
 * one is near, and chests near the player may open. Parts it lacks (sticks, planks) it crafts first, three levels down
 * at most. 2×2 recipes, the crafting table itself among them, need no table; 3×3 ones need a real crafting table the
 * player can see. With a stonecutter in sight, what it cuts comes from it first. Netherite gear comes from a smithing
 * table: the diamond piece keeps its enchantments, name, and wear, also when the player wears it. Mods' and data
 * packs' items craft by their ids from their own recipes, their items in the slots as they ask; water bottles fill from
 * glass bottles at water or a cauldron near, a level a bottle, as clicking does.
 */
object Crafting {
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
   * The server's recipes as data: crafting ones, stonecutting ones with [stonecutter], and filling a glass bottle when
   * [water] is near. A slot that wants an exact item (the loader's components ingredient: a water bottle) takes what
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
        else -> continue
      }
      val station = when (recipe) {
        is StonecutterRecipe -> Workshop.Station.STONECUTTER
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

  /** The nearest block [accepts] takes that the player may use and can see, or null. Tables aren't block entities, so this looks at blocks. */
  fun station(player: ServerPlayer, radius: Int, sight: Boolean, accepts: (BlockState) -> Boolean): BlockAt? {
    val origin = player.blockPosition()
    val level = player.level()
    var best: BlockAt? = null
    var bestDistance = Double.MAX_VALUE
    val reach = minOf(radius, 24)
    for (dx in -reach..reach) for (dy in -reach..reach) for (dz in -reach..reach) {
      val block = level.at(origin.offset(dx, dy, dz))
      if (!accepts(block.state)) continue
      val distance = block.corner.distanceToSqr(player.position())
      if (distance < bestDistance && Blocks.unusable(player, block, radius, sight) == null) {
        best = block
        bestDistance = distance
      }
    }
    return best
  }

  private fun spot(block: BlockAt) = buildJsonObject {
    put("x", block.x)
    put("y", block.y)
    put("z", block.z)
  }

  private fun missing(lang: chat.allay.core.Lang, items: Map<String, Int>) = ToolOutput(buildJsonObject {
    put("crafted", false)
    putJsonObject("missing") { items.forEach { (id, n) -> put(id, n) } }
    put("note", "tell the player what's missing; nothing was taken")
  }, lang.text("summary.craft_missing", "count" to items.size))

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

  val tool = object : Tool {
    override val name = "craft"

    override fun run(context: ToolContext, args: JsonObject): ToolOutput {
      val player = context.requirePlayer()
      val task = context.requireTask()
      val automation = context.features.automation
      val settings = automation.settings()
      val holds = automation.reservations
      val server = player.level().server
      automation.require(player, task, chat.allay.core.tasks.Kind.CRAFTING)
      val (itemId, item) = target(context, args.string("item") ?: throw ToolError("item is required"))
      val count = (args.int("count") ?: 1).coerceIn(1, 64)
      val lang = context.lang
      val find = { type: net.minecraft.world.level.block.Block -> station(player, settings.radius, settings.lineOfSight) { it.`is`(type) } }
      val cutter = find(GameBlocks.STONECUTTER)
      val water = Water.near(player)
      val sources = Sources.around(player, settings.radius)
      val stacks = sources.list.flatMap { it.slots() }.filterNotNull()
      val book = book(server, stacks, stonecutter = cutter != null, water = water != null)
      val recipes = book.recipes
      val smithing = item?.let { smithing(server, it) }
      if (smithing == null && recipes[itemId].isNullOrEmpty()) throw ToolError(
        if (itemId == Workshop.WATER_BOTTLE) "no water source or filled cauldron within ${Water.REACH} blocks of the player to fill glass bottles at"
        else "$itemId has no crafting${if (cutter == null) "" else " or stonecutter"} recipe on this server",
      )
      val have = stacks.mapNotNull(::key).distinct()
        .associateWith { id -> sources.free({ key(it) == id }, id, holds, task.id) }
        .filterValues { it > 0 }

      // Smithing: the template and the ingot come through the planner, which crafts an ingot from scrap and gold; the
      // diamond piece is the player's own, enchanted or not.
      val smithy = smithing?.let { find(GameBlocks.SMITHING_TABLE) ?: throw ToolError("$itemId is made on a smithing table, and there's none near the player in plain sight") }
      val upgrade = smithing?.let {
        Workshop.Recipe("smithing:$itemId", 1, listOf(ids(it.templateIngredient().orElse(null)), ids(it.additionIngredient().orElse(null))), Workshop.Station.SMITHING)
      }
      val bases = smithing?.let { recipe ->
        val kinds = ids(recipe.baseIngredient())
        // The richest pieces first: most enchantment levels, then the least worn. Slots 36–39 are what they wear.
        (0..40).mapNotNull { slot -> player.inventory.getItem(slot).takeIf { !it.isEmpty && Slots.id(it) in kinds }?.let { slot to it } }
          .sortedWith(compareByDescending<Pair<Int, ItemStack>> { levels(it.second) }.thenBy { it.second.get(DataComponents.DAMAGE) ?: 0 })
          .take(count)
      }
      if (smithing != null && bases!!.size < count) return missing(lang, mapOf(ids(smithing.baseIngredient()).first() to count - bases.size))

      val target = upgrade?.result ?: itemId
      val plan = when (val planned = Workshop.plan(target, count, have, { if (it == upgrade?.result) listOf(upgrade) else recipes[it].orEmpty() })) {
        is Workshop.Missing -> return missing(lang, planned.items)
        is Workshop.Plan -> planned
        else -> throw ToolError("couldn't plan the craft")
      }
      val table = if (!plan.needsTable) null else find(GameBlocks.CRAFTING_TABLE)
        ?: throw ToolError("no crafting table near the player in plain sight; 3×3 recipes need a real one")
      // A cauldron gives a bottle a level; water itself never runs dry.
      if (plan.fills > 0 && water!!.first < plan.fills) throw ToolError("${plan.fills} glass bottles to fill, but the cauldrons near hold only ${water.first} levels of water, a bottle each")
      val extra = plan.makes.filterKeys { it != upgrade?.result }
      val described = buildJsonObject {
        putJsonObject("takes") { plan.takes.forEach { (id, n) -> put(id, n) } }
        putJsonObject("makes") {
          extra.forEach { (id, n) -> put(id, n) }
          if (upgrade != null) put(itemId, count)
        }
        put("steps", buildJsonArray {
          plan.steps.forEach { step ->
            add(when {
              step.recipe == upgrade -> "${step.times} × $itemId on the smithing table"
              step.recipe.station == Workshop.Station.STONECUTTER -> "${step.times} × ${step.recipe.result} on the stonecutter"
              step.recipe.station == Workshop.Station.WATER -> "${step.times} × glass_bottle filled with water${if (water!!.first == Int.MAX_VALUE) "" else " at a cauldron, a level each"}"
              else -> "${step.times} × ${step.recipe.result}"
            })
          }
        })
        if (table != null) put("table", spot(table))
        if (cutter != null && Workshop.Station.STONECUTTER in plan.stations) put("stonecutter", spot(cutter))
        if (smithy != null) put("smithing_table", spot(smithy))
        if (bases != null) put("upgrades", buildJsonArray {
          bases.forEach { (slot, base) ->
            add(buildJsonObject {
              put("item", Slots.id(base))
              put("where", where(slot))
              put("enchantments", base.get(DataComponents.ENCHANTMENTS)?.size() ?: 0)
            })
          }
        })
      }
      if (args.bool("dry_run") == true) return ToolOutput(JsonObject(described + ("crafted" to JsonPrimitive(false))), lang.text("summary.craft_plan"))

      takeAll(player, task, sources, holds, plan.takes)
      if (plan.fills > 0) Water.drain(water!!, plan.fills)
      // Buckets and bottles that crafting hands back, like the game does: a cake leaves three empty buckets.
      val remains = plan.takes.mapNotNull { (id, n) -> InteractTools.known(id)?.craftingRemainder?.create()?.takeUnless { it.isEmpty }?.copyWithCount(n) }
      val made = extra.flatMap { (id, n) ->
        val stack = book.results[id] ?: InteractTools.known(id)?.let(::ItemStack) ?: return@flatMap emptyList()
        val max = stack.maxStackSize
        (0 until (n + max - 1) / max).map { stack.copyWithCount(minOf(max, n - it * max)) }
      }
      // The upgraded piece stays where the diamond one was, on the player too, with its enchantments, name, and wear.
      bases?.forEach { (slot, base) -> player.inventory.setItem(slot, base.transmuteCopy(item!!, base.count)) }
      give(player, made.flatMap(::split) + remains.flatMap(::split))
      task.report("crafted $count × $itemId into the player's inventory (at their feet when it was full)")
      val label = "$count × ${itemId.substringAfter(':').replace('_', ' ')}"
      val used = listOfNotNull(table, cutter?.takeIf { Workshop.Station.STONECUTTER in plan.stations }, smithy)
      if (used.isNotEmpty()) Marks.show(context.scheduler, player, used.map { Marks.Mark(it, null, label, Marks.Hue.GREEN) }, 10)
      return ToolOutput(JsonObject(described + ("crafted" to JsonPrimitive(true))), lang.text("summary.crafted", "count" to count, "item" to itemId))
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
