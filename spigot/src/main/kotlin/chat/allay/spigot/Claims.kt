package chat.allay.spigot

import me.ryanhamshire.GriefPrevention.ClaimPermission
import me.ryanhamshire.GriefPrevention.GriefPrevention
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.block.BlockState
import org.bukkit.entity.Player
import org.bukkit.loot.Lootable
import org.bukkit.plugin.java.JavaPlugin
import java.util.logging.Level

/**
 * Which containers Allay may tell a player about: the ones they could open themself. A container outside any
 * claim is anyone's to open; one in a claim needs container trust there, as GriefPrevention decides. An unopened
 * structure chest (its loot table still waits) stays hidden from everyone, since Paper's anti-xray hides it too.
 * Without GriefPrevention, every container but those is open. Runs on the server thread, where claims live.
 */
object Claims {
  val available: Boolean get() = Bukkit.getPluginManager().isPluginEnabled("GriefPrevention")

  private var warned = false

  fun mayOpen(player: Player, state: BlockState): Boolean {
    if ((state as? Lootable)?.lootTable != null) return false
    return ask { GriefPreventionHook.allows(player, state.location, build = false) }
  }

  /**
   * Whether the player could build at [location]: outside any claim, or in one where they may build (their own,
   * or with build trust). Allay reads redstone and compares drafts only there, so nobody takes apart a neighbor's machine.
   */
  fun mayBuild(player: Player, location: Location): Boolean = ask { GriefPreventionHook.allows(player, location, build = true) }

  private fun ask(check: () -> Boolean): Boolean {
    if (!available) return true
    return try {
      check()
    } catch (error: Throwable) {
      // A GriefPrevention whose API moved: closed is the safe side, and one line in the log says why.
      if (!warned) {
        warned = true
        JavaPlugin.getProvidingPlugin(Claims::class.java).logger.log(Level.WARNING, "Couldn't ask GriefPrevention about a claim, so Allay treats claims as closed", error)
      }
      false
    }
  }

  // Only touched when GriefPrevention is enabled; its classes aren't on the classpath otherwise.
  private object GriefPreventionHook {
    /** Container trust, or with [build], build trust. */
    fun allows(player: Player, location: Location, build: Boolean): Boolean {
      val plugin = GriefPrevention.instance ?: return true
      if (!plugin.claimsEnabledForWorld(location.world)) return true
      val claim = plugin.dataStore.getClaimAt(location, false, null) ?: return true
      return claim.checkPermission(player, if (build) ClaimPermission.Build else ClaimPermission.Inventory, null) == null
    }
  }
}
