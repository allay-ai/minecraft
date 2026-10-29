package chat.allay.spigot.tools

import chat.allay.core.*
import chat.allay.core.tools.*

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.bukkit.FluidCollisionMode
import org.bukkit.Material
import org.bukkit.Nameable
import org.bukkit.Tag
import org.bukkit.attribute.Attribute
import org.bukkit.block.Block
import org.bukkit.block.Container
import org.bukkit.block.data.Ageable
import org.bukkit.block.data.Waterlogged
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.AbstractVillager
import org.bukkit.entity.Enemy
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.inventory.ItemStack
import chat.allay.core.Lang
import chat.allay.core.tools.Facts.round1
import chat.allay.core.tools.Facts.short

/**
 * `look_at`: whatever is under the crosshair, in one call. A block comes with its hardness, tool, and drops, plus
 * what's inside when it's a container and the shelves when it's an enchanting table; a mob with its health and the
 * player's hits to kill it; a villager with its trades. Another player stays anonymous.
 */
object LookTools {
  /** How far the crosshair reaches: a block within the player's reach, an entity a little further. */
  const val BLOCK_RANGE = 6
  const val ENTITY_RANGE = 16.0

  /** Stacks `detail` lists at most: a full double chest, every one of its slots. */
  private const val MAX_STACKS = 54

  /** The container under the crosshair, by the same reach and rules for every tool that opens one. */
  fun targetContainer(player: Player): Pair<Block, Container> {
    val block = player.getTargetBlockExact(BLOCK_RANGE) ?: throw ToolError("the player isn't looking at a block within $BLOCK_RANGE blocks")
    val state = block.state as? Container ?: throw ToolError("${block.type.key.key} under the crosshair is not a container")
    if (!chat.allay.spigot.Claims.mayOpen(player, state)) throw ToolError(locked(state))
    return block to state
  }

  /** Why Allay won't read a container: its owner's claim, or loot that rolls only when someone opens it. */
  private fun locked(state: Container) =
    if ((state as? org.bukkit.loot.Lootable)?.lootTable != null) "an unopened structure chest: its loot rolls when someone opens it"
    else "in someone's claim where the player has no container trust; its contents stay private"

  private fun nothing(blockBeyond: Long?) = Facts.nothingInSight(BLOCK_RANGE, ENTITY_RANGE, blockBeyond)

  private val lookAt = object : Tool {
    override val name = "look_at"

    override fun run(context: ToolContext, args: JsonObject): ToolOutput {
      val player = context.requirePlayer()
      val eye = player.eyeLocation
      // Blocks stop the ray as they stop the eye, so no mob shows through a wall.
      val hit = player.world.rayTrace(eye, eye.direction, ENTITY_RANGE, FluidCollisionMode.NEVER, true, 0.0) { it != player && chat.allay.spigot.Vanish.visible(player, it) }
      val entity = hit?.hitEntity
      val block = player.getTargetBlockExact(BLOCK_RANGE)
      return when {
        entity != null -> entity(context.lang, player, entity)
        block != null -> block(context.lang, player, block, args.bool("detail") == true)
        else -> ToolOutput(nothing(hit?.hitBlock?.let { Math.round(hit.hitPosition.distance(eye.toVector())) }), context.lang.text("summary.look_nothing"))
      }
    }
  }

  private fun block(lang: Lang, player: Player, block: Block, detail: Boolean): ToolOutput {
    val type = block.type
    val held = player.inventory.itemInMainHand
    val drops = block.getDrops(held, player)
    val speed = block.getBreakSpeed(player)
    val data = block.blockData
    val state = block.state
    var summary = lang.text("summary.look_block", "id" to type.key.key)
    val result = buildJsonObject {
      put("kind", "block")
      put("id", type.key.key)
      data.asString.substringAfter('[', "").removeSuffix("]").takeIf { it.isNotEmpty() }?.let { put("state", it) }
      put("x", block.x)
      put("y", block.y)
      put("z", block.z)
      put("distance", round1(block.location.add(0.5, 0.5, 0.5).distance(player.eyeLocation)))
      put("hardness", round1(type.hardness.toDouble()))
      put("blast_resistance", round1(type.blastResistance.toDouble()))
      preferredTool(type)?.let { put("preferred_tool", it) }
      requiredTier(type)?.let { put("needs_tier", it) }
      put("held_tool_is_right", block.isPreferredTool(held))
      // Hardness −1 means unbreakable; otherwise the time with what the player holds right now.
      if (type.hardness >= 0 && speed > 0) put("break_seconds", round1(1.0 / (speed * 20)))
      put("drops_with_held_item", buildJsonArray { drops.forEach { it.compact()?.let(::add) } })
      if (data is Ageable) {
        put("age", data.age)
        put("max_age", data.maximumAge)
      }
      if (data is Waterlogged && data.isWaterlogged) put("waterlogged", true)
      put("block_light", block.lightFromBlocks.toInt())
      put("sky_light", block.lightFromSky.toInt())
      if (state is Container && !chat.allay.spigot.Claims.mayOpen(player, state)) {
        put("locked", locked(state))
      } else if (state is Container) {
        putJsonObject("container") { container(state, detail) }
        val used = state.inventory.contents.count { it != null && !it.type.isAir }
        summary = lang.text("summary.look_container", "id" to type.key.key, "used" to used, "slots" to state.inventory.size)
      }
      if (type == Material.ENCHANTING_TABLE) putJsonObject("enchanting") { enchanting(player, block) }
    }
    return ToolOutput(result, summary)
  }

