package chat.allay.modcore.tools

import chat.allay.core.PlayerData
import chat.allay.core.tools.Death
import chat.allay.core.tools.Dimensions
import chat.allay.modcore.Hooks
import chat.allay.modcore.Texts
import chat.allay.modcore.Vanish

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.EntityType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.gamerules.GameRules
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Sends every death to the backend (6.9), which keeps the history: when, where, and how. The mod keeps only each
 * player's last death of this run in memory, so `get_death_info` can say what dropped and whether it still lies there.
 * The death is read as the player starts to die ([Hooks.died]), with everything still on them, and told as Bukkit's
 * `PlayerDeathEvent` tells it, once the game dropped what it drops ([Hooks.deathDrops]).
 */
class Deaths(private val data: PlayerData) {
  private val last = ConcurrentHashMap<UUID, Death>()

  /** Deaths read, waiting for their drops. Server thread only. */
  private val dying = HashMap<UUID, Death>()

  /** The player's last death since the server started, or null. */
  fun latest(uuid: UUID): Death? = last[uuid]

  fun start() {
    Hooks.died += ::died
    Hooks.deathDrops += ::dropped
  }

  private fun died(player: ServerPlayer, source: DamageSource) {
    val level = player.level()
    val pos = player.blockPosition()
    val killer = source.entity
    // A player killer by name only when the victim could see them; a mob by the name a player gave it.
    val killerName = when (killer) {
      null -> null
      is ServerPlayer -> killer.gameProfile.name.takeIf { Vanish.visible(player, killer) }
      else -> killer.customName?.let(Texts::legacyOf)
    }
    // A spectator keeps everything too, as CraftBukkit's event says.
    val keepInventory = level.gameRules.get(GameRules.KEEP_INVENTORY) || player.isSpectator
    val experience = player.getExperienceReward(level, killer)
    val death = Death(
      at = System.currentTimeMillis(),
      world = Worlds.name(level),
      dimension = Dimensions.id(level),
      x = pos.x,
      y = pos.y,
      z = pos.z,
      cause = source.typeHolder().unwrapKey().map { it.identifier().toString() }.orElse(null),
      killer = killer?.let { EntityType.getKey(it.type).toString() },
      killer_name = killerName,
      message = Texts.legacyOf(player.combatTracker.deathMessage),
      level = player.experienceLevel,
      kept_inventory = keepInventory.takeIf { it },
      drops = null,
      experience = experience.takeIf { it > 0 },
    )
    // A spectator drops nothing, and the game doesn't even try.
    if (player.isSpectator) record(player.uuid, death) else dying[player.uuid] = death
  }

  /**
   * What the game dropped for the death read last: the inventory, armor and off hand included, unless `keep_inventory`
   * kept it (Curse of Vanishing destroyed some), and what the loot table adds, as Bukkit's `event.drops`.
   */
  private fun dropped(player: ServerPlayer, items: List<ItemStack>) {
    val death = dying.remove(player.uuid) ?: return
    val drops = items.groupBy { Recipes.id(it.item) }.mapValues { (_, stacks) -> stacks.sumOf { it.count } }
      .entries.sortedByDescending { it.value }.take(MAX_DROPS).associate { it.key to it.value }
    record(player.uuid, death.copy(drops = drops.takeIf { it.isNotEmpty() }))
  }

  private fun record(uuid: UUID, death: Death) {
    last[uuid] = death
    data.died(uuid, death)
  }

  /** Erasure: the last death goes from memory too. */
  fun forget(uuid: UUID) {
    last.remove(uuid)
    dying.remove(uuid)
  }

  companion object {
    /** Items on the ground vanish after 5 minutes, counted only while their chunk is loaded. */
    const val DESPAWN_TICKS = 6_000
    private const val MAX_DROPS = 20
  }
}
