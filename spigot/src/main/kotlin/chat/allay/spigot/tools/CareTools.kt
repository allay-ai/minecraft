package chat.allay.spigot.tools

import chat.allay.core.*
import chat.allay.core.tools.*

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import chat.allay.core.tools.Facts.round1

/** Tools about keeping the player going: gear wear, experience, and what to eat. */
object CareTools {
  private fun tool(name: String, body: (ToolContext, Player, JsonObject) -> ToolOutput) = object : Tool {
    override val name = name
    override fun run(context: ToolContext, args: JsonObject) = body(context, context.requirePlayer(), args)
  }

  private val armorSuffixes = listOf("_helmet", "_chestplate", "_leggings", "_boots")

  /** Where an item sits, in inspect_item's words: `from` and, for the inventory, `slot` (0–8 is the hotbar). */
  private class Spot(val from: String, val slot: Int? = null)

  private val durability = tool("get_item_durability") { context, player, _ ->
    val inventory = player.inventory
    val slots: List<Pair<Spot, ItemStack?>> = listOf(
      Spot("head") to inventory.helmet, Spot("chest") to inventory.chestplate, Spot("legs") to inventory.leggings, Spot("feet") to inventory.boots,
      Spot("offhand") to inventory.itemInOffHand,
    ) + inventory.storageContents.mapIndexed { slot, item -> Spot("inventory", slot) to item }
    var low = 0
    val items = slots.mapNotNull { (spot, item) ->
      if (item == null || item.type.isAir) return@mapNotNull null
      val max = item.type.maxDurability.toInt()
      if (max <= 0) return@mapNotNull null
      val damage = (item.itemMeta as? Damageable)?.damage ?: 0
      val remaining = (max - damage).coerceAtLeast(0)
      val unbreaking = item.getEnchantmentLevel(Enchantment.UNBREAKING)
      val armor = armorSuffixes.any { item.type.key.key.endsWith(it) }
      val percent = Mechanics.durabilityPercent(remaining, max)
      if (percent < 10) low++
      buildJsonObject {
        put("from", spot.from)
        spot.slot?.let { put("slot", it) }
        put("id", item.type.key.key)
        put("damage", damage)
        put("max_damage", max)
        put("percent_left", percent)
        if (unbreaking > 0) {
          put("unbreaking", unbreaking)
          put("expected_uses", Mechanics.expectedUses(remaining, unbreaking, armor))
        }
        if (item.getEnchantmentLevel(Enchantment.MENDING) > 0) put("mending", true)
        if (percent < 10) put("almost_broken", true)
      }
    }
    ToolOutput(buildJsonObject { put("items", buildJsonArray { items.forEach(::add) }) }, if (low > 0) context.lang.text("summary.wear_low", "low" to low, "count" to items.size) else context.lang.text("summary.wear_fine", "count" to items.size))
  }

  private val xp = tool("get_xp_info") { context, player, args ->
    val target = args.int("target_level")?.also { if (it !in 0..100) throw ToolError("target_level must be 0–100") }
    val level = player.level
    val toNext = Mechanics.pointsToNext(level)
    val points = Mechanics.points(level, player.exp)
    val result = buildJsonObject {
      put("level", level)
      put("progress", round1(player.exp.toDouble()))
      put("total_points", points)
      put("points_to_next_level", toNext - (points - Mechanics.pointsAt(level)))
      target?.let {
        put("target_level", it)
        put("points_to_target", (Mechanics.pointsAt(it) - points).coerceAtLeast(0))
      }
    }
    ToolOutput(result, context.lang.text("summary.xp", "level" to level, "points" to points))
  }

  private val food = tool("get_food_options") { context, player, _ ->
    val counts = mutableMapOf<String, Int>()
    val custom = mutableMapOf<String, Pair<Int, Double>>()
    player.inventory.contents.filterNotNull().filterNot { it.type.isAir }.forEach { item ->
      val id = item.type.key.key
      val meta = item.itemMeta
      if (meta?.hasFood() == true) custom[id] = meta.food.nutrition to meta.food.saturation.toDouble()
      if (id in Mechanics.FOODS || id in custom) counts.merge(id, item.amount, Int::plus)
    }
    val foods = counts.entries.map { (id, count) ->
      val known = Mechanics.FOODS[id]
      val nutrition = custom[id]?.first ?: known!!.nutrition
      val saturation = custom[id]?.second ?: known!!.saturation
      buildJsonObject {
        put("id", id)
        put("count", count)
        put("nutrition", nutrition)
        put("saturation", saturation)
        known?.note?.let { put("note", it) }
      }
    }.sortedByDescending { (it["saturation"] as kotlinx.serialization.json.JsonPrimitive).content.toDouble() }
    val result = buildJsonObject {
      put("food", player.foodLevel)
      put("saturation", round1(player.saturation.toDouble()))
      put("food_missing", 20 - player.foodLevel)
      put("foods", buildJsonArray { foods.forEach(::add) })
    }
    ToolOutput(result, if (foods.isEmpty()) context.lang.text("summary.food_none") else context.lang.text("summary.food", "kinds" to foods.size))
  }

