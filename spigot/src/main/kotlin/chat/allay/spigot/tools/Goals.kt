package chat.allay.spigot.tools

import chat.allay.core.*
import chat.allay.core.tools.*
import chat.allay.spigot.send

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.Sound
import org.bukkit.Statistic
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerAdvancementDoneEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import chat.allay.spigot.Minecraft.formatMessage
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.hypot
import kotlin.math.max

/**
 * Goals, their boss bars, and the events and ticks that move them. The goals are part of each player's state on the
 * backend ([PlayerData]); while the player plays, the book here is the live copy.
 */
class Goals(
  private val plugin: Plugin,
  private val locales: chat.allay.spigot.Locales,
  private val data: PlayerData,
  private val max: () -> Int,
) : Listener {
  private val books = ConcurrentHashMap<UUID, GoalBook>()
  private val bars = ConcurrentHashMap<String, BossBar>()
  private var ticker: BukkitTask? = null

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
    plugin.server.scheduler.runTask(plugin, Runnable {
      books[uuid] = GoalBook(state.goals.orEmpty(), max)
      bars.keys.filter { it.startsWith("$uuid:") }.forEach { bars.remove(it)?.removeAll() }
      plugin.server.getPlayer(uuid)?.let(::show)
    })
  }

  fun start() {
    ticker = plugin.server.scheduler.runTaskTimer(plugin, Runnable { plugin.server.onlinePlayers.forEach(::poll) }, 20L, 20L)
    plugin.server.onlinePlayers.forEach(::show)
  }

  fun stop() {
    ticker?.cancel()
    bars.values.forEach(BossBar::removeAll)
    bars.clear()
  }

  /** Validates a goal from the assistant's arguments and starts tracking it for the player. */
  fun add(player: Player, spec: Goal): Goal {
    val book = of(player.uniqueId)
    val start = baseline(player, spec)
    // A place's bar fills with the distance covered, so its target is the distance at the start.
    val target = if (spec.kind == "reach") max(1, start) else spec.target
    val goal = book.add { id -> spec.copy(id = id, baseline = start, target = target, createdAt = System.currentTimeMillis()) }
    save(player.uniqueId)
    // The player may already be there, or at that level.
    poll(player)
    show(player)
    return book.all().firstOrNull { it.id == goal.id } ?: goal
  }

  fun remove(player: Player, id: String?): Int {
    val book = of(player.uniqueId)
    val removed = if (id == null) book.all().map { it.id }.also { book.clear() } else listOfNotNull(book.remove(id)?.id)
    removed.forEach { bars.remove(key(player.uniqueId, it))?.removeAll() }
    if (removed.isNotEmpty()) save(player.uniqueId)
    return removed.size
  }

  /** What the bar says, in the player's language: the title, then where the player stands. */
  fun label(goal: Goal, lang: chat.allay.core.Lang): String = when (goal.kind) {
    "reach" -> lang.text("goals.bar_distance", "title" to goal.title, "blocks" to max(0, goal.target - goal.progress))
    "advancement", "biome", "dimension" -> goal.title
    else -> lang.text("goals.bar_count", "title" to goal.title, "progress" to goal.progress / goal.scale, "target" to goal.target / goal.scale)
  }

  private fun baseline(player: Player, goal: Goal): Long = when (goal.kind) {
    "stat" -> statistic(player, goal)
    "reach" -> distance(player, goal)?.toLong() ?: 0
    else -> 0
  }

  private fun statistic(player: Player, goal: Goal): Long {
    val statistic = runCatching { Statistic.valueOf(goal.statistic!!.uppercase()) }.getOrNull() ?: return 0
    return runCatching {
      when (statistic.type) {
        Statistic.Type.UNTYPED -> player.getStatistic(statistic)
        Statistic.Type.ENTITY -> player.getStatistic(statistic, entityType(goal.entity)!!)
        else -> player.getStatistic(statistic, Material.matchMaterial(goal.material!!)!!)
      }.toLong()
    }.getOrDefault(0)
  }

  private fun distance(player: Player, goal: Goal): Double? {
    if (goal.dimension != null && Dimensions.id(player.world) != Dimensions.normalize(goal.dimension)) return null
    return hypot(player.location.x - goal.x!!, player.location.z - goal.z!!)
  }

  /** Reads every goal that follows the player's state rather than events. */
  private fun poll(player: Player) {
    val book = books[player.uniqueId] ?: return
    for (goal in book.all()) {
      val value = when (goal.kind) {
        "stat" -> statistic(player, goal) - goal.baseline
        "level" -> player.level.toLong()
        "advancement" -> {
          val advancement = goal.advancement?.let(NamespacedKey::fromString)?.let(Bukkit::getAdvancement)
          if (advancement != null && player.getAdvancementProgress(advancement).isDone) 1 else 0
        }
        "reach" -> distance(player, goal)?.let { d -> if (d <= (goal.radius ?: 10)) goal.target else (goal.baseline - d.toLong()).coerceIn(0, goal.target - 1) } ?: goal.progress
        "biome" -> if (player.location.block.biome.key.key == goal.biome) 1 else 0
        "dimension" -> if (Dimensions.id(player.world) == Dimensions.normalize(goal.dimension)) 1 else 0
        else -> continue
      }
      book.progress(goal.id, value)?.let { update(player, it) }
    }
  }

  private fun update(player: Player, goal: Goal) {
    if (goal.done) return complete(player, goal)
    val bar = bars[key(player.uniqueId, goal.id)] ?: return show(player)
    bar.setTitle(formatMessage(label(goal, locales.of(player))))
    bar.progress = goal.fraction
  }

  private fun complete(player: Player, goal: Goal) {
    of(player.uniqueId).remove(goal.id)
    save(player.uniqueId)
    bars.remove(key(player.uniqueId, goal.id))?.removeAll()
    player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f)
    locales.of(player).send(player, "goals.done", "title" to goal.title)
  }

  private fun show(player: Player) {
    val book = books[player.uniqueId] ?: of(player.uniqueId)
    for (goal in book.all()) {
      bars.computeIfAbsent(key(player.uniqueId, goal.id)) {
        val color = runCatching { BarColor.valueOf(goal.color.uppercase()) }.getOrDefault(BarColor.GREEN)
        val style = runCatching { BarStyle.valueOf(goal.style.uppercase()) }.getOrDefault(BarStyle.SOLID)
        Bukkit.createBossBar(formatMessage(label(goal, locales.of(player))), color, style)
      }.apply {
        progress = goal.fraction
        if (player !in players) addPlayer(player)
      }
    }
  }

  @EventHandler fun onJoin(event: PlayerJoinEvent) = show(event.player)

  @EventHandler fun onQuit(event: PlayerQuitEvent) {
    val uuid = event.player.uniqueId
    // Progress counted by the ticks goes out with the rest of the player's state.
    save(uuid)
    bars.keys.filter { it.startsWith("$uuid:") }.forEach { bars.remove(it)?.removeAll() }
    books.remove(uuid)
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  fun onPickup(event: EntityPickupItemEvent) {
    val player = event.entity as? Player ?: return
    val stack = event.item.itemStack
    val count = stack.amount - event.remaining
    // A player's own throws come back without counting; so do items from their death, through the book.
    if (count <= 0 || event.item.thrower == player.uniqueId) return
    val book = books[player.uniqueId] ?: return
    val moved = book.pickedUp(stack.type.key.key, count)
    if (moved.isEmpty()) return
    save(player.uniqueId)
    moved.forEach { update(player, it) }
  }

  @EventHandler(priority = EventPriority.MONITOR)
  fun onDeath(event: PlayerDeathEvent) {
    val book = books[event.entity.uniqueId] ?: return
    book.died(event.drops.groupBy { it.type.key.key }.mapValues { (_, stacks) -> stacks.sumOf { it.amount } })
  }

  @EventHandler fun onAdvancement(event: PlayerAdvancementDoneEvent) = poll(event.player)

  /** Erasure, on the server thread: the player's goals and their bars go. */
  fun forget(uuid: UUID) {
    books.remove(uuid)
    bars.keys.filter { it.startsWith("$uuid:") }.forEach { bars.remove(it)?.removeAll() }
  }

  fun save(uuid: UUID) {
    val book = books[uuid] ?: return
    data.update(uuid) { it.copy(goals = book.all().takeIf(List<Goal>::isNotEmpty)) }
  }

  private fun key(uuid: UUID, id: String) = "$uuid:$id"

  companion object {
    fun entityType(name: String?): EntityType? = name?.lowercase()?.removePrefix("minecraft:")?.let { Registry.ENTITY_TYPE.get(NamespacedKey.minecraft(it)) }
  }
}
