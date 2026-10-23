package chat.allay.fabric.tools

import chat.allay.core.*
import chat.allay.core.tools.*

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.core.component.DataComponents
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.enchantment.Enchantment
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.item.enchantment.ItemEnchantments
import chat.allay.core.tools.Facts.round1

/** Tools about keeping the player going: gear wear, experience, and what to eat. */
object CareTools {
  private fun tool(name: String, body: (ToolContext, ServerPlayer, JsonObject) -> ToolOutput) = object : Tool {
    override val name = name
    override fun run(context: ToolContext, args: JsonObject) = body(context, context.requirePlayer(), args)
  }

  private val armorSuffixes = listOf("_helmet", "_chestplate", "_leggings", "_boots")

  /** Where an item sits, in inspect_item's words: `from` and, for the inventory, `slot` (0–8 is the hotbar). */
  private class Spot(val from: String, val slot: Int? = null)

  private val durability = tool("get_item_durability") { context, player, _ ->
    val slots: List<Pair<Spot, ItemStack>> = listOf(
      Spot("head") to player.getItemBySlot(EquipmentSlot.HEAD), Spot("chest") to player.getItemBySlot(EquipmentSlot.CHEST),
      Spot("legs") to player.getItemBySlot(EquipmentSlot.LEGS), Spot("feet") to player.getItemBySlot(EquipmentSlot.FEET),
      Spot("offhand") to player.offhandItem,
    ) + PlayerTools.storage(player).mapIndexed { slot, item -> Spot("inventory", slot) to item }
    val unbreakingEnchantment = LookTools.enchantment(player, Enchantments.UNBREAKING)
    val mendingEnchantment = LookTools.enchantment(player, Enchantments.MENDING)
    var low = 0
    val items = slots.mapNotNull { (spot, item) ->
      if (item.isEmpty) return@mapNotNull null
      // The kind's durability, as Bukkit's `Material.getMaxDurability`; the damage the stack carries, as its meta reads it.
      val max = item.item.components().get(DataComponents.MAX_DAMAGE) ?: 0
      if (max <= 0) return@mapNotNull null
      val damage = item.own(DataComponents.DAMAGE) ?: 0
      val remaining = (max - damage).coerceAtLeast(0)
      val unbreaking = EnchantmentHelper.getItemEnchantmentLevel(unbreakingEnchantment, item)
      val id = Recipes.id(item.item)
      val armor = armorSuffixes.any { id.endsWith(it) }
      val percent = Mechanics.durabilityPercent(remaining, max)
      if (percent < 10) low++
      buildJsonObject {
        put("from", spot.from)
        spot.slot?.let { put("slot", it) }
        put("id", id)
        put("damage", damage)
        put("max_damage", max)
        put("percent_left", percent)
        if (unbreaking > 0) {
          put("unbreaking", unbreaking)
          put("expected_uses", Mechanics.expectedUses(remaining, unbreaking, armor))
        }
        if (EnchantmentHelper.getItemEnchantmentLevel(mendingEnchantment, item) > 0) put("mending", true)
        if (percent < 10) put("almost_broken", true)
      }
    }
    ToolOutput(buildJsonObject { put("items", buildJsonArray { items.forEach(::add) }) }, if (low > 0) context.lang.text("summary.wear_low", "low" to low, "count" to items.size) else context.lang.text("summary.wear_fine", "count" to items.size))
  }

