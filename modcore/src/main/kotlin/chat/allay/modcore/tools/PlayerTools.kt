package chat.allay.modcore.tools

import chat.allay.core.*
import chat.allay.core.tools.*
import chat.allay.modcore.Names
import chat.allay.modcore.Vanish

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.stats.Stats
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.monster.Enemy
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.AABB
import chat.allay.core.tools.Facts.round1

/** Tools about the asking player: state, inventory, surroundings, statistics, the villagers around. */
object PlayerTools {
  private fun tool(name: String, body: (ToolContext, ServerPlayer, JsonObject) -> ToolOutput) = object : Tool {
    override val name = name
    override fun run(context: ToolContext, args: JsonObject) = body(context, context.requirePlayer(), args)
  }

  /** The 36 slots of the main inventory, the hotbar first, as Bukkit's `storageContents`. */
  fun storage(player: ServerPlayer): List<ItemStack> = player.inventory.nonEquipmentItems

  /** Everything the player carries, as Bukkit's `PlayerInventory.getContents`: the main slots, armor from the feet up, and the off hand. */
  fun contents(player: ServerPlayer): List<ItemStack> = (0 until 41).map(player.inventory::getItem)

  /** An entity's type as the model reads it: `zombie`, or `mod:thing`. */
  fun type(entity: Entity): String = Worlds.key(EntityType.getKey(entity.type))

  private val status = tool("get_player_status") { context, player, _ ->
    val level = player.level()
    val pos = player.blockPosition()
    val maxHealth = player.getAttribute(Attributes.MAX_HEALTH)?.value ?: 20.0
    val health = player.health.toDouble()
    val food = player.foodData
    val result = buildJsonObject {
      put("gamemode", player.gameMode().getName())
      put("health", round1(health))
      put("max_health", round1(maxHealth))
      put("food", food.foodLevel)
      put("saturation", round1(food.saturationLevel.toDouble()))
      put("level", player.experienceLevel)
      put("level_progress", round1(player.experienceProgress.toDouble()))
      put("x", pos.x)
      put("y", pos.y)
      put("z", pos.z)
      put("dimension", Dimensions.id(level))
      put("biome", Worlds.key(level.at(pos).biome))
      put("light", level.getMaxLocalRawBrightness(pos))
      put("facing", player.direction.getName())
      if (player.abilities.flying) put("flying", true)
      put("effects", buildJsonArray {
        player.activeEffects.forEach {
          add(buildJsonObject {
            put("type", Worlds.key(it.effect))
            put("level", it.amplifier + 1)
            put("seconds", if (it.isInfiniteDuration) -1 else it.duration / 20)
          })
        }
      })
      player.mainHandItem.compact()?.let { put("held_item", it) }
    }
    ToolOutput(result, context.lang.text("summary.status", "health" to round1(health), "max" to round1(maxHealth), "food" to food.foodLevel))
  }

  private val inventory = tool("get_inventory") { context, player, args ->
    val storage = storage(player)
    val main = storage.mapIndexedNotNull { slot, item -> item.compact(slot) }
    val result = buildJsonObject {
      put("main", buildJsonArray { main.forEach(::add) })
      putJsonObject("armor") {
        player.getItemBySlot(EquipmentSlot.HEAD).compact()?.let { put("head", it) }
        player.getItemBySlot(EquipmentSlot.CHEST).compact()?.let { put("chest", it) }
        player.getItemBySlot(EquipmentSlot.LEGS).compact()?.let { put("legs", it) }
        player.getItemBySlot(EquipmentSlot.FEET).compact()?.let { put("feet", it) }
      }
      player.offhandItem.compact()?.let { put("offhand", it) }
      // Totals so “how many arrows do I have” counts the ones in a bundle too.
      Items.inside((storage + player.offhandItem).data()).takeIf { it.isNotEmpty() }?.let { totals ->
        putJsonObject("inside_containers") { totals.entries.sortedByDescending { it.value }.forEach { (id, n) -> put(id, n) } }
      }
      if (args.bool("include_enderchest") == true) {
        val ender = player.enderChestInventory
        val chest = (0 until ender.containerSize).map(ender::getItem)
        put("enderchest", buildJsonArray { chest.forEachIndexed { slot, item -> item.compact(slot)?.let(::add) } })
        Items.inside(chest.data()).takeIf { it.isNotEmpty() }?.let { totals ->
          putJsonObject("enderchest_inside_containers") { totals.entries.sortedByDescending { it.value }.forEach { (id, n) -> put(id, n) } }
        }
      }
    }
    ToolOutput(result, context.lang.text("summary.inventory", "stacks" to main.size))
  }

  private val notable = Regex("_ORE$|^ANCIENT_DEBRIS$|CHEST$|^BARREL$|^SPAWNER$|^TRIAL_SPAWNER$|^VAULT$|PORTAL|^END_PORTAL_FRAME$|^BEACON$|SHULKER_BOX$|^BUDDING_AMETHYST$")

