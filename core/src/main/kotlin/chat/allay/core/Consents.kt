package chat.allay.core

import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Whether a player agreed to Allay's notice, which the plugin shows with buttons before their first question. The
 * backend keeps the agreement (since 6.1) with the hash of what the player read (since 6.9); the plugin trusts a
 * positive answer for [RECHECK_MS], so an erasure from the admin panel asks the player again within the hour. A backend
 * before 6.1 can't keep one, and nobody gets asked until the next check an hour later, so an update of the backend
 * counts without a reload.
 *
 * The hash covers the English notice ([notice], as the jar ships it, so a server's own translation doesn't count as a
 * change) and the backend's policy revision. When either changes, a player who agreed before is asked again, with a
 * notice that says what changed.
 *
 * A player in a country that sets an age of consent ([AgeGate.asks], decided by [asksAge]) also says their age before
 * agreeing; the backend keeps `age_confirmed` with the agreement (6.9), and an agreement without it asks the age
 * question again. Anyone else agrees without it.
 *
 * [local] lists the players who agreed on this server, so a join looks up only them: it gains a player who agrees
 * or whom the backend knows to have agreed, and loses one who withdraws, declines, is erased, or has no agreement left.
 */
class Consents(
  private val backend: Backend,
  /** The English notice the player reads, line by line. */
  private val notice: () -> List<String>,
  private val local: Consented? = null,
  /** Whether the player has to say their age before agreeing. */
  private val asksAge: (UUID) -> Boolean = { true },
  private val now: () -> Long = System::currentTimeMillis,
) {
  interface Backend {
    /** The player's agreement and the backend's policy revision (6.9; null before). */
    suspend fun consent(userId: String): Answer
    suspend fun giveConsent(userId: String, version: Int, hash: String?, ageConfirmed: Boolean?)
    /** Whether there was an agreement to withdraw. */
    suspend fun withdrawConsent(userId: String): Boolean
  }

  /** What the backend keeps: the notice's version, and since 6.9 the hash the player agreed to and whether they confirmed their age. */
  data class Given(val version: Int, val hash: String? = null, val acceptedAt: String? = null, val ageConfirmed: Boolean? = null)

  data class Answer(val consent: Given?, val revision: String? = null)

  /** [AGE]: the player agreed to the current notice, but hasn't said they're old enough to. */
  enum class State { GIVEN, MISSING, CHANGED, AGE, UNSUPPORTED }

  /** When each player's agreement was last seen. */
  private val seen = ConcurrentHashMap<UUID, Long>()

  /** When a player whose notice changed had agreed, for the notice that asks again. */
  private val agreedAt = ConcurrentHashMap<UUID, String>()

  /** Players whose agreement on the backend says they're old enough, so a changed notice doesn't ask their age again. */
  private val aged = ConcurrentHashMap.newKeySet<UUID>()

  /** When the backend last said it can't keep consents. */
  @Volatile private var oldSince: Long? = null

  /** The backend's policy revision from its last answer; null before 6.9. */
  @Volatile private var revision: String? = null

  val unsupported get() = oldSince?.let { now() - it < RECHECK_MS } == true

  /** Whether [player] may ask without a request to the backend first. */
  fun known(player: UUID) = unsupported || seen[player]?.let { now() - it < RECHECK_MS } == true

  /** When a player asked again because the notice changed had agreed before, as the backend wrote it. */
  fun previous(player: UUID): String? = agreedAt[player]

  suspend fun check(player: UUID): State {
    val answer = try {
      backend.consent(player.toString())
    } catch (error: ApiErrorException) {
      if (error.apiError.statusCode !in OLD_BACKEND) throw error
      oldSince = now()
      return State.UNSUPPORTED
    }
    oldSince = null
    revision = answer.revision
    val given = answer.consent
    if (given == null) {
      seen.remove(player)
      // Erased in the admin panel, or never agreed here.
      local?.remove(player)
      return State.MISSING
    }
    if (given.ageConfirmed == true) aged += player else aged -= player
    // A backend before 6.9 keeps no hash: the version alone decides there.
    val current = answer.revision?.let(::hash)
    if (given.version < VERSION || (current != null && given.hash != current)) {
      seen.remove(player)
      given.acceptedAt?.let { agreedAt[player] = it }
      return State.CHANGED
    }
    agreedAt.remove(player)
    // Only a backend that keeps the age (6.9, with a revision) can say it's missing.
    if (answer.revision != null && given.ageConfirmed != true && asksAge(player)) {
      seen.remove(player)
      return State.AGE
    }
    seen[player] = now()
    local?.add(player)
    return State.GIVEN
  }

  /** Whether the backend's agreement already says the player is old enough. */
  fun ageKnown(player: UUID) = player in aged

  /** Records the agreement; [ageConfirmed] when the player said they're old enough to give it. */
  suspend fun give(player: UUID, ageConfirmed: Boolean = false) {
    backend.giveConsent(player.toString(), VERSION, revision?.let(::hash), ageConfirmed.takeIf { it })
    if (ageConfirmed) aged += player
    agreedAt.remove(player)
    seen[player] = now()
    local?.add(player)
  }

  suspend fun withdraw(player: UUID): Boolean {
    seen.remove(player)
    aged.remove(player)
    local?.remove(player)
    return backend.withdrawConsent(player.toString())
  }

  /** The player's data was erased, consent with it: ask again before the next question. */
  fun forget(player: UUID) {
    seen.remove(player)
    agreedAt.remove(player)
    aged.remove(player)
    local?.remove(player)
  }

  /** The player said no, to the notice or to the age question: nothing of theirs goes out. */
  fun declined(player: UUID) {
    seen.remove(player)
    aged.remove(player)
    local?.remove(player)
  }

  /** Whether the player is on this server's list of those who agreed, without a request. */
  fun listed(player: UUID) = local?.has(player) ?: true

  /** The hex SHA-256 of the notice's lines and the policy [revision], joined by `\n`. */
  fun hash(revision: String): String = digest(notice() + revision)

  companion object {
    /** The notice's version. Raise it when the notice says something new, and every player is asked again. */
    const val VERSION = 2
    const val RECHECK_MS = 3_600_000L
    /** A backend before 6.1 has no such route. */
    private val OLD_BACKEND = setOf(404, 405)

    fun digest(lines: List<String>): String =
      MessageDigest.getInstance("SHA-256").digest(lines.joinToString("\n").toByteArray()).joinToString("") { "%02x".format(it) }
  }
}
