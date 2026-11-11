package chat.allay.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.logging.Logger
import kotlin.math.floor
import kotlin.math.roundToInt

/** What operators hear about the subscription between polls of its status. */
sealed interface Notice {
  /** A payment came in; the subscription runs until [until]. */
  data class Paid(val until: String?) : Notice

  /** This period's budget passed [level] percent (80 or 100); it refills at [resetsAt]. */
  data class Quota(val level: Int, val resetsAt: String?) : Notice

  /** The subscription ends at [until], in three days or less, and doesn't renew by itself. */
  data class Expiring(val until: String) : Notice
}

/** What operators were already told, in `subscription.json`, so a restart doesn't tell them again. */
@Serializable data class Announced(
  val last_payment_at: String? = null,
  /** The `quota.resets_at` the level belongs to: a new period starts from zero. */
  val quota_period: String? = null,
  val quota_level: Int = 0,
  /** The `paid_until` the three-day warning went out for. */
  val expiry_warned: String? = null,
)

class Announcements(private val folder: File, private val log: Logger) {
  private val file = File(folder, "subscription.json")
  private val json = Json { ignoreUnknownKeys = true }
  private var state = load()

  /** The notices this status brings; remembers them right away. */
  @Synchronized fun check(status: LicenseStatus, now: Instant = Instant.now()): List<Notice> {
    val (next, notices) = next(state, status, now)
    if (next != state) {
      state = next
      save()
    }
    return notices
  }

  private fun save() = runCatching {
    folder.mkdirs()
    val temp = File(folder, "subscription.json.tmp")
    temp.writeText(json.encodeToString(state))
    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
  }.onFailure { log.warning("Couldn't save subscription.json: ${it.message}") }

  private fun load(): Announced = if (!file.exists()) Announced() else runCatching {
    json.decodeFromString<Announced>(file.readText())
  }.getOrElse { Announced() }

  companion object {
    val WARN_BEFORE: Duration = Duration.ofDays(3)

    fun next(state: Announced, status: LicenseStatus, now: Instant): Pair<Announced, List<Notice>> {
      if (!status.billing) return state to emptyList()
      val notices = mutableListOf<Notice>()
      var next = state

      val payment = status.last_payment_at
      if (payment != null && payment != state.last_payment_at) notices += Notice.Paid(status.paid_until)
      next = next.copy(last_payment_at = payment ?: state.last_payment_at)

      // Before the first payment or after the period, there's no limit to speak of.
      status.quota?.takeIf { status.active }?.let { quota ->
        val level = when {
          quota.used_percent >= 100 -> 100
          quota.used_percent >= 80 -> 80
          else -> 0
        }
        val told = if (quota.resets_at == state.quota_period) state.quota_level else 0
        if (level > told) notices += Notice.Quota(level, quota.resets_at)
        // A top-up lowers the level, so passing 80 again is news again.
        next = next.copy(quota_period = quota.resets_at, quota_level = level)
      }

      val until = status.paid_until
      val ends = until?.let(::instant)
      if (until != null && ends != null && !status.renews && until != state.expiry_warned &&
        ends.isAfter(now) && Duration.between(now, ends) <= WARN_BEFORE) {
        notices += Notice.Expiring(until)
        next = next.copy(expiry_warned = until)
      }
      return next to notices
    }
  }
}

/** A quota as a bar of 20 squares: green, yellow from 80%, red at 100%. */
object QuotaBar {
  fun render(percent: Double, width: Int = 20): String {
    val filled = (percent.coerceIn(0.0, 100.0) / 100 * width).roundToInt()
    val color = when {
      percent >= 100 -> "&c"
      percent >= 80 -> "&e"
      else -> "&a"
    }
    return color + "■".repeat(filled) + "&8" + "□".repeat(width - filled)
  }

  /** Whole percents, rounded down, so 99.6 never reads as a spent limit. */
  fun percent(percent: Double): Int = floor(percent.coerceAtLeast(0.0)).toInt()
}

/** ISO-8601 dates as a reader says them: `billing.date` from their language file, with the year when it isn't this one. */
object BillingDates {
  fun format(lang: Lang, iso: String?, zone: ZoneId, now: Instant = Instant.now()): String? {
    val date = iso?.let(::instant)?.atZone(zone) ?: return null
    val key = if (date.year == now.atZone(zone).year) "billing.date" else "billing.date_year"
    val locale = Locale.forLanguageTag(lang.code.replace('_', '-'))
    return runCatching { date.format(DateTimeFormatter.ofPattern(lang.text(key), locale)) }
      .getOrElse { date.toLocalDate().toString() }
  }
}

internal fun instant(iso: String): Instant? = runCatching { Instant.parse(iso) }
  .recoverCatching { OffsetDateTime.parse(iso).toInstant() }.getOrNull()
