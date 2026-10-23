package chat.allay.modcore.automation

import chat.allay.core.tasks.Task
import chat.allay.core.tools.*
import chat.allay.modcore.tools.BlockAt
import chat.allay.modcore.tools.InteractTools
import chat.allay.modcore.tools.Marks
import chat.allay.modcore.tools.Recipes
import chat.allay.modcore.tools.Tool
import chat.allay.modcore.tools.ToolContext
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
import net.minecraft.world.item.ItemStack
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
 * table: the diamond piece keeps its enchantments, name, and wear, also when the player wears it.
 */
object Crafting {
  /** The server's recipes as data, by result id, stonecutting ones with [stonecutter]; recipes that want an exact item (a named one) are left out. */
  fun recipes(server: MinecraftServer, stonecutter: Boolean = false): Map<String, List<Workshop.Recipe>> {
    val found = mutableMapOf<String, MutableList<Workshop.Recipe>>()
    for (holder in Recipes.all(server)) {
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
      val choices = slots.filterNotNull().map(::ids)
      if (choices.isEmpty() || choices.any { it.isEmpty() }) continue
      val result = Recipes.result(recipe)?.takeUnless { it.isEmpty } ?: continue
      found.getOrPut(Slots.id(result)) { mutableListOf() } += Workshop.Recipe(Slots.id(result), result.count, choices, station)
    }
    return found
  }

  /** The items an ingredient takes; none for one that wants an exact item, as Bukkit's `ExactChoice` gives none. */
  private fun ids(choice: Ingredient?): Set<String> {
    if (choice == null || chat.allay.modcore.Mod.loader.custom(choice)) return emptySet()
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
      val item = InteractTools.item(args.string("item") ?: throw ToolError("item is required"))
      val itemId = Recipes.id(item)
      val count = (args.int("count") ?: 1).coerceIn(1, 64)
      val lang = context.lang
      val find = { type: net.minecraft.world.level.block.Block -> station(player, settings.radius, settings.lineOfSight) { it.`is`(type) } }
      val cutter = find(GameBlocks.STONECUTTER)
      val recipes = recipes(server, stonecutter = cutter != null)
      val smithing = smithing(server, item)
      if (smithing == null && recipes[itemId].isNullOrEmpty()) throw ToolError("$itemId has no crafting${if (cutter == null) "" else " or stonecutter"} recipe on this server")
      val sources = Sources.around(player, settings.radius)
      val have = sources.list.flatMap { it.slots() }.filterNotNull().filter(Sources::plain).map { it.item }.distinct()
        .associate { type -> Recipes.id(type) to sources.free({ it.item == type && Sources.plain(it) }, Recipes.id(type), holds, task.id) }
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
      // Buckets and bottles that crafting hands back, like the game does: a cake leaves three empty buckets.
      val remains = plan.takes.mapNotNull { (id, n) -> InteractTools.known(id)?.craftingRemainder?.create()?.takeUnless { it.isEmpty }?.copyWithCount(n) }
      val made = extra.flatMap { (id, n) ->
        val type = InteractTools.known(id) ?: return@flatMap emptyList()
        val max = type.defaultMaxStackSize
        (0 until (n + max - 1) / max).map { ItemStack(type, minOf(max, n - it * max)) }
      }
      // The upgraded piece stays where the diamond one was, on the player too, with its enchantments, name, and wear.
      bases?.forEach { (slot, base) -> player.inventory.setItem(slot, base.transmuteCopy(item, base.count)) }
      give(player, made.flatMap(::split) + remains.flatMap(::split))
      task.report("crafted $count × $itemId into the player's inventory (at their feet when it was full)")
      val label = "$count × ${itemId.replace('_', ' ')}"
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
      val type = InteractTools.known(id)!!
      val match = { stack: ItemStack -> stack.item == type && Sources.plain(stack) }
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
