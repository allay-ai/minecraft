package chat.allay.fabric.tools

import chat.allay.core.*
import chat.allay.core.tools.*
import chat.allay.fabric.Hooks
import chat.allay.fabric.Sounds
import chat.allay.fabric.Texts
import chat.allay.fabric.of

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.stats.Stat
import net.minecraft.stats.StatType
import net.minecraft.stats.Stats
import net.minecraft.world.BossEvent
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.gamerules.GameRules
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.hypot
import kotlin.math.max

/**
 * Goals, their boss bars, and the events and ticks that move them. The goals are part of each player's state on the
 * backend ([PlayerData]); while the player plays, the book here is the live copy. The bars are the game's own boss
 * bars, each shown to its player alone.
 */
class Goals(
  private val scheduler: Scheduler,
  private val locales: Locales,
  private val data: PlayerData,
  private val max: () -> Int,
) {
  private val books = ConcurrentHashMap<UUID, GoalBook>()
  private val bars = ConcurrentHashMap<String, ServerBossEvent>()
  private var ticker: AutoCloseable? = null
  @Volatile private var server: MinecraftServer? = null

  fun of(uuid: UUID): GoalBook = books.computeIfAbsent(uuid) { GoalBook(data.of(it).goals.orEmpty(), max) }

  /** How many goals an online player has; safe from any thread. */
  fun count(uuid: UUID): Int = books[uuid]?.all()?.size ?: 0

  /** A player's goals, from the live book or the state in memory; safe from any thread. */
  fun snapshot(uuid: UUID): List<Goal> = books[uuid]?.all() ?: data.of(uuid).goals.orEmpty()

  /**
   * The backend sent the player's state after they joined: their goals replace the empty book they started with. Called
   * off the server thread; the bars change on it.
   */
  fun loaded(uuid: UUID, state: PlayerState) {
    scheduler.main {
      books[uuid] = GoalBook(state.goals.orEmpty(), max)
      bars.keys.filter { it.startsWith("$uuid:") }.forEach { bars.remove(it)?.removeAllPlayers() }
      server?.playerList?.getPlayer(uuid)?.let(::show)
    }
  }

  /** Starts the bars and the once-a-second poll on [server]; the game's events come through [Hooks]. */
  fun start(server: MinecraftServer) {
    this.server = server
    Hooks.joined += ::show
    Hooks.left += ::quit
    Hooks.pickup += ::pickedUp
    Hooks.died += { player, _ -> died(player) }
    Hooks.advancement += { player, _ -> poll(player) }
    ticker = scheduler.every(20L, 20L) { server.playerList.players.forEach(::poll) }
    server.playerList.players.forEach(::show)
  }

  fun stop() {
    ticker?.close()
    bars.values.forEach(ServerBossEvent::removeAllPlayers)
    bars.clear()
  }

  /** Validates a goal from the assistant's arguments and starts tracking it for the player. */
  fun add(player: ServerPlayer, spec: Goal): Goal {
    val book = of(player.uuid)
    val start = baseline(player, spec)
    // A place's bar fills with the distance covered, so its target is the distance at the start.
    val target = if (spec.kind == "reach") max(1, start) else spec.target
    val goal = book.add { id -> spec.copy(id = id, baseline = start, target = target, createdAt = System.currentTimeMillis()) }
    save(player.uuid)
    // The player may already be there, or at that level.
    poll(player)
    show(player)
    return book.all().firstOrNull { it.id == goal.id } ?: goal
  }

  fun remove(player: ServerPlayer, id: String?): Int {
    val book = of(player.uuid)
    val removed = if (id == null) book.all().map { it.id }.also { book.clear() } else listOfNotNull(book.remove(id)?.id)
    removed.forEach { bars.remove(key(player.uuid, it))?.removeAllPlayers() }
    if (removed.isNotEmpty()) save(player.uuid)
    return removed.size
  }

  /** What the bar says, in the player's language: the title, then where the player stands. */
  fun label(goal: Goal, lang: Lang): String = when (goal.kind) {
    "reach" -> lang.text("goals.bar_distance", "title" to goal.title, "blocks" to max(0, goal.target - goal.progress))
    "advancement", "biome", "dimension" -> goal.title
    else -> lang.text("goals.bar_count", "title" to goal.title, "progress" to goal.progress / goal.scale, "target" to goal.target / goal.scale)
  }

  /** The bar a player sees for a goal, while they play. */
  fun bar(uuid: UUID, id: String): BossEvent? = bars[key(uuid, id)]

  private fun baseline(player: ServerPlayer, goal: Goal): Long = when (goal.kind) {
    "stat" -> statistic(player, goal)
    "reach" -> distance(player, goal)?.toLong() ?: 0
    else -> 0
  }

  private fun statistic(player: ServerPlayer, goal: Goal): Long {
    val stat = runCatching { Statistics.stat(goal.statistic!!, goal.material, goal.entity) }.getOrNull() ?: return 0
    return player.stats.getValue(stat).toLong()
  }

  private fun distance(player: ServerPlayer, goal: Goal): Double? {
    if (goal.dimension != null && Dimensions.id(player.level()) != Dimensions.normalize(goal.dimension)) return null
    return hypot(player.x - goal.x!!, player.z - goal.z!!)
  }

  /** Reads every goal that follows the player's state rather than events. */
  private fun poll(player: ServerPlayer) {
    val book = books[player.uuid] ?: return
    for (goal in book.all()) {
      val value = when (goal.kind) {
        "stat" -> statistic(player, goal) - goal.baseline
        "level" -> player.experienceLevel.toLong()
        "advancement" -> {
          val advancement = goal.advancement?.let(Identifier::tryParse)?.let { player.level().server.advancements.get(it) }
          if (advancement != null && player.advancements.getOrStartProgress(advancement).isDone) 1 else 0
        }
        "reach" -> distance(player, goal)?.let { d -> if (d <= (goal.radius ?: 10)) goal.target else (goal.baseline - d.toLong()).coerceIn(0, goal.target - 1) } ?: goal.progress
        "biome" -> if (player.level().getBiome(player.blockPosition()).unwrapKey().map { it.identifier().path }.orElse(null) == goal.biome) 1 else 0
        "dimension" -> if (Dimensions.id(player.level()) == Dimensions.normalize(goal.dimension)) 1 else 0
        else -> continue
      }
      book.progress(goal.id, value)?.let { update(player, it) }
    }
  }

  private fun update(player: ServerPlayer, goal: Goal) {
    if (goal.done) return complete(player, goal)
    val bar = bars[key(player.uuid, goal.id)] ?: return show(player)
    bar.name = Texts.legacy(Legacy.color(label(goal, locales.of(player))))
    bar.progress = goal.fraction.toFloat()
  }

  private fun complete(player: ServerPlayer, goal: Goal) {
    of(player.uuid).remove(goal.id)
    save(player.uuid)
    bars.remove(key(player.uuid, goal.id))?.removeAllPlayers()
    Sounds.play(player, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f)
    val text = locales.of(player).text("goals.done", "title" to goal.title)
    if (text.isNotEmpty()) player.sendSystemMessage(Texts.legacy(Legacy.color(text)))
  }

  private fun show(player: ServerPlayer) {
    val book = books[player.uuid] ?: of(player.uuid)
    for (goal in book.all()) {
      bars.computeIfAbsent(key(player.uuid, goal.id)) {
        ServerBossEvent(UUID.randomUUID(), Texts.legacy(Legacy.color(label(goal, locales.of(player)))), color(goal.color), overlay(goal.style))
      }.apply {
        progress = goal.fraction.toFloat()
        // A respawn makes a new ServerPlayer: the bar follows the one that plays now.
        players.filter { it.uuid == player.uuid && it !== player }.forEach(::removePlayer)
        if (player !in players) addPlayer(player)
      }
    }
  }

  private fun quit(player: ServerPlayer) {
    val uuid = player.uuid
    // Progress counted by the ticks goes out with the rest of the player's state.
    save(uuid)
    bars.keys.filter { it.startsWith("$uuid:") }.forEach { bars.remove(it)?.removeAllPlayers() }
    books.remove(uuid)
  }

  private fun pickedUp(player: ServerPlayer, item: ItemEntity, stack: ItemStack, count: Int) {
    // A player's own throws come back without counting; so do items from their death, through the book.
    if (count <= 0 || item.owner?.uuid == player.uuid) return
    val book = books[player.uuid] ?: return
    val moved = book.pickedUp(Recipes.id(stack.item), count)
    if (moved.isEmpty()) return
    save(player.uuid)
    moved.forEach { update(player, it) }
  }

  /** What the player drops on death, as Bukkit's death event lists it: nothing with keepInventory, never what vanishes. */
  private fun died(player: ServerPlayer) {
    val book = books[player.uuid] ?: return
    if (player.level().gameRules.get(GameRules.KEEP_INVENTORY)) return
    val inventory = player.inventory
    val drops = (0 until inventory.containerSize).map(inventory::getItem)
      .filter { !it.isEmpty && !EnchantmentHelper.has(it, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP) }
    book.died(drops.groupBy { Recipes.id(it.item) }.mapValues { (_, stacks) -> stacks.sumOf { it.count } })
  }

  /** Erasure, on the server thread: the player's goals and their bars go. */
  fun forget(uuid: UUID) {
    books.remove(uuid)
    bars.keys.filter { it.startsWith("$uuid:") }.forEach { bars.remove(it)?.removeAllPlayers() }
  }

  fun save(uuid: UUID) {
    val book = books[uuid] ?: return
    data.update(uuid) { it.copy(goals = book.all().takeIf(List<Goal>::isNotEmpty)) }
  }

  private fun key(uuid: UUID, id: String) = "$uuid:$id"

  companion object {
    /** Bukkit's bar colors, which are the game's, in the same order. */
    val COLORS: List<String> = BossEvent.BossBarColor.entries.map { it.name.lowercase() }

    /** Bukkit's bar styles; the game calls them overlays and keeps the same order. */
    val STYLES = listOf("solid", "segmented_6", "segmented_10", "segmented_12", "segmented_20")

    fun color(name: String): BossEvent.BossBarColor = BossEvent.BossBarColor.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: BossEvent.BossBarColor.GREEN

    fun overlay(name: String): BossEvent.BossBarOverlay = BossEvent.BossBarOverlay.entries.getOrNull(STYLES.indexOf(name.lowercase())) ?: BossEvent.BossBarOverlay.PROGRESS

    fun entityType(name: String?): EntityType<*>? =
      name?.lowercase()?.removePrefix("minecraft:")?.let { Identifier.tryBuild(Identifier.DEFAULT_NAMESPACE, it) }?.let { BuiltInRegistries.ENTITY_TYPE.getOptional(it).orElse(null) }
  }
}

