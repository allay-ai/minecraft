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
  /** Kinds beyond the 20 in [drops] (backend 6.21 keeps it; older ones drop the field). */
  val more_drops: Int? = null,
) {
  companion object {
    /** Kinds of drops a death keeps; the rest only count in [more_drops]. */
    const val MAX_DROPS = 20

    /**
     * The game's death message with each name in [hidden] (other players the victim may not learn about: they never
     * agreed here, or they're vanished) put as “a player”, so the history never names them.
     */
    fun scrub(message: String?, hidden: Collection<String>): String? {
      if (message == null) return null
      return hidden.filter { it.isNotEmpty() }.sortedByDescending { it.length }
        .fold(message) { text, name -> text.replace(Regex("(?<![A-Za-z0-9_])${Regex.escape(name)}(?![A-Za-z0-9_])"), "a player") }
    }
  }
}
