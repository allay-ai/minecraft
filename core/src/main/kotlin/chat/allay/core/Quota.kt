package chat.allay.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import java.util.logging.Logger

/**
 * Spending limits the server's admin sets in `quota` of config.yml, for any way of paying: the whole server may spend
 * [monthlyUsd] a month, and each player a share of it. A player's share comes from the tier permissions they hold,
 * `allay.quota.<tier>`; the biggest wins, and operators get the biggest there is. `default` needs no permission.
 * The shares together must stay within 1.0, or the config is wrong and Allay answers nobody until it's fixed.
 */
class QuotaSettings(val enabled: Boolean, val monthlyUsd: Double, val tiers: Map<String, Double>, val error: String?) {
  /** The share of [monthlyUsd] a player gets, from the tiers [holds] says they have. */
  fun share(operator: Boolean, holds: (String) -> Boolean): Double {
    if (operator) return tiers.values.maxOrNull() ?: 1.0
    return tiers.filter { (tier, _) -> tier == DEFAULT || holds(tier) }.values.maxOrNull() ?: 0.0
  }

  companion object {
    const val DEFAULT = "default"
    private val NAME = Regex("[a-z0-9_-]{1,32}")

    /** Reads `quota`: shares as strings (“0.10”) or numbers; anything else makes [error] say what's wrong. */
    fun parse(config: YamlConfig): QuotaSettings {
      val enabled = config.boolean("quota.enabled", false)
      val monthly = config.get("quota.monthly_usd")?.let { decimal(it) }
      val section = config.section("quota.tiers")
      val tiers = LinkedHashMap<String, Double>()
      var error: String? = null
      section?.keys?.forEach { key ->
        val tier = key.lowercase()
        val value = decimal(section.get(key))
        when {
          !NAME.matches(tier) -> error = error ?: "quota.tiers.$key: a tier name is letters, digits, _ and -"
          value == null -> error = error ?: "quota.tiers.$key: “${section.get(key)}” is not a number like \"0.10\""
          value.signum() < 0 || value > BigDecimal.ONE -> error = error ?: "quota.tiers.$key: a share is between 0 and 1, not $value"
          else -> tiers[tier] = value.toDouble()
        }
      }
      if (enabled && error == null) {
        val total = section?.keys?.mapNotNull { decimal(section.get(it)) }?.fold(BigDecimal.ZERO, BigDecimal::add) ?: BigDecimal.ZERO
        error = when {
          monthly == null || monthly.signum() <= 0 -> "quota.monthly_usd must be a sum above 0, such as 20.0"
          tiers.isEmpty() -> "quota.tiers is empty: give at least one tier, such as default: \"0.05\""
          total > BigDecimal.ONE -> "the shares in quota.tiers add up to ${total.stripTrailingZeros().toPlainString()}, more than 1.0 (100%)"
          else -> null
        }
      }
      return QuotaSettings(enabled, monthly?.toDouble() ?: 0.0, tiers, error.takeIf { enabled })
    }

    private fun decimal(value: Any?): BigDecimal? = when (value) {
      is Number -> value.toString().toBigDecimalOrNull()
      is String -> value.trim().toBigDecimalOrNull()
      else -> null
    }
  }
}

/** Models by tier from `models` in config.yml: the first tier, in the file's order, whose `allay.model.<tier>` a player holds. */
class ModelTiers(val models: Map<String, String>) {
  fun of(operator: Boolean, holds: (String) -> Boolean): String? =
    models.entries.firstOrNull { (tier, _) -> operator || holds(tier) }?.value

  companion object {
    fun parse(config: YamlConfig): ModelTiers {
      val section = config.section("models") ?: return ModelTiers(emptyMap())
      return ModelTiers(section.keys.associate { it.lowercase() to (section.string(it) ?: "").trim() }.filterValues { it.isNotEmpty() })
    }
  }
}

/** What the server and each player spent this month, in `quota.json`. A new month starts from zero. */
class QuotaBook(private val folder: File, private val log: Logger, private val zone: () -> ZoneId) {
  @Serializable private data class Month(val month: String = "", val server: Double = 0.0, val players: Map<String, Double> = emptyMap())

  private val file = File(folder, "quota.json")
  private val json = Json { ignoreUnknownKeys = true }
  private var data = load()

  /** `2026-10`, in the server's time zone. */
  private fun month(now: ZonedDateTime = ZonedDateTime.now(zone())) = "%04d-%02d".format(now.year, now.monthValue)

  @Synchronized private fun current(): Month {
    val month = month()
    if (data.month != month) data = Month(month)
    return data
  }

  fun server(): Double = current().server

  fun player(id: UUID): Double = current().players[id.toString()] ?: 0.0

  @Synchronized fun add(id: UUID?, usd: Double) {
    if (usd <= 0) return
    val now = current()
    val players = if (id == null) now.players else now.players + (id.toString() to (now.players[id.toString()] ?: 0.0) + usd)
    data = now.copy(server = now.server + usd, players = players)
    save()
  }

  /** The first day of next month, when spending starts over: `2026-11-01`. */
  fun resets(): String = ZonedDateTime.now(zone()).plusMonths(1).withDayOfMonth(1).toLocalDate().toString()

  @Synchronized private fun save() = runCatching {
    folder.mkdirs()
    val temp = File(folder, "quota.json.tmp")
    temp.writeText(json.encodeToString(data))
    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
  }.onFailure { log.warning("Couldn't save quota.json: ${it.message}") }

  private fun load(): Month = if (!file.exists()) Month() else runCatching { json.decodeFromString<Month>(file.readText()) }.getOrElse {
    log.warning("Couldn't read quota.json: ${it.message}; counting from zero")
    Month()
  }
}

/** Whether a player may ask now, under [settings], with what [book] says they and the server spent. */
object Quotas {
  sealed interface Verdict {
    data object Allowed : Verdict
    /** The config is wrong; [reason] is for admins. */
    data class Misconfigured(val reason: String) : Verdict
    /** The player spent their share. */
    data object PlayerSpent : Verdict
    /** The whole server spent its month. */
    data object ServerSpent : Verdict
  }

  fun check(settings: QuotaSettings, book: QuotaBook, player: UUID?, operator: Boolean, holds: (String) -> Boolean): Verdict {
    if (!settings.enabled) return Verdict.Allowed
    settings.error?.let { return Verdict.Misconfigured(it) }
    if (book.server() >= settings.monthlyUsd) return Verdict.ServerSpent
    if (player == null) return Verdict.Allowed
    val limit = settings.share(operator, holds) * settings.monthlyUsd
    return if (book.player(player) >= limit) Verdict.PlayerSpent else Verdict.Allowed
  }
}