/**
 * Bukkit's statistic names (`MINE_BLOCK`, `WALK_ONE_CM`, `SNEAK_TIME`) as the game's statistics, paired as
 * CraftBukkit's `CraftStatistic` pairs them, so a goal saved on Bukkit counts the same here. Typed ones take a block,
 * an item, or a mob.
 */
object Statistics {
  enum class Kind { UNTYPED, BLOCK, ITEM, ENTITY }

  private val typed: Map<String, Pair<Kind, StatType<*>>> = mapOf(
    "MINE_BLOCK" to (Kind.BLOCK to Stats.BLOCK_MINED),
    "BREAK_ITEM" to (Kind.ITEM to Stats.ITEM_BROKEN),
    "CRAFT_ITEM" to (Kind.ITEM to Stats.ITEM_CRAFTED),
    "DROP" to (Kind.ITEM to Stats.ITEM_DROPPED),
    "PICKUP" to (Kind.ITEM to Stats.ITEM_PICKED_UP),
    "USE_ITEM" to (Kind.ITEM to Stats.ITEM_USED),
    "KILL_ENTITY" to (Kind.ENTITY to Stats.ENTITY_KILLED),
    "ENTITY_KILLED_BY" to (Kind.ENTITY to Stats.ENTITY_KILLED_BY),
  )

