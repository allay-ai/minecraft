package chat.allay.spigot.tools

import chat.allay.core.*
import chat.allay.core.tools.*
import chat.allay.spigot.Vanish

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bukkit.GameMode
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player

/**
 * Other players as the asker's locator bar shows them, and no more: the owner allowed Allay to know about others
 * only what the player already sees on the bar. The rules are the game's (`ServerWaypointManager` and
 * `WaypointTransmitter` in 26.3): the bar needs the `locator_bar` game rule in the viewer's world, and a player
 * whose transmit range is 0 (crouching, a carved pumpkin or a mob head on the head, invisibility) is off it.
 */
object Locator {
  /**
   * One dot on the bar, in plain numbers so tests can hold it to the game. A spectator sees every player who
   * transmits, however far; nobody else sees a spectator, or anyone beyond both players' ranges.
   */
  fun shows(viewerSpectator: Boolean, targetSpectator: Boolean, transmit: Double, receive: Double, distance: Double): Boolean {
    if (transmit <= 0) return false
    if (viewerSpectator) return true
    return !targetSpectator && distance <= minOf(transmit, receive)
  }

  /** Whether [target] is on [viewer]'s bar right now. A vanished player stays hidden, as everywhere in Allay. */
  fun shows(viewer: Player, target: Player): Boolean {
    if (target == viewer || !target.isOnline || target.world != viewer.world || !Vanish.visible(viewer, target)) return false
    if (!barOn(viewer)) return false
    return shows(
      viewerSpectator = viewer.gameMode == GameMode.SPECTATOR,
      targetSpectator = target.gameMode == GameMode.SPECTATOR,
      transmit = target.getAttribute(Attribute.WAYPOINT_TRANSMIT_RANGE)?.value ?: DEFAULT_RANGE,
      receive = viewer.getAttribute(Attribute.WAYPOINT_RECEIVE_RANGE)?.value ?: DEFAULT_RANGE,
      distance = target.location.distance(viewer.location),
    )
  }

  /** Everyone on [viewer]'s bar, nearest first. */
  fun around(viewer: Player): List<Player> =
    viewer.world.players.filter { shows(viewer, it) }.sortedBy { it.location.distanceSquared(viewer.location) }

  /** An online player by name, in any case, whom [viewer] may know about; on the bar or not. */
  fun named(viewer: Player, name: String): Player? =
    viewer.server.onlinePlayers.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) && it != viewer && Vanish.visible(viewer, it) }

  /** The `locator_bar` game rule in [viewer]'s world. Through the registry, as WorldTools reads rules: GameRule's static members differ between Spigot and Paper. */
  fun barOn(viewer: Player): Boolean {
    val rule = Registry.GAME_RULE.get(NamespacedKey.minecraft("locator_bar")) ?: return true
    return viewer.world.getGameRuleValue(rule) as? Boolean ?: true
  }

  /** The game's default for both ranges. */
  private const val DEFAULT_RANGE = 6.0E7
}

/** `get_nearby_players`: who is on the asker's locator bar, so they can ask for the way to someone by name. */
object LocatorTools {
  /** More than this many blocks up or down, the bar shows an arrow; the model hears it as above or below. */
  private const val HEIGHT = 8

  private val nearby = object : Tool {
    override val name = "get_nearby_players"

    override fun run(context: ToolContext, args: JsonObject): ToolOutput {
      val player = context.requirePlayer()
      val around = Locator.around(player)
      val result = buildJsonObject {
        // Off by the game rule, nobody is on the bar; the model should say why instead of “nobody's around”.
        put("locator_bar", Locator.barOn(player))
        put("players", buildJsonArray {
          around.forEach { other ->
            val dx = other.location.x - player.location.x
            val dy = other.location.y - player.location.y
            val dz = other.location.z - player.location.z
            add(buildJsonObject {
              put("name", context.nameOf(other))
              put("distance", Math.round(other.location.distance(player.location)))
              put("direction", Facts.direction(dx, dz))
              if (dy > HEIGHT) put("height", "above") else if (dy < -HEIGHT) put("height", "below")
            })
          }
        })
        // Everyone in the player list sees these names anyway; where they are stays unsaid.
        put("others_online", buildJsonArray {
          Vanish.visibleTo(player, player.server.onlinePlayers).filter { it != player && it !in around }.sortedBy { it.name.lowercase() }.forEach { add(context.nameOf(it)) }
        })
      }
      val lang = context.lang
      return ToolOutput(result, if (around.isEmpty()) lang.text("summary.players_none") else lang.text("summary.players", "count" to around.size))
    }
  }

  val all = listOf(nearby)
}
