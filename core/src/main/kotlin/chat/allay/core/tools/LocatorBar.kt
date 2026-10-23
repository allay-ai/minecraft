package chat.allay.core.tools

/**
 * The locator bar's rules as the game has them (`ServerWaypointManager` and `WaypointTransmitter` in 26.3), in plain
 * numbers, so every platform shows other players to Allay exactly as the bar shows them, and tests hold it to the game.
 */
object LocatorBar {
  /** The game's default transmit and receive range, when a player has no such attribute. */
  const val DEFAULT_RANGE = 6.0E7

  /**
   * One dot on the bar. A spectator sees every player who transmits, however far; nobody else sees a spectator, or
   * anyone beyond both players' ranges. Crouching, a carved pumpkin or a mob head, and invisibility set transmit to 0.
   */
  fun shows(viewerSpectator: Boolean, targetSpectator: Boolean, transmit: Double, receive: Double, distance: Double): Boolean {
    if (transmit <= 0) return false
    if (viewerSpectator) return true
    return !targetSpectator && distance <= minOf(transmit, receive)
  }
}
