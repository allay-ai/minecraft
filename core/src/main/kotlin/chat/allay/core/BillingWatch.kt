package chat.allay.core

/**
 * Polls the subscription every 5 minutes, off the server thread, and tells operators online (and the console) about a
 * payment, a quota past 80% and 100%, and an end three days off. `subscription.json` keeps what they heard across restarts.
 */
class BillingWatch(
  private val platform: Platform,
  private val locales: Locales,
  private val billing: Billing,
  private val credentials: Credentials,
  private val announcements: Announcements,
  private val commands: BillingCommands,
) {
  private var task: AutoCloseable? = null

  /** The subscription as the last poll saw it; null with a key of the server's own or before the first poll. */
  @Volatile var status: LicenseStatus? = null; private set

  fun start() {
    stop()
    task = platform.scheduler.every(20L * 30, 20L * 60 * 5, async = true, ::poll)
  }

  fun stop() {
    task?.close()
    task = null
  }

  private fun poll() {
    if (credentials.byok || billing.license.read() == null) return
    val status = runCatching { billing.status() }
      .onFailure { platform.log.fine("Couldn't check the subscription: ${credentials.scrub(it.toString())}") }
      .getOrNull() ?: return
    this.status = status
    val notices = announcements.check(status)
    if (notices.isNotEmpty()) platform.scheduler.main { notices.forEach(::announce) }
  }

  private fun announce(notice: Notice) {
    platform.players.filter { it.can(Permissions.ADMIN) }.forEach { player ->
      val lang = locales.of(player)
      val text = Line.Text(Legacy.color(text(lang, notice, commands.zone(player))))
      player.send(if (notice is Notice.Paid) Line(text) else Line(text, Line.SPACE, commands.linkButton(lang, "billing.notice.renew")))
    }
    val console = platform.console
    platform.log.info(Legacy.strip(Legacy.color(text(locales.of(console), notice, commands.zone(console)))))
  }

  private fun text(lang: Lang, notice: Notice, zone: java.time.ZoneId): String = when (notice) {
    is Notice.Paid -> lang.text("billing.notice.paid", "date" to (BillingDates.format(lang, notice.until, zone) ?: "?"))
    is Notice.Quota -> {
      val key = if (notice.level >= 100) "billing.notice.quota_100" else "billing.notice.quota_80"
      // A week or a month that's spent refills only with a payment; a year's month says when.
      val date = BillingDates.format(lang, notice.resetsAt, zone)
      if (date == null) lang.text("${key}_no_reset") else lang.text(key, "date" to date)
    }
    is Notice.Expiring -> lang.text("billing.notice.expiring", "date" to (BillingDates.format(lang, notice.until, zone) ?: "?"))
  }
}
