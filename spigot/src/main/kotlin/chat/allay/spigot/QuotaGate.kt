package chat.allay.spigot

import chat.allay.core.*

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

/**
 * The admin's spending limits ([QuotaSettings]) and models by tier ([ModelTiers]) on Bukkit: a question or an agent's
 * wake starts only when the player and the server have money left of what's unlocked so far. A broken quota config
 * stops Allay for everyone: admins hear why, on join too, and players hear to ask an admin.
 */
class QuotaGate(private val plugin: JavaPlugin, private val locales: Locales, private val config: () -> Config, private val book: QuotaBook) : Listener {
  private fun admin(sender: CommandSender) = sender.isOp || sender.hasPermission(Permissions.ADMIN)

  /** Whether [sender] may ask now; when not, they've heard why. */
  fun allows(sender: CommandSender): Boolean {
    val player = sender as? Player
    val settings = config().quota
    val lang = locales.of(sender)
    return when (val verdict = Quotas.check(settings, book, player?.uniqueId, sender.isOp) { sender.hasPermission(Permissions.quota(it)) }) {
      Quotas.Verdict.Allowed -> true
      is Quotas.Verdict.Misconfigured -> {
        if (admin(sender)) lang.send(sender, "quota.misconfigured_admin", "reason" to verdict.reason) else lang.send(sender, "quota.misconfigured")
        false
      }
      Quotas.Verdict.NoTier -> {
        lang.send(sender, "quota.no_tier")
        false
      }
      Quotas.Verdict.PlayerSpent -> {
        lang.send(sender, "quota.player_spent", "date" to book.resets(settings.daily))
        false
      }
      Quotas.Verdict.ServerSpent -> {
        lang.send(sender, "quota.server_spent", "date" to book.resets(settings.daily))
        false
      }
    }
  }

  /** The model [sender]'s tier picks, or null for the default. */
  fun model(sender: CommandSender): String? = config().models.of(sender.isOp) { sender.hasPermission(Permissions.model(it)) }

  /** Counts what a run cost; nothing when quotas are off. */
  fun spent(player: UUID?, usd: Double) {
    if (config().quota.enabled && usd > 0) book.add(player, usd)
  }

  /** One line in the log at start and reload when the quota config is wrong. */
  fun report() {
    config().quota.error?.let { plugin.logger.severe("quota in config.yml is wrong: $it. Allay answers nobody until it's fixed") }
  }

  @EventHandler fun onJoin(event: PlayerJoinEvent) {
    val reason = config().quota.error ?: return
    if (!admin(event.player)) return
    plugin.server.scheduler.runTaskLater(plugin, Runnable {
      if (event.player.isOnline) locales.of(event.player).send(event.player, "quota.misconfigured_admin", "reason" to reason)
    }, 40L)
  }
}