  private val xp = tool("get_xp_info") { context, player, args ->
    val target = args.int("target_level")?.also { if (it !in 0..100) throw ToolError("target_level must be 0–100") }
    val level = player.experienceLevel
    val toNext = Mechanics.pointsToNext(level)
    val points = Mechanics.points(level, player.experienceProgress)
    val result = buildJsonObject {
      put("level", level)
      put("progress", round1(player.experienceProgress.toDouble()))
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
    PlayerTools.contents(player).filterNot { it.isEmpty }.forEach { item ->
      val id = Recipes.id(item.item)
      // Food a stack carries on top of its kind's, as Bukkit's `ItemMeta.hasFood` sees it.
      item.own(DataComponents.FOOD)?.let { custom[id] = it.nutrition() to it.saturation().toDouble() }
      if (id in Mechanics.FOODS || id in custom) counts.merge(id, item.count, Int::plus)
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
    }.sortedByDescending { (it["saturation"] as JsonPrimitive).content.toDouble() }
    val result = buildJsonObject {
      put("food", player.foodData.foodLevel)
      put("saturation", round1(player.foodData.saturationLevel.toDouble()))
      put("food_missing", 20 - player.foodData.foodLevel)
      put("foods", buildJsonArray { foods.forEach(::add) })
    }
    ToolOutput(result, if (foods.isEmpty()) context.lang.text("summary.food_none") else context.lang.text("summary.food", "kinds" to foods.size))
  }

  private val anvil = tool("get_anvil_plan") { context, player, args ->
    val item = player.mainHandItem.takeUnless { it.isEmpty } ?: throw ToolError("the player holds nothing; ask them to hold the item")
    // Without enchantments to aim for, plan with what the player has: books and items of this kind in the inventory.
    val wanted = args["enchantments"] as? JsonObject ?: return@tool fromInventory(context, player, (args["slots"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() })
    if (wanted.isEmpty() || wanted.size > 10) throw ToolError("give 1–10 enchantments")
    val books = wanted.keys.map { key ->
      val id = Names.enchantment(key)
      val enchantment = id.ifEmpty { null }?.let { Anvils.enchantment(player, "minecraft:$it") } ?: throw ToolError("no enchantment called $key")
      if (!enchantment.value().canEnchant(item)) throw ToolError("$id doesn't go on ${Recipes.id(item.item)}")
      val max = enchantment.value().maxLevel
      val level = (wanted.int(key) ?: max).coerceIn(1, max)
      enchantment to level
    }
    val all = books.map { it.first } + (item.get(DataComponents.ENCHANTMENTS) ?: ItemEnchantments.EMPTY).keySet()
    books.forEach { (enchantment, _) ->
      all.firstOrNull { it != enchantment && !Enchantment.areCompatible(it, enchantment) }?.let { throw ToolError("${Worlds.key(enchantment)} conflicts with ${Worlds.key(it)}") }
    }
    val penalty = item.get(DataComponents.REPAIR_COST) ?: 0
    val plan = Mechanics.anvilPlan(penalty, books.map { (e, level) -> Mechanics.Book(Worlds.key(e), level, Mechanics.bookWeight(Worlds.key(e))) })
    fun names(pieces: List<Int>) = buildJsonArray { pieces.forEach { add(JsonPrimitive(if (it == 0) "item" else "${Worlds.key(books[it - 1].first)} ${books[it - 1].second}")) } }
    val result = buildJsonObject {
      put("item", Recipes.id(item.item))
      put("item_repair_cost", penalty)
      put("steps", buildJsonArray {
        plan.steps.forEach { step -> add(buildJsonObject { put("left", names(step.left)); put("right", names(step.right)); put("levels", step.cost) }) }
      })
      put("total_levels", plan.total)
      put("too_expensive", plan.tooExpensive)
    }
    ToolOutput(result, if (plan.tooExpensive) context.lang.text("summary.anvil_too_expensive") else context.lang.text("summary.anvil", "levels" to plan.total, "steps" to plan.steps.size))
  }

  private fun fromInventory(context: ToolContext, player: ServerPlayer, slots: List<Int>?): ToolOutput {
    val planned = Anvils.plan(player, slots)
      ?: return ToolOutput(buildJsonObject { put("found", false); put("reason", "nothing in the inventory adds to the item in hand: no enchanted book or second enchanted item of its kind that improves it") }, context.lang.text("summary.anvil_nothing"))
    val (sources, plan) = planned.sources to planned.plan
    fun names(pieces: List<Int>) = buildJsonArray { pieces.forEach { add(JsonPrimitive(Anvils.label(sources[it]))) } }
    fun enchantments(piece: Mechanics.AnvilPiece) = buildJsonObject { piece.enchantments.forEach { (key, level) -> put(key, level) } }
    val result = buildJsonObject {
      put("found", true)
      put("item", Recipes.id(player.mainHandItem.item))
      put("pieces", buildJsonArray {
        plan.used.forEach { index ->
          val source = sources[index]
          add(buildJsonObject {
            put("piece", Anvils.label(source))
            source.slot?.let { put("slot", it) }
            put("kind", if (source.piece.book) "book" else Recipes.id(source.item.item))
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
