package chat.allay.spigot.automation

import chat.allay.core.tasks.Task
import chat.allay.core.tools.*
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
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.RecipeChoice
import org.bukkit.inventory.ShapedRecipe
import org.bukkit.inventory.ShapelessRecipe
import org.bukkit.inventory.SmithingTransformRecipe
import org.bukkit.inventory.StonecuttingRecipe
import org.bukkit.inventory.meta.Damageable

/**
 * `craft`: crafts from this server's recipes, with plain ingredients from the inventory, bundles, the ender chest when
 * one is near, and chests near the player may open. Parts it lacks (sticks, planks) it crafts first, three levels down
 * at most. 2×2 recipes, the crafting table itself among them, need no table; 3×3 ones need a real crafting table the
 * player can see. With a stonecutter in sight, what it cuts comes from it first. Netherite gear comes from a smithing
 * table: the diamond piece keeps its enchantments, name, and wear, also when the player wears it.
 */
object Crafting {
  /** The server's recipes as data, by result id, stonecutting ones with [stonecutter]; recipes that want an exact item (a named one) are left out. */
  fun recipes(server: Server, stonecutter: Boolean = false): Map<String, List<Workshop.Recipe>> {
    val found = mutableMapOf<String, MutableList<Workshop.Recipe>>()
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
      val result = recipe.result
      found.getOrPut(result.type.key.key) { mutableListOf() } += Workshop.Recipe(result.type.key.key, result.amount, choices, station)
    }
    return found
  }

  private fun ids(choice: RecipeChoice?): Set<String> = (choice as? RecipeChoice.MaterialChoice)?.choices?.map { it.key.key }?.toSet() ?: emptySet()

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
    val reach = minOf(radius, 12)
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

  val tool = object : Tool {
    override val name = "craft"

    override fun run(context: ToolContext, args: JsonObject): ToolOutput {
      val player = context.requirePlayer()
      val task = context.requireTask()
      val automation = context.features.automation
      val settings = automation.settings()
      val holds = automation.reservations
      automation.require(player, task, chat.allay.core.tasks.Kind.CRAFTING)
      val item = InteractTools.material(args.string("item") ?: throw ToolError("item is required"))
      val count = (args.int("count") ?: 1).coerceIn(1, 64)
      val lang = context.lang
      val find = { type: Material -> station(player, settings.radius, settings.lineOfSight) { it == type } }
      val cutter = find(Material.STONECUTTER)
      val recipes = recipes(player.server, stonecutter = cutter != null)
      val smithing = smithing(player.server, item)
      if (smithing == null && recipes[item.key.key].isNullOrEmpty()) throw ToolError("${item.key.key} has no crafting${if (cutter == null) "" else " or stonecutter"} recipe on this server")
      val sources = Sources.around(player, settings.radius)
      val have = sources.list.flatMap { it.slots() }.filterNotNull().filter(Sources::plain).map { it.type }.distinct()
        .associate { type -> type.key.key to sources.free({ it.type == type && Sources.plain(it) }, type.key.key, holds, task.id) }
        .filterValues { it > 0 }

      // Smithing: the template and the ingot come through the planner, which crafts an ingot from scrap and gold; the
      // diamond piece is the player's own, enchanted or not.
      val smithy = smithing?.let { find(Material.SMITHING_TABLE) ?: throw ToolError("${item.key.key} is made on a smithing table, and there's none near the player in plain sight") }
      val upgrade = smithing?.let { Workshop.Recipe("smithing:${item.key.key}", 1, listOf(ids(it.template), ids(it.addition)), Workshop.Station.SMITHING) }
      val bases = smithing?.let { recipe ->
        val kinds = ids(recipe.base)
        // The richest pieces first: most enchantment levels, then the least worn. Slots 36–39 are what they wear.
        (0..40).mapNotNull { slot -> player.inventory.getItem(slot)?.takeIf { it.type.key.key in kinds }?.let { slot to it } }
          .sortedWith(compareByDescending<Pair<Int, ItemStack>> { it.second.enchantments.values.sum() }.thenBy { (it.second.itemMeta as? Damageable)?.damage ?: 0 })
          .take(count)
      }
      if (smithing != null && bases!!.size < count) return missing(lang, mapOf(ids(smithing.base).first() to count - bases.size))

      val target = upgrade?.result ?: item.key.key
      val plan = when (val planned = Workshop.plan(target, count, have, { if (it == upgrade?.result) listOf(upgrade) else recipes[it].orEmpty() })) {
        is Workshop.Missing -> return missing(lang, planned.items)
        is Workshop.Plan -> planned
        else -> throw ToolError("couldn't plan the craft")
      }
      val table = if (!plan.needsTable) null else find(Material.CRAFTING_TABLE)
        ?: throw ToolError("no crafting table near the player in plain sight; 3×3 recipes need a real one")
      val extra = plan.makes.filterKeys { it != upgrade?.result }
      val described = buildJsonObject {
        putJsonObject("takes") { plan.takes.forEach { (id, n) -> put(id, n) } }
        putJsonObject("makes") { extra.forEach { (id, n) -> put(id, n) }; if (upgrade != null) put(item.key.key, count) }
        put("steps", buildJsonArray {
          plan.steps.forEach { step ->
            add(when {
              step.recipe == upgrade -> "${step.times} × ${item.key.key} on the smithing table"
              step.recipe.station == Workshop.Station.STONECUTTER -> "${step.times} × ${step.recipe.result} on the stonecutter"
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
      // Buckets and bottles that crafting hands back, like the game does: a cake leaves three empty buckets.
      val remains = plan.takes.mapNotNull { (id, n) -> Material.matchMaterial(id)?.craftingRemainingItem?.let { ItemStack(it, n) } }
      val made = extra.flatMap { (id, n) ->
        val type = Material.matchMaterial(id) ?: return@flatMap emptyList()
        val max = type.maxStackSize
        (0 until (n + max - 1) / max).map { ItemStack(type, minOf(max, n - it * max)) }
      }
      // The upgraded piece stays where the diamond one was, on the player too, with its enchantments, name, and wear.
      @Suppress("DEPRECATION")
      bases?.forEach { (slot, base) -> player.inventory.setItem(slot, base.clone().apply { type = item }) }
      give(player, made + remains)
      task.report("crafted $count × ${item.key.key} into the player's inventory (at their feet when it was full)")
      val label = "$count × ${item.key.key.replace('_', ' ')}"
      val used = listOfNotNull(table, cutter?.takeIf { Workshop.Station.STONECUTTER in plan.stations }, smithy)
      if (used.isNotEmpty()) Marks.show(context.plugin, player, used.map { Marks.Mark(it, null, label, Marks.Hue.GREEN) }, 10)
      return ToolOutput(JsonObject(described + ("crafted" to JsonPrimitive(true))), lang.text("summary.crafted", "count" to count, "item" to item.key.key))
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
      val type = Material.matchMaterial(id)!!
      val match = { stack: ItemStack -> stack.type == type && Sources.plain(stack) }
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
