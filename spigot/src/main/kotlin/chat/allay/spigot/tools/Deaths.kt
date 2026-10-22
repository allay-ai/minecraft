package chat.allay.spigot.tools

import chat.allay.core.*
import chat.allay.core.tools.*

import chat.allay.spigot.Vanish
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent

/** Writes every death into the player's [DeathLog], so `get_death_info` can tell when, where, and how, across restarts. */
class Deaths(val log: DeathLog, private val warn: (String) -> Unit) : Listener {
  @EventHandler(priority = EventPriority.MONITOR)
  fun onDeath(event: PlayerDeathEvent) {
    val player = event.entity
    val place = player.location
    val world = place.world ?: return
    val source = event.damageSource
    val killer = source.causingEntity
    // A player killer by name only when the victim could see them; a mob by the name a player gave it.
    val killerName = when (killer) {
      null -> null
      is Player -> killer.name.takeIf { Vanish.visible(player, killer) }
      else -> killer.customName
    }
    val drops = event.drops.groupBy { it.type.key.key }.mapValues { (_, stacks) -> stacks.sumOf { it.amount } }
      .entries.sortedByDescending { it.value }.take(MAX_DROPS).associate { it.key to it.value }
    val death = Death(
      at = System.currentTimeMillis(),
      world = world.name,
      dimension = Dimensions.id(world),
      x = place.blockX,
      y = place.blockY,
      z = place.blockZ,
      cause = source.damageType.key.toString(),
      killer = killer?.type?.key?.toString(),
      killerName = killerName,
      message = event.deathMessage,
      level = player.level,
      keptInventory = event.keepInventory,
      drops = drops,
      experience = event.droppedExp,
    )
    // A small file once a death: cheap enough for the server thread, and no death is lost to a crash right after.
    runCatching { log.record(player.uniqueId, death) }.onFailure { warn("Couldn't save a death: ${it.message}") }
  }

  companion object {
    /** Items on the ground vanish after 5 minutes, counted only while their chunk is loaded. */
    const val DESPAWN_TICKS = 6_000
    private const val MAX_DROPS = 20
  }
}
