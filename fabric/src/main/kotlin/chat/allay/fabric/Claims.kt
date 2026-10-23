package chat.allay.fabric

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.RandomizableContainer
import net.minecraft.world.level.Level
import org.slf4j.LoggerFactory

/**
 * Which containers Allay may tell a player about: the ones they could open themself. Claims come from any mod behind
 * Patbox's Common Protection API (GOML, Flan, and others): a container in a claim needs the right to use blocks there.
 * An unopened structure chest (its loot table still waits) stays hidden from everyone, as on Paper. Without a claims
 * mod, every container but those is open. Runs on the server thread, where claims live.
 */
object Claims {
  val available: Boolean by lazy { FabricLoader.getInstance().isModLoaded("common-protection-api") }

  private var warned = false

  fun mayOpen(player: ServerPlayer, level: Level, pos: BlockPos): Boolean {
    if ((level.getBlockEntity(pos) as? RandomizableContainer)?.lootTable != null) return false
    return ask { Api.interact(player, level, pos) }
  }

  /**
   * Whether the player could build at [pos]: outside any claim, or in one where they may build, and outside the
   * spawn protection the game keeps from non-operators. Allay reads redstone and compares drafts only there, so nobody
   * takes apart a neighbor's machine.
   */
  fun mayBuild(player: ServerPlayer, level: Level, pos: BlockPos): Boolean {
    if (level is ServerLevel && level.server.isUnderSpawnProtection(level, pos, player)) return false
    return ask { Api.build(player, level, pos) }
  }

  /** Whether [pos] lies in a claim; false without a claims mod. */
  fun claimed(level: Level, pos: BlockPos): Boolean = available && runCatching { Api.claimed(level, pos) }.getOrDefault(false)

  private fun ask(check: () -> Boolean): Boolean {
    if (!available) return true
    return try {
      check()
    } catch (error: Throwable) {
      // An API that moved: closed is the safe side, and one line in the log says why.
      if (!warned) {
        warned = true
        LoggerFactory.getLogger("Allay").warn("Couldn't ask the claims mod about a claim, so Allay treats claims as closed", error)
      }
      false
    }
  }

  // Only touched when the API is loaded; its classes aren't there otherwise.
  private object Api {
    fun claimed(level: Level, pos: BlockPos) = eu.pb4.common.protection.api.CommonProtection.isProtected(level, pos)
    fun interact(player: ServerPlayer, level: Level, pos: BlockPos) = eu.pb4.common.protection.api.CommonProtection.canInteractBlock(level, pos, player.nameAndId(), player)
    fun build(player: ServerPlayer, level: Level, pos: BlockPos) =
      eu.pb4.common.protection.api.CommonProtection.canPlaceBlock(level, pos, player.nameAndId(), player) &&
        eu.pb4.common.protection.api.CommonProtection.canBreakBlock(level, pos, player.nameAndId(), player)
  }
}
