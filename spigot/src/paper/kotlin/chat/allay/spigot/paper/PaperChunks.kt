package chat.allay.spigot.paper

import io.papermc.paper.event.packet.PlayerChunkLoadEvent
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener

/**
 * When a player's client gets a chunk, as only Paper tells: a preview's blocks in it go again right after, since the
 * chunk's own blocks took their place. Spigot sends them again on a timer instead. Loaded only on Paper.
 */
object PaperChunks {
  fun sent(heard: (Player, Int, Int) -> Unit): Listener = Sent(heard)

  class Sent(private val heard: (Player, Int, Int) -> Unit) : Listener {
    @EventHandler(priority = EventPriority.MONITOR)
    fun sent(event: PlayerChunkLoadEvent) = heard(event.player, event.chunk.x, event.chunk.z)
  }
}
