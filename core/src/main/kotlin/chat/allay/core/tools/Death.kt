package chat.allay.core.tools

import kotlinx.serialization.Serializable

/**
 * One death as the game told it: where, when, what killed the player, and what they lost. The plugin sends each to the
 * backend (`POST /api/v2/users/{id}/deaths`, server 6.9), which keeps the history and pages it for the model; field
 * names follow the wire.
 */
@Serializable
data class Death(
  val at: Long,
  val world: String,
  val dimension: String,
  val x: Int,
  val y: Int,
  val z: Int,
  /** The damage type, like minecraft:fall. */
  val cause: String? = null,
  /** The type of whatever caused it, like minecraft:creeper or minecraft:player. */
  val killer: String? = null,
  /** A killer player's name, or a mob's custom name, which a player wrote. */
  val killer_name: String? = null,
  val message: String? = null,
  val level: Int = 0,
  val kept_inventory: Boolean? = null,
  /** At most 20 kinds, the biggest piles first. */
  val drops: Map<String, Int>? = null,
  val experience: Int? = null,
)
