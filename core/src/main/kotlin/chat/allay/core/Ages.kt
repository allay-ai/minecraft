package chat.allay.core

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * How old a player must be to agree to their data being used, by the country of their IP address: the GDPR (Art. 8)
 * lets each EU and EEA country pick 13 to 16, Spain's LOPDGDD (Art. 7) says 14, and COPPA sets 13 in the US. An
 * unknown country gets the strictest, 16.
 */
object AgeGate {
  /** The bands the question offers; [min] is the youngest age in each. Neutral on purpose: no band names the line. */
  enum class Band(val wire: String, val min: Int) {
    UNDER_13("under13", 0), AGE_13("13", 13), AGE_14_15("14-15", 14), AGE_16_17("16-17", 16), ADULT("18", 18);

    companion object {
      fun parse(text: String): Band? = entries.firstOrNull { it.wire.equals(text.trim(), ignoreCase = true) }
    }
  }

  private val AGES = buildMap {
    listOf("BE", "DK", "EE", "FI", "LV", "MT", "PT", "SE", "IS", "NO").forEach { put(it, 13) }
    listOf("AT", "BG", "CY", "IT", "LT", "ES").forEach { put(it, 14) }
    listOf("CZ", "FR", "GR", "SI").forEach { put(it, 15) }
    listOf("DE", "HR", "HU", "IE", "LU", "NL", "PL", "RO", "SK", "LI").forEach { put(it, 16) }
  }

  /** The age of consent for [country], an ISO code like `ES`; 13 outside the EU and EEA, 16 when unknown. */
  fun threshold(country: String?): Int = when {
    country.isNullOrBlank() -> 16
    else -> AGES[country.uppercase()] ?: 13
  }

  /** A band counts only when even its youngest age is old enough, so a band across the line never lets anyone through. */
  fun allows(band: Band, country: String?) = band.min >= threshold(country)
}

/**
 * Players who said they're too young to agree, with when they said it, in `ages.bin` ([SealedFile]). Allay doesn't
 * answer them and sends nothing about them; the question comes back a year later.
 */
class Ages(private val sealed: SealedFile, private val log: Logger, private val now: () -> Long = System::currentTimeMillis) {
  private val young = ConcurrentHashMap<UUID, Long>()
  @Volatile private var loaded = false

  /** Whether the player said they're too young less than a year ago. */
  fun blocked(player: UUID): Boolean {
    load()
    val at = young[player] ?: return false
    if (now() - at < YEAR_MS) return true
    young.remove(player)
    save()
    return false
  }

  fun block(player: UUID) {
    load()
    young[player] = now()
    save()
  }

  private fun load() {
    if (loaded) return
    synchronized(this) {
      if (loaded) return
      sealed.read()?.lineSequence()?.forEach { line ->
        val (id, at) = line.split(' ').takeIf { it.size == 2 } ?: return@forEach
        runCatching { young[UUID.fromString(id)] = at.toLong() }
      }
      loaded = true
    }
  }

  @Synchronized private fun save() {
    if (young.isEmpty()) return sealed.delete()
    if (!sealed.write(young.entries.joinToString("\n") { "${it.key} ${it.value}" })) log.warning("No license or server.token yet to seal $FILE with: answers to the age question last until the server stops")
  }

  companion object {
    const val FILE = "ages.bin"
    const val YEAR_MS = 365L * 24 * 3_600_000
  }
}
