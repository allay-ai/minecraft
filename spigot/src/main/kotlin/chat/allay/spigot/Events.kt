package chat.allay.spigot

import chat.allay.core.Allay

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerLocaleChangeEvent
import org.bukkit.event.player.PlayerQuitEvent

/** Bukkit's events for the core's pieces: logins, joins, quits, languages, and chat. */
class Events(private val allay: Allay<*>) : Listener {
  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  fun onPreLogin(event: AsyncPlayerPreLoginEvent) {
    if (event.loginResult == AsyncPlayerPreLoginEvent.Result.ALLOWED) allay.locales.login(event.uniqueId, event.address)
  }

  @EventHandler
  fun onJoin(event: PlayerJoinEvent) {
    val player = event.player.audience
    allay.chat.joined(player)
    allay.quota.joined(player)
  }

  /** After every other listener: the language first, then the player's state from the backend. */
  @EventHandler(priority = EventPriority.MONITOR)
  fun onJoined(event: PlayerJoinEvent) {
    val player = event.player.audience
    allay.locales.joined(player)
    allay.joined(player)
  }

  @EventHandler
  fun onQuit(event: PlayerQuitEvent) {
    allay.chat.quit(event.player.uniqueId)
    allay.locales.quit(event.player.uniqueId)
  }

  /** After every other listener, so what they changed goes back to the backend. */
  @EventHandler(priority = EventPriority.MONITOR)
  fun onLeft(event: PlayerQuitEvent) {
    allay.quit(event.player.uniqueId)
  }

  @EventHandler(priority = EventPriority.MONITOR)
  fun onLocaleChange(event: PlayerLocaleChangeEvent) {
    allay.locales.changed(event.player.uniqueId, event.locale)
  }

  // LOWEST, so the line never reaches other chat plugins as an ordinary message.
  @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
  fun onChat(event: AsyncPlayerChatEvent) {
    when (val line = allay.chat.message(event.player.audience, event.message)) {
      null -> event.isCancelled = true
      event.message -> Unit
      else -> event.message = line
    }
  }
}
