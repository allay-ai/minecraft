package chat.allay.spigot.automation

import chat.allay.core.tools.*
import chat.allay.spigot.tools.InteractTools
import chat.allay.spigot.tools.Marks
import chat.allay.spigot.tools.Tool
import chat.allay.spigot.tools.ToolContext

import kotlinx.serialization.json.JsonObject
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

/**
 * `craft`: crafts from this server's recipes, with plain ingredients from the inventory, bundles, the ender chest when
 * one is near, and chests near the player may open. Parts it lacks (sticks, planks) it crafts first, three levels down
 * at most. A 3×3 recipe needs a real crafting table the player can see; 2×2 ones, the table itself among them, don't.
 */
object Crafting {
  /** The server's crafting recipes as data, by result id; recipes that want an exact item (a named one) are left out. */
  fun recipes(server: Server): Map<String, List<Workshop.Recipe>> {
    val found = mutableMapOf<String, MutableList<Workshop.Recipe>>()
    val iterator = server.recipeIterator()
    while (iterator.hasNext()) {
      val recipe = iterator.next()
      val slots: List<RecipeChoice?> = when (recipe) {
        is ShapedRecipe -> recipe.shape.flatMap { row -> row.map { recipe.choiceMap[it] } }
        is ShapelessRecipe -> recipe.choiceList
        else -> continue
      }
      val small = when (recipe) {
        is ShapedRecipe -> recipe.shape.size <= 2 && recipe.shape.all { it.length <= 2 }
        else -> slots.size <= 4
      }
      val choices = slots.filterNotNull().map { choice -> (choice as? RecipeChoice.MaterialChoice)?.choices?.map { it.key.key }?.toSet() ?: emptySet() }
      if (choices.isEmpty() || choices.any { it.isEmpty() }) continue
      val result = recipe.result
      found.getOrPut(result.type.key.key) { mutableListOf() } += Workshop.Recipe(result.type.key.key, result.amount, choices, small)
    }
    return found
  }

  /** The nearest crafting table the player may use and can see, or null. Tables aren't block entities, so this looks at blocks. */
  private fun table(player: Player, radius: Int, sight: Boolean): Block? {
    val origin = player.location.block
    var best: Block? = null
    var bestDistance = Double.MAX_VALUE
    val reach = minOf(radius, 12)
    for (dx in -reach..reach) for (dy in -reach..reach) for (dz in -reach..reach) {
      val block = origin.getRelative(dx, dy, dz)
      if (block.type != Material.CRAFTING_TABLE) continue
      val distance = block.location.distanceSquared(player.location)
      if (distance < bestDistance && Blocks.unusable(player, block, radius, sight) == null) {
        best = block
        bestDistance = distance
      }
    }
    return best
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
      val item = InteractTools.material(args.string("item") ?: throw ToolError("item is required"))
      val count = (args.int("count") ?: 1).coerceIn(1, 64)
      val recipes = recipes(player.server)
      if (recipes[item.key.key].isNullOrEmpty()) throw ToolError("${item.key.key} has no crafting recipe on this server")
      val sources = Sources.around(player, settings.radius)
      val have = sources.list.flatMap { it.slots() }.filterNotNull().filter(Sources::plain).map { it.type }.distinct()
        .associate { type -> type.key.key to sources.free({ it.type == type && Sources.plain(it) }, type.key.key, holds, task.id) }
        .filterValues { it > 0 }
      val lang = context.lang
      val plan = when (val planned = Workshop.plan(item.key.key, count, have, { recipes[it].orEmpty() })) {
        is Workshop.Missing -> return ToolOutput(buildJsonObject {
          put("crafted", false)
          putJsonObject("missing") { planned.items.forEach { (id, n) -> put(id, n) } }
          put("note", "tell the player what's missing; nothing was taken")
        }, lang.text("summary.craft_missing", "count" to planned.items.size))
        is Workshop.Plan -> planned
        else -> throw ToolError("couldn't plan the craft")
      }
      val table = if (!plan.needsTable) null else table(player, settings.radius, settings.lineOfSight)
        ?: throw ToolError("no crafting table near the player in plain sight; 3×3 recipes need a real one")
      val described = buildJsonObject {
        putJsonObject("takes") { plan.takes.forEach { (id, n) -> put(id, n) } }
        putJsonObject("makes") { plan.makes.forEach { (id, n) -> put(id, n) } }
        put("steps", buildJsonArray { plan.steps.forEach { add("${it.times} × ${it.recipe.result}") } })
        if (table != null) put("table", buildJsonObject { put("x", table.x); put("y", table.y); put("z", table.z) })
      }
      if (args.bool("dry_run") == true) return ToolOutput(JsonObject(described + ("crafted" to kotlinx.serialization.json.JsonPrimitive(false))), lang.text("summary.craft_plan"))

      // Take everything first; if anything falls short, it all goes back and nothing is crafted.
      val taken = mutableListOf<Pair<ItemStack, Sources.Source?>>()
      for ((id, n) in plan.takes) {
        val type = Material.matchMaterial(id)!!
        val match = { stack: ItemStack -> stack.type == type && Sources.plain(stack) }
        if (!sources.reserve(match, id, n, holds, task.id)) {
          taken.forEach { (stack, source) -> give(player, listOf(stack), source) }
          holds.release(task.id)
          throw ToolError("someone took the $id meanwhile")
        }
        val (got, short) = sources.takeReserved(match, id, n, holds, task.id)
        got.forEach { taken += it to sources.list.firstOrNull() }
        if (short != null) {
          taken.forEach { (stack, source) -> give(player, listOf(stack), source) }
          holds.release(task.id)
          throw ToolError("the $id changed meanwhile")
        }
      }
      // Buckets and bottles that crafting hands back, like the game does: a cake leaves three empty buckets.
      val remains = plan.takes.mapNotNull { (id, n) -> Material.matchMaterial(id)?.craftingRemainingItem?.let { ItemStack(it, n) } }
      val made = plan.makes.flatMap { (id, n) ->
        val type = Material.matchMaterial(id) ?: return@flatMap emptyList()
        val max = type.maxStackSize
        (0 until (n + max - 1) / max).map { ItemStack(type, minOf(max, n - it * max)) }
      }
      give(player, made + remains)
      if (table != null) Marks.show(context.plugin, player, listOf(Marks.Mark(table, null, "${count} × ${item.key.key.replace('_', ' ')}", Marks.Hue.GREEN)), 10)
      return ToolOutput(JsonObject(described + ("crafted" to kotlinx.serialization.json.JsonPrimitive(true))), lang.text("summary.crafted", "count" to count, "item" to item.key.key))
    }
  }
}