  private fun JsonObjectBuilder.container(state: Container, detail: Boolean) {
    val inventory = state.inventory
    (state as? Nameable)?.customName?.let { put("name", it) }
    put("slots", inventory.size)
    put("used_slots", inventory.contents.count { it != null && !it.type.isAir })
    if (detail) {
      val (shown, more) = Mechanics.capped(inventory.contents.mapIndexedNotNull { slot, item -> item.compact(slot) }, MAX_STACKS)
      put("stacks", buildJsonArray { shown.forEach(::add) })
      if (more > 0) put("more_stacks", more)
    } else {
      Items.counts(inventory.contents.data()).forEach { (key, value) -> put(key, value) }
    }
  }

  private fun JsonObjectBuilder.enchanting(player: Player, table: Block) {
    val shelves = Mechanics.bookshelves(
      provider = { dx, dy, dz -> Tag.ENCHANTMENT_POWER_PROVIDER.isTagged(table.getRelative(dx, dy, dz).type) },
      transmitter = { dx, dy, dz -> Tag.ENCHANTMENT_POWER_TRANSMITTER.isTagged(table.getRelative(dx, dy, dz).type) },
    )
    val range = Mechanics.topOfferRange(shelves)
    put("bookshelves", shelves)
    put("counted_max", 15)
    put("top_offer_min_level", range.first)
    put("top_offer_max_level", range.last)
    put("player_level", player.level)
    put("lapis_in_inventory", player.inventory.contents.filterNotNull().filter { it.type == Material.LAPIS_LAZULI }.sumOf { it.amount })
  }

  private fun entity(lang: Lang, player: Player, entity: Entity): ToolOutput {
    val type = short(entity.type.key.toString())
    val distance = round1(entity.location.distance(player.eyeLocation))
    // Another player stays anonymous, like everywhere else: no name, health, or gear.
    if (entity is Player) {
      return ToolOutput(buildJsonObject { put("kind", "entity"); put("type", "player"); put("distance", distance) }, lang.text("summary.look_entity", "type" to type))
    }
    var summary = lang.text("summary.look_entity", "type" to type)
    val result = buildJsonObject {
      put("kind", "entity")
      put("type", type)
      entity.customName?.let { put("name", it) }
      put("distance", distance)
      when (entity) {
        is org.bukkit.entity.Item -> entity.itemStack.compact()?.let { put("item", it) }
        is ItemFrame -> entity.item.compact()?.let { put("item", it) }
        else -> {}
      }
      if (entity is LivingEntity) {
        put("health", round1(entity.health))
        put("max_health", round1(entity.getAttribute(Attribute.MAX_HEALTH)?.value ?: entity.health))
        val armor = entity.getAttribute(Attribute.ARMOR)?.value ?: 0.0
        put("armor", round1(armor))
        entity.getAttribute(Attribute.ATTACK_DAMAGE)?.value?.let { put("attack_damage", round1(it)) }
        put("hostile", entity is Enemy)
        val hits = yourHit(player, entity, armor)
        hits?.let { summary = lang.text("summary.look_mob", "type" to type, "hits" to it) }
      }
      if (entity is AbstractVillager) {
        put("profession", Villagers.profession(entity))
        (entity as? Villager)?.let { put("level", it.villagerLevel) }
        put("trades", buildJsonArray { entity.recipes.forEach { add(Villagers.trade(it)) } })
        summary = lang.text("summary.look_trader", "type" to type, "trades" to entity.recipes.size)
      }
    }
    return ToolOutput(result, summary)
  }

  /** How the player's held weapon fares against the mob; returns the hits to kill, or null when it deals no damage. */
  private fun JsonObjectBuilder.yourHit(player: Player, mob: LivingEntity, armor: Double): Int? {
    val held: ItemStack = player.inventory.itemInMainHand
    val base = player.getAttribute(Attribute.ATTACK_DAMAGE)?.value ?: 1.0
    val bonus = Mechanics.enchantmentBonus(
      sharpness = held.getEnchantmentLevel(Enchantment.SHARPNESS),
      smite = held.getEnchantmentLevel(Enchantment.SMITE),
      bane = held.getEnchantmentLevel(Enchantment.BANE_OF_ARTHROPODS),
      undead = Tag.ENTITY_TYPES_SENSITIVE_TO_SMITE.isTagged(mob.type),
      arthropod = Tag.ENTITY_TYPES_SENSITIVE_TO_BANE_OF_ARTHROPODS.isTagged(mob.type),
    )
    val toughness = mob.getAttribute(Attribute.ARMOR_TOUGHNESS)?.value ?: 0.0
    val perHit = Mechanics.afterArmor(base + bonus, armor, toughness)
    val hits = Mechanics.hitsToKill(mob.health, perHit)
    putJsonObject("your_hit") {
      held.compact()?.let { put("weapon", it) }
      put("damage_per_hit", round1(perHit))
      hits?.let { put("hits_to_kill", it) }
      put("estimate", "fully charged hits, no crits, sweeping, potions, or difficulty scaling")
    }
    return hits
  }

  private fun preferredTool(type: Material): String? = when {
    Tag.MINEABLE_PICKAXE.isTagged(type) -> "pickaxe"
    Tag.MINEABLE_AXE.isTagged(type) -> "axe"
    Tag.MINEABLE_SHOVEL.isTagged(type) -> "shovel"
    Tag.MINEABLE_HOE.isTagged(type) -> "hoe"
    else -> null
  }

  private fun requiredTier(type: Material): String? = when {
    Tag.NEEDS_DIAMOND_TOOL.isTagged(type) -> "diamond"
    Tag.NEEDS_IRON_TOOL.isTagged(type) -> "iron"
    Tag.NEEDS_STONE_TOOL.isTagged(type) -> "stone"
    else -> null
  }

  val all = listOf<Tool>(lookAt)
}
