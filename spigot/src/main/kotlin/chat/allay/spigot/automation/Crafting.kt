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
 * one is near, and chests near the player may open. Parts it lacks (sticks, planks) it crafts first, three levels down
 * at most. 2×2 recipes, the crafting table itself among them, need no table; 3×3 ones need a real crafting table the
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
   * The server's recipes as data: crafting ones, stonecutting ones with [stonecutter], and filling a glass bottle when
   * [water] is near. A slot that wants an exact item (a plugin's taurine, a water bottle) takes it by its [key]; one that
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
        else -> continue
      }
      val station = when (recipe) {
        is StonecuttingRecipe -> Workshop.Station.STONECUTTER
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

  private fun missing(lang: chat.allay.core.Lang, items: Map<String, Int>) = ToolOutput(buildJsonObject {
    put("crafted", false)
    putJsonObject("missing") { items.forEach { (id, n) -> put(id, n) } }
    put("note", "tell the player what's missing; nothing was taken")
  }, lang.text("summary.craft_missing", "count" to items.size))

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

  val tool = object : Tool {
    override val name = "craft"

    override fun run(context: ToolContext, args: JsonObject): ToolOutput {
      val player = context.requirePlayer()
      val task = context.requireTask()
      val automation = context.features.automation
      val settings = automation.settings()
      val holds = automation.reservations
      automation.require(player, task, chat.allay.core.tasks.Kind.CRAFTING)
      val (itemId, item) = target(context, args.string("item") ?: throw ToolError("item is required"))
      val count = (args.int("count") ?: 1).coerceIn(1, 64)
      val lang = context.lang
      val find = { type: Material -> station(player, settings.radius, settings.lineOfSight) { it == type } }
      val cutter = find(Material.STONECUTTER)
      val water = Water.near(player)
      val book = book(player.server, stonecutter = cutter != null, water = water != null)
      val recipes = book.recipes
      val smithing = item?.let { smithing(player.server, it) }
      if (smithing == null && recipes[itemId].isNullOrEmpty()) throw ToolError(
        if (itemId == Workshop.WATER_BOTTLE) "no water source or filled cauldron within ${Water.REACH} blocks of the player to fill glass bottles at"
        else "$itemId has no crafting${if (cutter == null) "" else " or stonecutter"} recipe on this server",
      )
      val sources = Sources.around(player, settings.radius)
      val have = sources.list.flatMap { it.slots() }.filterNotNull().mapNotNull(::key).distinct()
        .associateWith { id -> sources.free({ key(it) == id }, id, holds, task.id) }
        .filterValues { it > 0 }

      // Smithing: the template and the ingot come through the planner, which crafts an ingot from scrap and gold; the
      // diamond piece is the player's own, enchanted or not.
      val smithy = smithing?.let { find(Material.SMITHING_TABLE) ?: throw ToolError("$itemId is made on a smithing table, and there's none near the player in plain sight") }
      val upgrade = smithing?.let { Workshop.Recipe("smithing:$itemId", 1, listOf(ids(it.template), ids(it.addition)), Workshop.Station.SMITHING) }
      val bases = smithing?.let { recipe ->
        val kinds = ids(recipe.base)
        // The richest pieces first: most enchantment levels, then the least worn. Slots 36–39 are what they wear.
        (0..40).mapNotNull { slot -> player.inventory.getItem(slot)?.takeIf { it.type.key.key in kinds }?.let { slot to it } }
          .sortedWith(compareByDescending<Pair<Int, ItemStack>> { it.second.enchantments.values.sum() }.thenBy { (it.second.itemMeta as? Damageable)?.damage ?: 0 })
          .take(count)
      }
      if (smithing != null && bases!!.size < count) return missing(lang, mapOf(ids(smithing.base).first() to count - bases.size))

      val target = upgrade?.result ?: itemId
      val plan = when (val planned = Workshop.plan(target, count, have, { if (it == upgrade?.result) listOf(upgrade) else recipes[it].orEmpty() })) {
        is Workshop.Missing -> return missing(lang, planned.items)
        is Workshop.Plan -> planned
        else -> throw ToolError("couldn't plan the craft")
      }
      val table = if (!plan.needsTable) null else find(Material.CRAFTING_TABLE)
        ?: throw ToolError("no crafting table near the player in plain sight; 3×3 recipes need a real one")
      // A cauldron gives a bottle a level; water itself never runs dry.
      if (plan.fills > 0 && water!!.first < plan.fills) throw ToolError("${plan.fills} glass bottles to fill, but the cauldrons near hold only ${water.first} levels of water, a bottle each")
      val extra = plan.makes.filterKeys { it != upgrade?.result }
      val described = buildJsonObject {
        putJsonObject("takes") { plan.takes.forEach { (id, n) -> put(id, n) } }
        putJsonObject("makes") { extra.forEach { (id, n) -> put(id, n) }; if (upgrade != null) put(itemId, count) }
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
          bases.forEach { (slot, base) -> add(buildJsonObject { put("item", base.type.key.key); put("where", where(slot)); put("enchantments", base.enchantments.size) }) }
        })
      }
      if (args.bool("dry_run") == true) return ToolOutput(JsonObject(described + ("crafted" to JsonPrimitive(false))), lang.text("summary.craft_plan"))

      takeAll(player, task, sources, holds, plan.takes)
      if (plan.fills > 0) Water.drain(water!!, plan.fills)
      // Buckets and bottles that crafting hands back, like the game does: a cake leaves three empty buckets.
      val remains = plan.takes.mapNotNull { (id, n) -> Material.matchMaterial(id)?.craftingRemainingItem?.let { ItemStack(it, n) } }
      val made = extra.flatMap { (id, n) ->
        val stack = book.results[id] ?: Material.matchMaterial(id)?.let(::ItemStack) ?: return@flatMap emptyList()
        val max = stack.maxStackSize
        (0 until (n + max - 1) / max).map { stack.clone().apply { amount = minOf(max, n - it * max) } }
      }
      // The upgraded piece stays where the diamond one was, on the player too, with its enchantments, name, and wear.
      @Suppress("DEPRECATION")
      bases?.forEach { (slot, base) -> player.inventory.setItem(slot, base.clone().apply { type = item!! }) }
      give(player, made + remains)
      task.report("crafted $count × $itemId into the player's inventory (at their feet when it was full)")
      val label = "$count × ${itemId.substringAfter(':').replace('_', ' ')}"
      val used = listOfNotNull(table, cutter?.takeIf { Workshop.Station.STONECUTTER in plan.stations }, smithy)
      if (used.isNotEmpty()) Marks.show(context.plugin, player, used.map { Marks.Mark(it, null, label, Marks.Hue.GREEN) }, 10)
      return ToolOutput(JsonObject(described + ("crafted" to JsonPrimitive(true))), lang.text("summary.crafted", "count" to count, "item" to itemId))
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