  private val custom: Map<String, Identifier> = mapOf(
    "ANIMALS_BRED" to Stats.ANIMALS_BRED, "AVIATE_ONE_CM" to Stats.AVIATE_ONE_CM, "BELL_RING" to Stats.BELL_RING,
    "BOAT_ONE_CM" to Stats.BOAT_ONE_CM, "ARMOR_CLEANED" to Stats.CLEAN_ARMOR, "BANNER_CLEANED" to Stats.CLEAN_BANNER,
    "CLEAN_SHULKER_BOX" to Stats.CLEAN_SHULKER_BOX, "CLIMB_ONE_CM" to Stats.CLIMB_ONE_CM, "CROUCH_ONE_CM" to Stats.CROUCH_ONE_CM,
    "DAMAGE_ABSORBED" to Stats.DAMAGE_ABSORBED, "DAMAGE_BLOCKED_BY_SHIELD" to Stats.DAMAGE_BLOCKED_BY_SHIELD, "DAMAGE_DEALT" to Stats.DAMAGE_DEALT,
    "DAMAGE_DEALT_ABSORBED" to Stats.DAMAGE_DEALT_ABSORBED, "DAMAGE_DEALT_RESISTED" to Stats.DAMAGE_DEALT_RESISTED, "DAMAGE_RESISTED" to Stats.DAMAGE_RESISTED,
    "DAMAGE_TAKEN" to Stats.DAMAGE_TAKEN, "DEATHS" to Stats.DEATHS, "DROP_COUNT" to Stats.DROP,
    "CAKE_SLICES_EATEN" to Stats.EAT_CAKE_SLICE, "ITEM_ENCHANTED" to Stats.ENCHANT_ITEM, "FALL_ONE_CM" to Stats.FALL_ONE_CM,
    "CAULDRON_FILLED" to Stats.FILL_CAULDRON, "FISH_CAUGHT" to Stats.FISH_CAUGHT, "FLY_ONE_CM" to Stats.FLY_ONE_CM,
    "HAPPY_GHAST_ONE_CM" to Stats.HAPPY_GHAST_ONE_CM, "HORSE_ONE_CM" to Stats.HORSE_ONE_CM, "DISPENSER_INSPECTED" to Stats.INSPECT_DISPENSER,
    "DROPPER_INSPECTED" to Stats.INSPECT_DROPPER, "HOPPER_INSPECTED" to Stats.INSPECT_HOPPER, "INTERACT_WITH_ANVIL" to Stats.INTERACT_WITH_ANVIL,
    "BEACON_INTERACTION" to Stats.INTERACT_WITH_BEACON, "INTERACT_WITH_BLAST_FURNACE" to Stats.INTERACT_WITH_BLAST_FURNACE,
    "BREWINGSTAND_INTERACTION" to Stats.INTERACT_WITH_BREWINGSTAND, "INTERACT_WITH_CAMPFIRE" to Stats.INTERACT_WITH_CAMPFIRE,
    "INTERACT_WITH_CARTOGRAPHY_TABLE" to Stats.INTERACT_WITH_CARTOGRAPHY_TABLE, "CRAFTING_TABLE_INTERACTION" to Stats.INTERACT_WITH_CRAFTING_TABLE,
    "FURNACE_INTERACTION" to Stats.INTERACT_WITH_FURNACE, "INTERACT_WITH_GRINDSTONE" to Stats.INTERACT_WITH_GRINDSTONE,
    "INTERACT_WITH_LECTERN" to Stats.INTERACT_WITH_LECTERN, "INTERACT_WITH_LOOM" to Stats.INTERACT_WITH_LOOM,
    "INTERACT_WITH_SMITHING_TABLE" to Stats.INTERACT_WITH_SMITHING_TABLE, "INTERACT_WITH_SMOKER" to Stats.INTERACT_WITH_SMOKER,
    "INTERACT_WITH_STONECUTTER" to Stats.INTERACT_WITH_STONECUTTER, "JUMP" to Stats.JUMP, "LEAVE_GAME" to Stats.LEAVE_GAME,
    "MINECART_ONE_CM" to Stats.MINECART_ONE_CM, "MOB_KILLS" to Stats.MOB_KILLS, "NAUTILUS_ONE_CM" to Stats.NAUTILUS_ONE_CM,
    "OPEN_BARREL" to Stats.OPEN_BARREL, "CHEST_OPENED" to Stats.OPEN_CHEST, "ENDERCHEST_OPENED" to Stats.OPEN_ENDERCHEST,
    "SHULKER_BOX_OPENED" to Stats.OPEN_SHULKER_BOX, "PIG_ONE_CM" to Stats.PIG_ONE_CM, "NOTEBLOCK_PLAYED" to Stats.PLAY_NOTEBLOCK,
    "RECORD_PLAYED" to Stats.PLAY_RECORD, "PLAY_ONE_MINUTE" to Stats.PLAY_TIME, "PLAYER_KILLS" to Stats.PLAYER_KILLS,
    "FLOWER_POTTED" to Stats.POT_FLOWER, "RAID_TRIGGER" to Stats.RAID_TRIGGER, "RAID_WIN" to Stats.RAID_WIN,
    "SLEEP_IN_BED" to Stats.SLEEP_IN_BED, "SLEEP_IN_STRAW_BED" to Stats.SLEEP_IN_STRAW_BED, "SNEAK_TIME" to Stats.CROUCH_TIME,
    "SPRINT_ONE_CM" to Stats.SPRINT_ONE_CM, "STRIDER_ONE_CM" to Stats.STRIDER_ONE_CM, "SWIM_ONE_CM" to Stats.SWIM_ONE_CM,
    "TALKED_TO_VILLAGER" to Stats.TALKED_TO_VILLAGER, "TARGET_HIT" to Stats.TARGET_HIT, "TIME_SINCE_DEATH" to Stats.TIME_SINCE_DEATH,
    "TIME_SINCE_REST" to Stats.TIME_SINCE_REST, "TOTAL_WORLD_TIME" to Stats.TOTAL_WORLD_TIME, "TRADED_WITH_VILLAGER" to Stats.TRADED_WITH_VILLAGER,
    "TRAPPED_CHEST_TRIGGERED" to Stats.TRIGGER_TRAPPED_CHEST, "NOTEBLOCK_TUNED" to Stats.TUNE_NOTEBLOCK, "CAULDRON_USED" to Stats.USE_CAULDRON,
    "WALK_ON_WATER_ONE_CM" to Stats.WALK_ON_WATER_ONE_CM, "WALK_ONE_CM" to Stats.WALK_ONE_CM, "WALK_UNDER_WATER_ONE_CM" to Stats.WALK_UNDER_WATER_ONE_CM,
  )

