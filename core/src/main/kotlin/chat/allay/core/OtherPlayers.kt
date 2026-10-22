package chat.allay.core

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Other players as the model reads them in one run: by name when they agreed to Allay's notice on this server, else
 * as “Player 1”, “Player 2”…, the same label all run long. Their names then never reach the backend, the AI
 * provider, or the traces; a tool the model calls with a label finds the player again.
 */
class OtherPlayers {
  private val labels = ConcurrentHashMap<UUID, String>()
  private val count = AtomicInteger()

  fun label(player: UUID, name: String, agreed: Boolean): String =
    if (agreed) name else labels.computeIfAbsent(player) { "Player ${count.incrementAndGet()}" }

  /** The player a label stands for, or null when [text] isn't one of this run's labels. */
  fun player(text: String): UUID? = labels.entries.firstOrNull { it.value.equals(text.trim(), ignoreCase = true) }?.key
}
