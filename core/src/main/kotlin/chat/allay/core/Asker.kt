package chat.allay.core

import java.util.UUID

/**
 * Who asked: a player or the console. Each platform wraps its own sender in one. Tools and the backend learn about
 * this sender alone: the owner decided the assistant may learn about the asker and nobody else.
 */
interface Asker {
  val name: String

  /** The player's id; null for the console and command blocks. */
  val uuid: UUID?

  val operator: Boolean

  /** The language the player's game is set to, like `en_us`; null for the console. */
  val locale: String?

  /** The dimension the player stood in when they asked, as `Dimensions` ids; null for the console. */
  val dimension: String? get() = null

  /** Where the player stood when they asked; null for the console. */
  val position: Protocol.Position? get() = null

  fun can(permission: String): Boolean
}
