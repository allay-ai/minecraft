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
 * Spending limits the server's admin sets in `quota` of config.yml, for any way of paying. The server may spend
 * [monthlyUsd] a month; with [daily], only as much of it as the days so far unlock, so the first evening can't burn
 * the month. Tiers are weights, `allay.quota.<tier>` gives one, `default` needs no permission, a player gets the
 * biggest they hold, and operators the biggest there is. Nobody books anything: a player's limit is the unlocked
 * budget times their weight over the weights of everyone who asked this month, so a quiet player costs nobody.
 */
class QuotaSettings(val enabled: Boolean, val monthlyUsd: Double, val daily: Boolean, val tiers: Map<String, Double>, val error: String?) {
  /** The weight a player gets from the tiers [holds] says they have; 0 means no tier at all. */
  fun weight(operator: Boolean, holds: (String) -> Boolean): Double {
    if (operator) return tiers.values.maxOrNull() ?: 0.0
    return tiers.filter { (tier, _) -> tier == DEFAULT || holds(tier) }.values.maxOrNull() ?: 0.0
  }

  /** What the server may have spent by [now]: the whole month, or the days so far of it. */
  fun unlocked(now: ZonedDateTime): Double =
    if (!daily) monthlyUsd else monthlyUsd * now.dayOfMonth / now.toLocalDate().lengthOfMonth()

  companion object {
    const val DEFAULT = "default"
    private val NAME = Regex("[a-z0-9_-]{1,32}")

    /** Reads `quota`: weights as numbers or strings (“3”); anything else makes [error] say what's wrong. */
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
          value == null -> error = error ?: "quota.tiers.$key: “${section.get(key)}” is not a number like 1 or 2.5"
          value.signum() <= 0 -> error = error ?: "quota.tiers.$key: a weight is above 0, not $value"
          else -> tiers[tier] = value.toDouble()
        }
      }
      if (enabled && error == null) {
        error = when {
          monthly == null || monthly.signum() <= 0 -> "quota.monthly_usd must be a sum above 0, such as 20.0"
          tiers.isEmpty() -> "quota.tiers is empty: give at least one tier, such as default: 1"
          else -> null
        }
      }
      return QuotaSettings(enabled, monthly?.toDouble() ?: 0.0, config.boolean("quota.daily", true), tiers, error.takeIf { enabled })
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

/**
 * What the server and each player spent this month, in `quota.json`, and the weight each player who asked had last.
 * A new month starts from zero, with nobody active.
 */
class QuotaBook(private val folder: File, private val log: Logger, private val zone: () -> ZoneId) {
  @Serializable private data class Player(val usd: Double = 0.0, val weight: Double = 0.0)
  @Serializable private data class Month(val month: String = "", val server: Double = 0.0, val players: Map<String, Player> = emptyMap())

  private val file = File(folder, "quota.json")
  private val json = Json { ignoreUnknownKeys = true }
  private var data = load()

  fun now(): ZonedDateTime = ZonedDateTime.now(zone())

  /** `2026-10`, in the server's time zone. */
  private fun month(now: ZonedDateTime = now()) = "%04d-%02d".format(now.year, now.monthValue)

  @Synchronized private fun current(): Month {
    val month = month()
    if (data.month != month) data = Month(month)
    return data
  }

  fun server(): Double = current().server

  fun player(id: UUID): Double = current().players[id.toString()]?.usd ?: 0.0

  /** The weights of everyone who asked this month, [id] counted with [weight] as of now. */
  @Synchronized fun active(id: UUID, weight: Double): Double {
    val now = current()
    if (now.players[id.toString()]?.weight != weight) {
      data = now.copy(players = now.players + (id.toString() to (now.players[id.toString()] ?: Player()).copy(weight = weight)))
      save()
    }
    return data.players.values.sumOf { it.weight }
  }

  @Synchronized fun add(id: UUID?, usd: Double) {
    if (usd <= 0) return
    val now = current()
    val players = if (id == null) now.players else now.players + (id.toString() to (now.players[id.toString()] ?: Player()).let { it.copy(usd = it.usd + usd) })
    data = now.copy(server = now.server + usd, players = players)
    save()
  }

  /** When there's more to spend: tomorrow with a daily budget, else the first of next month. */
  fun resets(daily: Boolean): String {
    val now = now()
    val next = if (daily && now.dayOfMonth < now.toLocalDate().lengthOfMonth()) now.plusDays(1) else now.plusMonths(1).withDayOfMonth(1)
    return next.toLocalDate().toString()
  }

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

/** Whether a player may ask now, under [settings], with what [book] says they, the others, and the server spent. */
object Quotas {
  sealed interface Verdict {
    data object Allowed : Verdict
    /** The config is wrong; [reason] is for admins. */
    data class Misconfigured(val reason: String) : Verdict
    /** The player holds no tier and there's no default one. */
    data object NoTier : Verdict
    /** The player spent their share of what's unlocked so far. */
    data object PlayerSpent : Verdict
    /** The whole server spent what's unlocked so far. */
    data object ServerSpent : Verdict
  }

  /** A player's limit right now: what's unlocked, times their weight over all active weights. */
  fun limit(unlocked: Double, weight: Double, activeWeights: Double): Double = if (activeWeights <= 0) 0.0 else unlocked * weight / activeWeights

  fun check(settings: QuotaSettings, book: QuotaBook, player: UUID?, operator: Boolean, holds: (String) -> Boolean): Verdict {
    if (!settings.enabled) return Verdict.Allowed
    settings.error?.let { return Verdict.Misconfigured(it) }
    val unlocked = settings.unlocked(book.now())
    if (book.server() >= unlocked) return Verdict.ServerSpent
    if (player == null) return Verdict.Allowed
    val weight = settings.weight(operator, holds)
    if (weight <= 0) return Verdict.NoTier
    val limit = limit(unlocked, weight, book.active(player, weight))
    return if (book.player(player) >= limit) Verdict.PlayerSpent else Verdict.Allowed
  }
}
