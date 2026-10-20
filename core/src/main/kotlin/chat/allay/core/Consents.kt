package chat.allay.core

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Whether a player agreed to Allay's notice, which the plugin shows with buttons before their first question. The
 * backend keeps the agreement (since 6.1); the plugin trusts a positive answer for [RECHECK_MS], so an erasure from
 * the admin panel asks the player again within the hour. A backend before 6.1 can't keep one, and nobody gets asked.
 */
class Consents(private val backend: Backend, private val now: () -> Long = System::currentTimeMillis) {
  interface Backend {
    /** The version of the notice the player agreed to, or null. */
    suspend fun consent(userId: String): Int?
    suspend fun giveConsent(userId: String, version: Int)
    /** Whether there was an agreement to withdraw. */
    suspend fun withdrawConsent(userId: String): Boolean
  }

  enum class State { GIVEN, MISSING, UNSUPPORTED }

  /** When each player's agreement was last seen. */
  private val seen = ConcurrentHashMap<UUID, Long>()

  @Volatile var unsupported = false
    private set

  /** Whether [player] may ask without a request to the backend first. */
  fun known(player: UUID) = unsupported || seen[player]?.let { now() - it < RECHECK_MS } == true

  suspend fun check(player: UUID): State {
    val version = try {
      backend.consent(player.toString())
    } catch (error: ApiErrorException) {
      if (error.apiError.statusCode !in OLD_BACKEND) throw error
      unsupported = true
      return State.UNSUPPORTED
    }
    if (version == null || version < VERSION) return State.MISSING
    seen[player] = now()
    return State.GIVEN
  }

  suspend fun give(player: UUID) {
    backend.giveConsent(player.toString(), VERSION)
    seen[player] = now()
  }

  suspend fun withdraw(player: UUID): Boolean {
    seen.remove(player)
    return backend.withdrawConsent(player.toString())
  }

  /** The player's data was erased, consent with it: ask again before the next question. */
  fun forget(player: UUID) {
    seen.remove(player)
  }

  companion object {
    /** The notice's version. Raise it when the notice says something new, and every player is asked again. */
    const val VERSION = 1
    const val RECHECK_MS = 3_600_000L
    /** A backend before 6.1 has no such route. */
    private val OLD_BACKEND = setOf(404, 405)
  }
}