  /** Mobs count within this many blocks, whatever the block radius. */
  const val ENTITY_RADIUS = 32.0

  private val surroundings = tool("get_surroundings") { context, player, args ->
    val radius = (args.int("radius") ?: 8).coerceIn(1, 16)
    val origin = player.blockPosition()
    val level = player.level()
    val counts = mutableMapOf<Block, Int>()
    val found = mutableListOf<Pair<BlockAt, Int>>()
    val minY = (origin.y - radius).coerceAtLeast(level.minY)
    val maxY = (origin.y + radius).coerceAtMost(level.maxY)
    val pos = BlockPos.MutableBlockPos()
    for (x in -radius..radius) for (y in minY..maxY) for (z in -radius..radius) {
      val state = level.getBlockState(pos.set(origin.x + x, y, origin.z + z))
      if (state.isAir) continue
      counts.merge(state.block, 1, Int::plus)
      // The same names Bukkit's materials carry, so the same blocks count as notable.
      if (notable.containsMatchIn(Names.bukkit(BuiltInRegistries.BLOCK.getKey(state.block).path))) {
        found += level.at(pos) to x * x + (y - origin.y) * (y - origin.y) + z * z
      }
    }
    val seen = level.getEntities(player, AABB.ofSize(player.position(), ENTITY_RADIUS * 2, ENTITY_RADIUS * 2, ENTITY_RADIUS * 2)) { it is LivingEntity && Vanish.visible(player, it) }
      .map { Facts.Seen(type(it), it.position().distanceTo(player.position()), it is Enemy, it is ServerPlayer) }
    val entities = Facts.entities(seen)
    val result = buildJsonObject {
      put("radius", radius)
      putJsonObject("blocks") {
        counts.entries.sortedByDescending { it.value }.take(12).forEach { (block, count) -> put(Worlds.key(BuiltInRegistries.BLOCK.getKey(block)), count) }
      }
      put("notable", buildJsonArray {
        found.sortedBy { it.second }.take(15).forEach { (block, _) ->
          add(buildJsonObject {
            put("id", block.key)
            place(block.x, block.y, block.z, player)
          })
        }
      })
      entities.forEach { (key, value) -> put(key, value) }
    }
    val creatures = seen.count { !it.player }
    ToolOutput(result, context.lang.text("summary.surroundings", "kinds" to counts.size, "creatures" to creatures))
  }

  /** A statistic of the game's `custom` kind, like play time or jumps. */
  fun stat(player: ServerPlayer, id: Identifier): Int = runCatching { player.stats.getValue(Stats.CUSTOM, id) }.getOrDefault(0)

  private val statistics = tool("get_statistics") { context, player, _ ->
    fun stat(id: Identifier) = stat(player, id)
    val hours = round1(stat(Stats.PLAY_TIME) / 72_000.0)
    val result = buildJsonObject {
      put("play_time_hours", hours)
      put("deaths", stat(Stats.DEATHS))
      put("mob_kills", stat(Stats.MOB_KILLS))
      put("player_kills", stat(Stats.PLAYER_KILLS))
      put("walked_km", round1((stat(Stats.WALK_ONE_CM) + stat(Stats.SPRINT_ONE_CM)) / 100_000.0))
      put("flown_km", round1((stat(Stats.FLY_ONE_CM) + stat(Stats.AVIATE_ONE_CM)) / 100_000.0))
      put("jumps", stat(Stats.JUMP))
      put("fish_caught", stat(Stats.FISH_CAUGHT))
      put("minutes_since_death", stat(Stats.TIME_SINCE_DEATH) / 1_200)
      // Phantoms come after three in-game days without sleep; the model needs this to explain them.
      put("minutes_since_rest", stat(Stats.TIME_SINCE_REST) / 1_200)
    }
    ToolOutput(result, context.lang.text("summary.statistics", "hours" to hours))
  }

  private val villagers = tool("find_villagers") { context, player, args ->
    val limit = context.features.villagerRadius()
    val radius = (args.int("radius") ?: limit).coerceIn(4, limit)
    val item = args.string("item")?.let(InteractTools::item)
    val profession = args.string("profession")?.lowercase()
    val found = Villagers.around(player, radius, item).filter { profession == null || Villagers.profession(it.trader) == profession }.take(10)
    val result = buildJsonObject {
      put("radius", radius)
      put("villagers", buildJsonArray {
        found.forEach { villager ->
          val at = villager.trader.blockPosition()
          add(buildJsonObject {
            put("profession", Villagers.profession(villager.trader))
            (villager.trader as? Villager)?.let { put("level", it.villagerData.level()) }
            place(at.x, at.y, at.z, player)
            put("trades", buildJsonArray { villager.trades.take(10).forEach { add(Villagers.trade(it)) } })
          })
        }
      })
    }
    ToolOutput(result, if (found.isEmpty()) context.lang.text("summary.villagers_none") else context.lang.text("summary.villagers", "count" to found.size))
  }

  val all = listOf(status, inventory, surroundings, statistics, villagers)
}
