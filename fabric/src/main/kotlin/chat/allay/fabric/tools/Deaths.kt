package chat.allay.fabric.tools

import chat.allay.core.PlayerData
import chat.allay.core.tools.Death
import chat.allay.core.tools.Dimensions
import chat.allay.fabric.Hooks
import chat.allay.fabric.Texts
import chat.allay.fabric.Vanish

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.EntityType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.level.gamerules.GameRules
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Sends every death to the backend (6.9), which keeps the history: when, where, and how. The mod keeps only each
 * player's last death of this run in memory, so `get_death_info` can say what dropped and whether it still lies there.
 * The death is read as the player starts to die ([Hooks.died]), with everything still on them, and told as Bukkit's
 * `PlayerDeathEvent` tells it.
 */
class Deaths(private val data: PlayerData) {
  private val last = ConcurrentHashMap<UUID, Death>()

  /** The player's last death since the server started, or null. */
  fun latest(uuid: UUID): Death? = last[uuid]

  fun start() {
    Hooks.died += ::died
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
    val keepInventory = level.gameRules.get(GameRules.KEEP_INVENTORY)
    val drops = drops(player, keepInventory).groupBy { Recipes.id(it.item) }.mapValues { (_, stacks) -> stacks.sumOf { it.count } }
      .entries.sortedByDescending { it.value }.take(MAX_DROPS).associate { it.key to it.value }
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
      drops = drops.takeIf { it.isNotEmpty() },
      experience = experience.takeIf { it > 0 },
    )
    last[player.uuid] = death
    data.died(player.uuid, death)
  }

  /**
   * What the player is about to drop, as the game drops it: the whole inventory, armor and off hand included, unless
   * `keep_inventory` keeps it or they watch as a spectator; items with Curse of Vanishing vanish instead.
   */
  private fun drops(player: ServerPlayer, keepInventory: Boolean): List<ItemStack> {
    if (keepInventory || player.isSpectator) return emptyList()
    val inventory = player.inventory
    return (0 until inventory.containerSize).map(inventory::getItem)
      .filter { !it.isEmpty && !EnchantmentHelper.has(it, EnchantmentEffectComponents.PREVENT_EQUIPMENT_DROP) }
  }

  /** Erasure: the last death goes from memory too. */
  fun forget(uuid: UUID) {
    last.remove(uuid)
  }

  companion object {
    /** Items on the ground vanish after 5 minutes, counted only while their chunk is loaded. */
    const val DESPAWN_TICKS = 6_000
    private const val MAX_DROPS = 20
  }
}
