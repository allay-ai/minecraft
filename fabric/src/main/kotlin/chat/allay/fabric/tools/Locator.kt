package chat.allay.fabric.tools

import chat.allay.core.*
import chat.allay.core.tools.*
import chat.allay.fabric.Vanish

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.level.GameType
import net.minecraft.world.level.gamerules.GameRules

/**
 * Other players as the asker's locator bar shows them, and no more: the owner allowed Allay to know about others
 * only what the player already sees on the bar. The rules are the game's (`ServerWaypointManager` and
 * `WaypointTransmitter` in 26.3): the bar needs the `locator_bar` game rule in the viewer's world, and a player
 * whose transmit range is 0 (crouching, a carved pumpkin or a mob head on the head, invisibility) is off it.
 */
object Locator {
  /** Whether [target] is on [viewer]'s bar right now. A vanished player stays hidden, as everywhere in Allay. */
  fun shows(viewer: ServerPlayer, target: ServerPlayer): Boolean {
    if (target.uuid == viewer.uuid || target.hasDisconnected() || target.level() != viewer.level() || !Vanish.visible(viewer, target)) return false
    if (!barOn(viewer)) return false
    return LocatorBar.shows(
      viewerSpectator = viewer.gameMode() == GameType.SPECTATOR,
      targetSpectator = target.gameMode() == GameType.SPECTATOR,
      transmit = target.getAttribute(Attributes.WAYPOINT_TRANSMIT_RANGE)?.value ?: LocatorBar.DEFAULT_RANGE,
      receive = viewer.getAttribute(Attributes.WAYPOINT_RECEIVE_RANGE)?.value ?: LocatorBar.DEFAULT_RANGE,
      distance = target.position().distanceTo(viewer.position()),
    )
  }

  /** Everyone on [viewer]'s bar, nearest first. */
  fun around(viewer: ServerPlayer): List<ServerPlayer> =
    viewer.level().players().filter { shows(viewer, it) }.sortedBy { it.position().distanceToSqr(viewer.position()) }

  /** An online player by name, in any case, whom [viewer] may know about; on the bar or not. */
  fun named(viewer: ServerPlayer, name: String): ServerPlayer? =
    viewer.level().server.playerList.players.firstOrNull { it.gameProfile.name.equals(name.trim(), ignoreCase = true) && it.uuid != viewer.uuid && Vanish.visible(viewer, it) }

  /** The `locator_bar` game rule in [viewer]'s world. */
  fun barOn(viewer: ServerPlayer): Boolean = viewer.level().gameRules.get(GameRules.LOCATOR_BAR)
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
            val dx = other.x - player.x
            val dy = other.y - player.y
            val dz = other.z - player.z
            add(buildJsonObject {
              put("name", context.nameOf(other))
              put("distance", Math.round(other.position().distanceTo(player.position())))
              put("direction", Facts.direction(dx, dz))
              if (dy > HEIGHT) put("height", "above") else if (dy < -HEIGHT) put("height", "below")
            })
          }
        })
        // Everyone in the player list sees these names anyway; where they are stays unsaid.
        put("others_online", buildJsonArray {
          Vanish.visibleTo(player, player.level().server.playerList.players).filter { it.uuid != player.uuid && it !in around }.sortedBy { it.gameProfile.name.lowercase() }.forEach { add(context.nameOf(it)) }
        })
      }
      val lang = context.lang
      return ToolOutput(result, if (around.isEmpty()) lang.text("summary.players_none") else lang.text("summary.players", "count" to around.size))
    }
  }

  val all = listOf(nearby)
}
