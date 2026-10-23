package chat.allay.core

import java.util.UUID

/**
 * The admin's spending limits ([QuotaSettings]) and models by tier ([ModelTiers]): a question or an agent's wake starts
 * only when the player and the server have money left of what's unlocked so far. A broken quota config stops Allay for
 * everyone: admins hear why, on join too, and players hear to ask an admin.
 */
class QuotaGate(private val platform: Platform, private val locales: Locales, private val config: () -> Config, private val book: QuotaBook) {
  /** Whether [sender] may ask now; when not, they've heard why. */
  fun allows(sender: Audience): Boolean {
    val settings = config().quota
    val lang = locales.of(sender)
    return when (val verdict = Quotas.check(settings, book, sender.uuid, sender.operator) { sender.can(Permissions.quota(it)) }) {
      Quotas.Verdict.Allowed -> true
      is Quotas.Verdict.Misconfigured -> {
        if (sender.admin) lang.send(sender, "quota.misconfigured_admin", "reason" to verdict.reason) else lang.send(sender, "quota.misconfigured")
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
  fun model(sender: Audience): String? = config().models.of(sender.operator) { sender.can(Permissions.model(it)) }

  /** Counts what a run cost; nothing when quotas are off. */
  fun spent(player: UUID?, usd: Double) {
    if (config().quota.enabled && usd > 0) book.add(player, usd)
  }

  /** One line in the log at start and reload when the quota config is wrong. */
  fun report() {
    config().quota.error?.let { platform.log.severe("quota in config.yml is wrong: $it. Allay answers nobody until it's fixed") }
  }

  fun joined(player: PlayerAudience) {
    val reason = config().quota.error ?: return
    if (!player.admin) return
    platform.scheduler.later(40L) {
      if (player.online) locales.of(player).send(player, "quota.misconfigured_admin", "reason" to reason)
    }
  }
}