  /** What a Bukkit name takes besides itself; null when Bukkit has no statistic by that name. */
  fun kind(name: String): Kind? {
    val wanted = name.uppercase()
    return typed[wanted]?.first ?: if (wanted in custom) Kind.UNTYPED else null
  }

  /** The block an item stands for, when Bukkit would call it a block too: the same id, like `diamond_ore`, not `redstone`. */
  fun block(item: Item): Block? = (item as? BlockItem)?.block?.takeIf { BuiltInRegistries.BLOCK.getKey(it) == BuiltInRegistries.ITEM.getKey(item) }

  /** The game's statistic for a goal's names; throws when they don't make one. */
  @Suppress("UNCHECKED_CAST")
  fun stat(name: String, material: String?, entity: String?): Stat<*> {
    val wanted = name.uppercase()
    custom[wanted]?.let { return Stats.CUSTOM.get(it) }
    val (kind, type) = typed.getValue(wanted)
    return when (kind) {
      Kind.BLOCK -> (type as StatType<Block>).get(block(item(material!!))!!)
      Kind.ITEM -> (type as StatType<Item>).get(item(material!!))
      else -> (type as StatType<EntityType<*>>).get(Goals.entityType(entity)!!)
    }
  }

  private fun item(id: String): Item = InteractTools.known(id) ?: error("no item $id")
}
