package chat.allay.spigot.tools

import chat.allay.core.*
import chat.allay.core.tools.*

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.putJsonObject
import org.bukkit.Location
import org.bukkit.entity.Player
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Places that matter to the asker: where they respawn, where the world starts, where they last died, and earlier deaths. */
object LocationTools {
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
        put("world_spawn", describe(player.server.worlds.first().spawnLocation, player))
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

    override fun run(context: ToolContext, args: JsonObject): ToolOutput {
      val player = context.requirePlayer()
      val history = context.features.deaths.log.of(player.uniqueId)
      val page = history.page(args.int("page") ?: 1)
      val record = history.latest()
      // A death from before the log existed is known only by the game's last death point.
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
      val zone = TimeZones.parse(context.features.zones.of(player.uniqueId)) ?: ZoneId.systemDefault()
      val result = buildJsonObject {
        put("place", describe(place, player))
        put("minutes_ago", minutes)
        record?.let {
          put("kept_inventory", it.keptInventory)
          put("dropped", buildJsonObject { it.drops.forEach { (id, count) -> put(id, count) } })
          put("dropped_experience", it.experience)
        }
        // Items despawn after five minutes, but their clock only runs while the area is loaded.
        put("area_loaded", loaded)
        if (loaded) {
          put("items_on_ground", items.sumOf { it.itemStack.amount })
          items.minOfOrNull { Deaths.DESPAWN_TICKS - it.ticksLived }?.let { put("seconds_until_first_despawn", (it / 20).coerceAtLeast(0)) }
        }
        putJsonObject("history") {
          put("page", page.page)
          put("pages", page.pages)
          put("total", page.total)
          put("deaths", buildJsonArray { page.deaths.forEach { addJsonObject { entry(it, player, zone) } } })
        }
      }
      val summary = if (world == player.world) {
        context.lang.text("summary.death_distance", "blocks" to Math.round(place.distance(player.location)))
      } else context.lang.text("summary.death_elsewhere")
      return ToolOutput(result, summary)
    }

    /** One death of the history: local time in the player's zone, the place, and what happened, without empty fields. */
    private fun kotlinx.serialization.json.JsonObjectBuilder.entry(death: Death, player: Player, zone: ZoneId) {
      val at = Instant.ofEpochMilli(death.at)
      put("at", at.atZone(zone).truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
      val minutes = (System.currentTimeMillis() - death.at) / 60_000
      if (minutes < 48 * 60) put("minutes_ago", minutes) else put("days_ago", minutes / (24 * 60))
      put("x", death.x)
      put("y", death.y)
      put("z", death.z)
      put("dimension", death.dimension)
      if (death.world == player.world.name) {
        val dx = death.x + 0.5 - player.location.x
        val dz = death.z + 0.5 - player.location.z
        put("distance", Math.round(Math.hypot(dx, dz)))
        put("direction", Facts.direction(dx, dz))
      }
      death.cause?.let { put("cause", it.removePrefix("minecraft:")) }
      death.killer?.let { put("killer", it.removePrefix("minecraft:")) }
      death.killerName?.let { put("killer_name", it) }
      death.message?.let { put("message", it) }
      put("level", death.level)
      if (death.keptInventory) put("kept_inventory", true)
      if (death.drops.isNotEmpty()) put("dropped_items", death.drops.values.sum())
      if (death.experience > 0) put("dropped_experience", death.experience)
    }
  }

  val all = listOf<Tool>(locations, death)
}
