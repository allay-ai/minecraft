package chat.allay.spigot.tools

import chat.allay.core.*
import chat.allay.core.tools.*

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.entity.Player

/** Places that matter to the asker: where they respawn, where the world starts, and where they last died. */
object LocationTools {
  /**
   * The world players spawn in. Since 1.21.9 the game keeps one spawn for the server, in any dimension, and Paper tells
   * which world through `Server.getRespawnWorld`; Spigot has no such call, so the first world, as before.
   */
  fun respawnWorld(server: Server): World =
    runCatching { server.javaClass.getMethod("getRespawnWorld").invoke(server) as? World }.getOrNull() ?: server.worlds.first()

  /** The world spawn with the radius new players land within, from the `respawn_radius` game rule. */
  private fun worldSpawn(player: Player): JsonElement {
    val world = respawnWorld(player.server)
    val spawn = describe(world.spawnLocation, player)
    if (spawn !is JsonObject) return spawn
    // Through the registry, as WorldTools reads rules: GameRule's static members differ between Spigot and Paper.
    val radius = Registry.GAME_RULE.get(NamespacedKey.minecraft("respawn_radius"))?.let { world.getGameRuleValue(it) as? Number }
    return JsonObject(spawn + listOfNotNull(radius?.let { "respawn_radius" to kotlinx.serialization.json.JsonPrimitive(it.toInt()) }))
  }

  /** A place as the model needs it, with distance and direction when the player can walk there. */
  private fun describe(place: Location?, player: Player): JsonElement {
    val world = place?.world ?: return JsonNull
    return buildJsonObject {
      put("x", place.blockX)
      put("y", place.blockY)
      put("z", place.blockZ)
      put("dimension", Dimensions.id(world))
      if (world == player.world) {
        val dx = place.x - player.location.x
        val dz = place.z - player.location.z
        put("distance", Math.round(Math.hypot(dx, dz)))
        put("direction", Facts.direction(dx, dz))
      }
    }
  }

  private val locations = object : Tool {
    override val name = "get_locations"

    override fun run(context: ToolContext, args: JsonObject): ToolOutput {
      val player = context.requirePlayer()
      val death = player.lastDeathLocation
      val result = buildJsonObject {
        put("respawn_point", describe(player.respawnLocation, player))
        put("world_spawn", worldSpawn(player))
        put("last_death", describe(death, player))
      }
      val summary = if (death?.world == player.world) {
        context.lang.text("summary.death_near", "blocks" to Math.round(death.distance(player.location)))
      } else if (player.respawnLocation != null) context.lang.text("summary.respawn_set") else context.lang.text("summary.respawn_none")
      return ToolOutput(result, summary)
    }
  }

  private val death = object : Tool {
    override val name = "get_death_info"

    /**
     * The last death as it stands now. Since 6.7 the backend keeps the history and pages it for the model itself
     * (`get_locations` with `deaths_page`), so `page` is ignored here.
     */
    override fun run(context: ToolContext, args: JsonObject): ToolOutput {
      val player = context.requirePlayer()
      val record = context.features.deaths.latest(player.uniqueId)
      // A death from before this run is known only by the game's last death point.
      val place = record?.let { player.server.getWorld(it.world)?.let { world -> Location(world, it.x + 0.5, it.y.toDouble(), it.z + 0.5) } }
        ?: player.lastDeathLocation ?: throw ToolError("this player hasn't died yet")
      val world = place.world ?: throw ToolError("the world of the last death is gone")
      val minutes = player.getStatistic(org.bukkit.Statistic.TIME_SINCE_DEATH) / 1_200
      val loaded = world.isChunkLoaded(place.blockX shr 4, place.blockZ shr 4)
      // Only items of the kinds the player dropped count, so someone else's junk nearby doesn't.
      val kinds = record?.drops?.keys.orEmpty()
      val items = if (loaded) {
        world.getNearbyEntities(place, 8.0, 8.0, 8.0).filterIsInstance<org.bukkit.entity.Item>().filter { record == null || it.itemStack.type.key.key in kinds }
      } else emptyList()
      val result = buildJsonObject {
        put("place", describe(place, player))
        put("minutes_ago", minutes)
        record?.let {
          put("kept_inventory", it.kept_inventory == true)
          put("dropped", buildJsonObject { it.drops.orEmpty().forEach { (id, count) -> put(id, count) } })
          put("dropped_experience", it.experience ?: 0)
        }
        // Items despawn after five minutes, but their clock only runs while the area is loaded.
        put("area_loaded", loaded)
        if (loaded) {
          put("items_on_ground", items.sumOf { it.itemStack.amount })
          items.minOfOrNull { Deaths.DESPAWN_TICKS - it.ticksLived }?.let { put("seconds_until_first_despawn", (it / 20).coerceAtLeast(0)) }
        }
      }
      val summary = if (world == player.world) {
        context.lang.text("summary.death_distance", "blocks" to Math.round(place.distance(player.location)))
      } else context.lang.text("summary.death_elsewhere")
      return ToolOutput(result, summary)
    }
  }

  val all = listOf<Tool>(locations, death)
}
