package chat.allay.core

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * The players who agreed to the notice on this server, in `consented.bin` ([SealedFile]): UUIDs only. A player who
 * joins is looked up on the backend only when they're on this list; anyone else reaches the backend only when they use
 * Allay themselves. Without a key yet (no license, no token) the list lives in memory until there is one.
 */
class Consented(private val sealed: SealedFile, private val log: Logger) {
  private val players = ConcurrentHashMap.newKeySet<UUID>()
  @Volatile private var loaded = false
  @Volatile private var warned = false

  fun has(player: UUID): Boolean {
    load()
    return player in players
  }

  fun add(player: UUID) {
    load()
    if (players.add(player)) save()
  }

  fun remove(player: UUID) {
    load()
    if (players.remove(player)) save()
  }

  private fun load() {
    if (loaded) return
    synchronized(this) {
      if (loaded) return
      sealed.read()?.lineSequence()?.mapNotNull { runCatching { UUID.fromString(it.trim()) }.getOrNull() }?.forEach(players::add)
      loaded = true
    }
  }

  @Synchronized private fun save() {
    if (players.isEmpty()) return sealed.delete()
    if (!sealed.write(players.joinToString("\n")) && !warned) {
      warned = true
      log.warning("No license or server.token yet to seal ${FILE} with: the list of players who agreed lasts until the server stops")
    }
  }

  companion object {
    const val FILE = "consented.bin"
  }
}
