package chat.allay.fabric.tools

import chat.allay.core.*
import chat.allay.core.tools.*
import chat.allay.fabric.Claims
import chat.allay.fabric.Texts
import chat.allay.fabric.Vanish
import chat.allay.fabric.automation.Blocks

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.Holder
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.BlockTags
import net.minecraft.tags.EntityTypeTags
import net.minecraft.world.Container
import net.minecraft.world.Nameable
import net.minecraft.world.RandomizableContainer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.entity.npc.villager.AbstractVillager
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.item.Items as GameItems
import net.minecraft.world.item.enchantment.Enchantment
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.phys.HitResult
import chat.allay.core.Lang
import chat.allay.core.tools.Facts.round1

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

  /** The block under the crosshair within [range], as Bukkit's `getTargetBlockExact`: outlines count, fluids don't. */
  fun targetBlock(player: ServerPlayer, range: Int = BLOCK_RANGE): BlockAt? {
    val eye = player.eyePosition
    val hit = player.level().clip(ClipContext(eye, eye.add(player.lookAngle.scale(range.toDouble())), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player))
    return if (hit.type == HitResult.Type.BLOCK) player.level().at(hit.blockPos) else null
  }

  /** The container under the crosshair, by the same reach and rules for every tool that opens one, with what it holds. */
  fun targetContainer(player: ServerPlayer): Pair<BlockAt, Container> {
    val block = targetBlock(player) ?: throw ToolError("the player isn't looking at a block within $BLOCK_RANGE blocks")
    val inventory = Blocks.container(block)?.let { Blocks.inventory(block) } ?: throw ToolError("${block.key} under the crosshair is not a container")
    if (!Claims.mayOpen(player, block.level, block.pos)) throw ToolError(locked(block))
    return block to inventory
  }

  /** Why Allay won't read a container: its owner's claim, or loot that rolls only when someone opens it. */
  private fun locked(block: BlockAt) =
    if ((block.entity as? RandomizableContainer)?.lootTable != null) "an unopened structure chest: its loot rolls when someone opens it"
    else "in someone's claim where the player has no container trust; its contents stay private"

  private val lookAt = object : Tool {
    override val name = "look_at"

    override fun run(context: ToolContext, args: JsonObject): ToolOutput {
      val player = context.requirePlayer()
      val eye = player.eyePosition
      val entity = entityInSight(player)
      val block = targetBlock(player)
      val entityCloser = entity != null && (block == null || entity.position().distanceTo(eye) < block.center.distanceTo(eye))
      return when {
        entity != null && entityCloser -> entity(context.lang, player, entity)
        block != null -> block(context.lang, player, block, args.bool("detail") == true)
        else -> ToolOutput(buildJsonObject { put("kind", "nothing") }, context.lang.text("summary.look_nothing"))
      }
    }
  }

  /**
   * The nearest entity whose box the line of sight crosses within [ENTITY_RANGE], walls or not, as Bukkit's
   * `rayTraceEntities`; `look_at` then compares it with the block. Vanished players are never in sight.
   */
  private fun entityInSight(player: ServerPlayer): Entity? {
    val eye = player.eyePosition
    val end = eye.add(player.lookAngle.scale(ENTITY_RANGE))
    return player.level().getEntities(player, net.minecraft.world.phys.AABB(eye, end).inflate(1.0)) { Vanish.visible(player, it) }
      .mapNotNull { entity ->
        val box = entity.boundingBox
        // From inside a box, Bukkit's ray hits where it leaves it.
        val hit = if (box.contains(eye)) box.clip(end, eye) else box.clip(eye, end)
        hit.orElse(null)?.let { entity to it.distanceToSqr(eye) }
      }
      .minByOrNull { it.second }?.first
  }

  /** The block's properties as Bukkit's `BlockData.getAsString` writes them, without the brackets: `facing=north,lit=false`. */
  private fun properties(state: BlockState): String = BlockStateParser.serialize(state).substringAfter('[', "").removeSuffix("]")

  /** The `age` of a crop or a plant that grows, as Bukkit's `Ageable` sees it; null for a block that doesn't age. */
  fun age(state: BlockState): IntegerProperty? = state.properties.firstOrNull { it.name == "age" } as? IntegerProperty

  private fun block(lang: Lang, player: ServerPlayer, block: BlockAt, detail: Boolean): ToolOutput {
    val state = block.state
    val type = state.block
    val held = player.mainHandItem
    // As Bukkit's `getDrops`: nothing when the held item can't harvest a block that needs the right tool.
    val preferred = !state.requiresCorrectToolForDrops() || held.isCorrectToolForDrops(state)
    val drops = if (preferred) Block.getDrops(state, block.level, block.pos, block.entity, player, held) else emptyList()
    val speed = state.getDestroyProgress(player, block.level, block.pos)
    val hardness = type.defaultBlockState().getDestroySpeed(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO)
    val container = Blocks.container(block)
    var summary = lang.text("summary.look_block", "id" to block.key)
    val result = buildJsonObject {
      put("kind", "block")
      put("id", block.key)
      properties(state).takeIf { it.isNotEmpty() }?.let { put("state", it) }
      put("x", block.x)
      put("y", block.y)
      put("z", block.z)
      put("distance", round1(block.center.distanceTo(player.eyePosition)))
      put("hardness", round1(hardness.toDouble()))
      put("blast_resistance", round1(type.explosionResistance.toDouble()))
      preferredTool(state)?.let { put("preferred_tool", it) }
      requiredTier(state)?.let { put("needs_tier", it) }
      put("held_tool_is_right", preferred)
      // Hardness −1 means unbreakable; otherwise the time with what the player holds right now.
      if (hardness >= 0 && speed > 0) put("break_seconds", round1(1.0 / (speed * 20)))
      put("drops_with_held_item", buildJsonArray { drops.forEach { it.compact()?.let(::add) } })
      age(state)?.let { age ->
        put("age", state.getValue(age))
        put("max_age", age.possibleValues.max())
      }
      if (state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED)) put("waterlogged", true)
      put("block_light", block.level.getBrightness(LightLayer.BLOCK, block.pos))
      put("sky_light", block.level.getBrightness(LightLayer.SKY, block.pos))
      if (container != null && !Claims.mayOpen(player, block.level, block.pos)) {
        put("locked", locked(block))
      } else if (container != null) {
        val inventory = Blocks.inventory(block) ?: container
        putJsonObject("container") { container(block, inventory, detail) }
        val used = (0 until inventory.containerSize).count { !inventory.getItem(it).isEmpty }
        summary = lang.text("summary.look_container", "id" to block.key, "used" to used, "slots" to inventory.containerSize)
      }
      if (state.`is`(net.minecraft.world.level.block.Blocks.ENCHANTING_TABLE)) putJsonObject("enchanting") { enchanting(player, block) }
    }
    return ToolOutput(result, summary)
  }

  private fun JsonObjectBuilder.container(block: BlockAt, inventory: Container, detail: Boolean) {
    val contents = (0 until inventory.containerSize).map(inventory::getItem)
    (block.entity as? Nameable)?.customName?.let { put("name", Texts.legacyOf(it)) }
    put("slots", inventory.containerSize)
    put("used_slots", contents.count { !it.isEmpty })
    if (detail) {
      val (shown, more) = Mechanics.capped(contents.mapIndexedNotNull { slot, item -> item.compact(slot) }, MAX_STACKS)
      put("stacks", buildJsonArray { shown.forEach(::add) })
      if (more > 0) put("more_stacks", more)
    } else {
      Items.counts(contents.data()).forEach { (key, value) -> put(key, value) }
    }
  }

  private fun JsonObjectBuilder.enchanting(player: ServerPlayer, table: BlockAt) {
    val shelves = Mechanics.bookshelves(
      provider = { dx, dy, dz -> table.level.getBlockState(table.pos.offset(dx, dy, dz)).`is`(BlockTags.ENCHANTMENT_POWER_PROVIDER) },
      transmitter = { dx, dy, dz -> table.level.getBlockState(table.pos.offset(dx, dy, dz)).`is`(BlockTags.ENCHANTMENT_POWER_TRANSMITTER) },
    )
    val range = Mechanics.topOfferRange(shelves)
    put("bookshelves", shelves)
    put("counted_max", 15)
    put("top_offer_min_level", range.first)
    put("top_offer_max_level", range.last)
    put("player_level", player.experienceLevel)
    put("lapis_in_inventory", PlayerTools.contents(player).filter { it.item == GameItems.LAPIS_LAZULI }.sumOf { it.count })
  }

  private fun entity(lang: Lang, player: ServerPlayer, entity: Entity): ToolOutput {
    val type = PlayerTools.type(entity)
    val distance = round1(entity.position().distanceTo(player.eyePosition))
    // Another player stays anonymous, like everywhere else: no name, health, or gear.
    if (entity is ServerPlayer) {
      return ToolOutput(buildJsonObject { put("kind", "entity"); put("type", "player"); put("distance", distance) }, lang.text("summary.look_entity", "type" to type))
    }
    var summary = lang.text("summary.look_entity", "type" to type)
    val result = buildJsonObject {
      put("kind", "entity")
      put("type", type)
      entity.customName?.let { put("name", Texts.legacyOf(it)) }
      put("distance", distance)
      when (entity) {
        is ItemEntity -> entity.item.compact()?.let { put("item", it) }
        is ItemFrame -> entity.item.compact()?.let { put("item", it) }
        else -> {}
      }
      if (entity is LivingEntity) {
        put("health", round1(entity.health.toDouble()))
        put("max_health", round1(entity.getAttribute(Attributes.MAX_HEALTH)?.value ?: entity.health.toDouble()))
        val armor = entity.getAttribute(Attributes.ARMOR)?.value ?: 0.0
        put("armor", round1(armor))
        entity.getAttribute(Attributes.ATTACK_DAMAGE)?.value?.let { put("attack_damage", round1(it)) }
        put("hostile", entity is Enemy)
        val hits = yourHit(player, entity, armor)
        hits?.let { summary = lang.text("summary.look_mob", "type" to type, "hits" to it) }
      }
      if (entity is AbstractVillager) {
        put("profession", Villagers.profession(entity))
        (entity as? Villager)?.let { put("level", it.villagerData.level()) }
        put("trades", buildJsonArray { entity.offers.forEach { add(Villagers.trade(it)) } })
        summary = lang.text("summary.look_trader", "type" to type, "trades" to entity.offers.size)
      }
    }
    return ToolOutput(result, summary)
  }

  /** An enchantment of the game, for reading its level on an item. */
  fun enchantment(player: ServerPlayer, key: ResourceKey<Enchantment>): Holder<Enchantment> =
    player.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key)

  /** How the player's held weapon fares against the mob; returns the hits to kill, or null when it deals no damage. */
  private fun JsonObjectBuilder.yourHit(player: ServerPlayer, mob: LivingEntity, armor: Double): Int? {
    val held = player.mainHandItem
    val base = player.getAttribute(Attributes.ATTACK_DAMAGE)?.value ?: 1.0
    fun level(key: ResourceKey<Enchantment>) = EnchantmentHelper.getItemEnchantmentLevel(enchantment(player, key), held)
    val bonus = Mechanics.enchantmentBonus(
      sharpness = level(Enchantments.SHARPNESS),
      smite = level(Enchantments.SMITE),
      bane = level(Enchantments.BANE_OF_ARTHROPODS),
      undead = mob.typeHolder().`is`(EntityTypeTags.SENSITIVE_TO_SMITE),
      arthropod = mob.typeHolder().`is`(EntityTypeTags.SENSITIVE_TO_BANE_OF_ARTHROPODS),
    )
    val toughness = mob.getAttribute(Attributes.ARMOR_TOUGHNESS)?.value ?: 0.0
    val perHit = Mechanics.afterArmor(base + bonus, armor, toughness)
    val hits = Mechanics.hitsToKill(mob.health.toDouble(), perHit)
    putJsonObject("your_hit") {
      held.compact()?.let { put("weapon", it) }
      put("damage_per_hit", round1(perHit))
      hits?.let { put("hits_to_kill", it) }
      put("estimate", "fully charged hits, no crits, sweeping, potions, or difficulty scaling")
    }
    return hits
  }

  private fun preferredTool(state: BlockState): String? = when {
    state.`is`(BlockTags.MINEABLE_WITH_PICKAXE) -> "pickaxe"
    state.`is`(BlockTags.MINEABLE_WITH_AXE) -> "axe"
    state.`is`(BlockTags.MINEABLE_WITH_SHOVEL) -> "shovel"
    state.`is`(BlockTags.MINEABLE_WITH_HOE) -> "hoe"
    else -> null
  }

  private fun requiredTier(state: BlockState): String? = when {
    state.`is`(BlockTags.NEEDS_DIAMOND_TOOL) -> "diamond"
    state.`is`(BlockTags.NEEDS_IRON_TOOL) -> "iron"
    state.`is`(BlockTags.NEEDS_STONE_TOOL) -> "stone"
    else -> null
  }

  val all = listOf<Tool>(lookAt)
}
