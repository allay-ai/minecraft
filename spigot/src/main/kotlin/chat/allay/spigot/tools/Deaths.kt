package chat.allay.spigot.tools

import chat.allay.core.*
import chat.allay.core.tools.*

import chat.allay.spigot.Vanish
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Sends every death to the backend (6.9), which keeps the history: when, where, and how. The plugin keeps only each
 * player's last death of this run in memory, so `get_death_info` can say what dropped and whether it still lies there.
 */
class Deaths(private val data: PlayerData, private val consented: (UUID) -> Boolean) : Listener {
  private val last = ConcurrentHashMap<UUID, Death>()

  /** The player's last death since the server started, or null. */
  fun latest(uuid: UUID): Death? = last[uuid]

  @EventHandler(priority = EventPriority.MONITOR)
  fun onDeath(event: PlayerDeathEvent) {
    val player = event.entity
    val place = player.location
    val world = place.world ?: return
    val source = event.damageSource
    val killer = source.causingEntity
    // Another player by name only when they agreed here and the victim could see them; a mob by the name a player gave it.
    fun named(other: Player) = consented(other.uniqueId) && Vanish.visible(player, other)
    val killerName = when (killer) {
      null -> null
      is Player -> killer.name.takeIf { named(killer) }
      else -> killer.customName
    }
    // The game's message names whoever it likes; the rest of the players stay unnamed in the history.
    val hidden = player.server.onlinePlayers.filter { it != player && !named(it) }.map { it.name }
    val piles = event.drops.groupBy { it.type.key.key }.mapValues { (_, stacks) -> stacks.sumOf { it.amount } }
    val drops = piles.entries.sortedByDescending { it.value }.take(Death.MAX_DROPS).associate { it.key to it.value }
    val death = Death(
      at = System.currentTimeMillis(),
      world = world.name,
      dimension = Dimensions.id(world),
      x = place.blockX,
      y = place.blockY,
      z = place.blockZ,
      cause = source.damageType.key.toString(),
      killer = killer?.type?.key?.toString(),
      killer_name = killerName,
      message = Death.scrub(event.deathMessage, hidden),
      level = player.level,
      kept_inventory = event.keepInventory.takeIf { it },
      drops = drops.takeIf { it.isNotEmpty() },
      experience = event.droppedExp.takeIf { it > 0 },
      more_drops = (piles.size - drops.size).takeIf { it > 0 },
    )
    last[player.uniqueId] = death
    data.died(player.uniqueId, death)
  }

  /** Erasure: the last death goes from memory too. */
  fun forget(uuid: UUID) {
    last.remove(uuid)
  }

  companion object {
    /** Items on the ground vanish after 5 minutes, counted only while their chunk is loaded. */
    const val DESPAWN_TICKS = 6_000
  }
}
