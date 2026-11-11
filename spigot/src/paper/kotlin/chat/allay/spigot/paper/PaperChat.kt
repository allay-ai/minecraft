package chat.allay.spigot.paper

import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import java.util.UUID

/**
 * The chat as Paper's chat plugins leave it, for `chat_history`. Paper fires Spigot's chat event first, and a plugin
 * that narrows who reads a line (a staff, party, or local channel of CarbonChat and the like) does it after, in
 * AsyncChatEvent's viewers: Spigot's recipients would call such a line public. Loaded only on Paper.
 */
object PaperChat {
  /** Hands every line that went out to [heard]: who wrote it, the text with § codes, and every player who read it. */
  fun lines(heard: (Player, String, List<UUID>) -> Unit): Listener = Lines(heard)

  class Lines(private val heard: (Player, String, List<UUID>) -> Unit) : Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun sent(event: AsyncChatEvent) =
      heard(event.player, LegacyComponentSerializer.legacySection().serialize(event.message()), event.viewers().filterIsInstance<Player>().map { it.uniqueId })
  }
}