  private val anvil = tool("get_anvil_plan") { context, player, args ->
    val slots = (args["slots"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }
    val wanted = args["enchantments"] as? JsonObject
    if (wanted != null && (wanted.isEmpty() || wanted.size > 10)) throw ToolError("give 1–10 enchantments")
    // The item may lie anywhere: named, in a slot, or the one the books improve; the hand only when nothing says otherwise.
    // A name in any language finds the player's item through the catalog («мой меч»).
    val named = { name: String -> Catalogs.build(context).search(name, context.nameLocale, 20, setOf(Catalog.Kind.ITEM)).map { it.entry.id }.toSet() }
    val pick = if (wanted == null) Anvils.target(player, args.string("item"), args.int("slot"), slots, named) else idealTarget(player, args.string("item"), args.int("slot"), wanted, named)
    val target = when (pick) {
      is AnvilTargets.Pick.One -> pick.slot
      is AnvilTargets.Pick.Choose -> return@tool choose(context, player, pick)
      AnvilTargets.Pick.None -> return@tool ToolOutput(
        buildJsonObject {
          put("found", false)
          put("reason", if (wanted == null) "nothing the player has gains on an anvil: no enchanted book fits any of their items, and no two enchanted items of a kind" else "no item the player has takes all of these enchantments")
        },
        context.lang.text("summary.anvil_nothing"),
      )
    }
    // Without enchantments to aim for, plan with what the player has: books and items of this kind in the inventory.
    if (wanted == null) return@tool fromInventory(context, player, slots, target)
    val item = player.inventory.getItem(target)?.takeUnless { it.type.isAir } ?: throw ToolError("${AnvilTargets.label(target, player.inventory.heldItemSlot)} is empty")
    val books = wanted.keys.map { key ->
      val id = Names.enchantment(key)
      val enchantment = id.ifEmpty { null }?.let(Anvils::enchantment) ?: throw ToolError("no enchantment called $key")
      if (!enchantment.canEnchantItem(item)) throw ToolError("$id doesn't go on ${item.type.key.key}")
      val level = (wanted.int(key) ?: enchantment.maxLevel).coerceIn(1, enchantment.maxLevel)
      enchantment to level
    }
    val all = books.map { it.first } + item.enchantments.keys
    books.forEach { (enchantment, _) ->
      all.firstOrNull { it != enchantment && it.conflictsWith(enchantment) }?.let { throw ToolError("${Anvils.id(enchantment)} conflicts with ${Anvils.id(it)}") }
    }
    val penalty = (item.itemMeta as? org.bukkit.inventory.meta.Repairable)?.repairCost ?: 0
    val plan = Mechanics.anvilPlan(penalty, books.map { (e, level) -> Mechanics.Book(Anvils.id(e), level, Mechanics.bookWeight(Anvils.id(e))) })
    fun names(pieces: List<Int>) = buildJsonArray { pieces.forEach { add(kotlinx.serialization.json.JsonPrimitive(if (it == 0) "item" else "${Anvils.id(books[it - 1].first)} ${books[it - 1].second}")) } }
    val result = buildJsonObject {
      put("item", item.type.key.key)
      put("target", AnvilTargets.label(target, player.inventory.heldItemSlot))
      put("slot", target)
      put("item_repair_cost", penalty)
      put("steps", buildJsonArray {
        plan.steps.forEach { step -> add(buildJsonObject { put("left", names(step.left)); put("right", names(step.right)); put("levels", step.cost) }) }
      })
      put("total_levels", plan.total)
      put("too_expensive", plan.tooExpensive)
    }
    ToolOutput(result, if (plan.tooExpensive) context.lang.text("summary.anvil_too_expensive") else context.lang.text("summary.anvil", "levels" to plan.total, "steps" to plan.steps.size))
  }

  /**
   * The item for ideal books: the one named, else the one in hand, else the only item that takes every enchantment
   * asked for; several, and the player picks.
   */
  private fun idealTarget(player: Player, item: String?, slot: Int?, wanted: JsonObject, named: (String) -> Set<String>): AnvilTargets.Pick {
    if (slot != null || item != null && Names.normalize(item) != "hand") return Anvils.target(player, item, slot, emptyList(), named)
    val hand = player.inventory.heldItemSlot
    // What they hold is what they mean; a book that doesn't fit it says so.
    player.inventory.getItem(hand)?.takeUnless { it.type.isAir || it.type == org.bukkit.Material.ENCHANTED_BOOK || it.type == org.bukkit.Material.BOOK }?.let { return AnvilTargets.Pick.One(hand) }
    val enchantments = wanted.keys.mapNotNull { Anvils.enchantment(Names.enchantment(it)) }
    if (enchantments.size < wanted.size) throw ToolError("no enchantment called ${wanted.keys.first { Anvils.enchantment(Names.enchantment(it)) == null }}")
    val fitting = AnvilTargets.ALL.mapNotNull { at ->
      val stack = player.inventory.getItem(at)?.takeUnless { it.type.isAir || it.type == org.bukkit.Material.ENCHANTED_BOOK || it.type == org.bukkit.Material.BOOK } ?: return@mapNotNull null
      if (enchantments.all { it.canEnchantItem(stack) }) AnvilTargets.Option(at, stack.type.key.key, wanted.keys.sumOf { wanted.int(it) ?: 1 }) else null
    }
    // Several kinds fit (a sword and an axe both take Sharpness): the hand settles it, else the player does.
    return AnvilTargets.pick(fitting.distinctBy { it.id }.let { kinds -> if (kinds.size == 1) kinds else fitting }, hand)
  }

  /** Several items could be meant: the model asks which, with a button each. */
  private fun choose(context: ToolContext, player: Player, pick: AnvilTargets.Pick.Choose): ToolOutput {
    val hand = player.inventory.heldItemSlot
    val result = buildJsonObject {
      put("found", false)
      put("reason", "several items could be meant: ask the player which, with a button each, then call again with its slot")
      put("choose", buildJsonArray {
        pick.options.forEach { option ->
          add(buildJsonObject { put("slot", option.slot); put("where", AnvilTargets.label(option.slot, hand)); put("item", option.id); put("gain", option.gain) })
        }
      })
    }
    return ToolOutput(result, context.lang.text("summary.anvil_choose", "count" to pick.options.size))
  }

  private fun fromInventory(context: ToolContext, player: Player, slots: List<Int>?, target: Int): ToolOutput {
    val planned = Anvils.plan(player, slots, target)
      ?: return ToolOutput(buildJsonObject { put("found", false); put("reason", "nothing in the inventory adds to that item: no enchanted book or second enchanted item of its kind that improves it") }, context.lang.text("summary.anvil_nothing"))
    val (sources, plan) = planned.sources to planned.plan
    fun names(pieces: List<Int>) = buildJsonArray { pieces.forEach { add(kotlinx.serialization.json.JsonPrimitive(Anvils.label(sources[it], planned.hand))) } }
    fun enchantments(piece: Mechanics.AnvilPiece) = buildJsonObject { piece.enchantments.forEach { (key, level) -> put(key, level) } }
    val result = buildJsonObject {
      put("found", true)
      put("item", planned.target.item.type.key.key)
      put("target", Anvils.label(planned.target, planned.hand))
      put("slot", target)
      put("pieces", buildJsonArray {
        plan.used.forEach { index ->
          val source = sources[index]
          add(buildJsonObject {
            put("piece", Anvils.label(source, planned.hand))
            put("slot", source.slot)
            put("kind", if (source.piece.book) "book" else source.item.type.key.key)
            put("enchantments", enchantments(source.piece))
            put("repair_cost", source.piece.repairCost)
          })
        }
      })
      put("steps", buildJsonArray {
        plan.steps.forEach { step -> add(buildJsonObject { put("left", names(step.left)); put("right", names(step.right)); put("levels", step.cost); put("gives", enchantments(step.result)) }) }
      })
      put("result", enchantments(plan.result))
      put("total_levels", plan.total)
      put("too_expensive", plan.tooExpensive)
      val unused = sources.indices.filter { it !in plan.used }
      if (unused.isNotEmpty()) put("unused", names(unused))
    }
    val summary = if (plan.tooExpensive) context.lang.text("summary.anvil_too_expensive") else context.lang.text("summary.anvil", "levels" to plan.total, "steps" to plan.steps.size)
    return ToolOutput(result, summary)
  }

  val all = listOf(durability, xp, food, anvil)
}
