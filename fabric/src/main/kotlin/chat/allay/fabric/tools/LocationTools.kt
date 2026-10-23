package chat.allay.fabric.tools

import chat.allay.core.*
import chat.allay.core.tools.*

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.core.GlobalPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.stats.Stats
import net.minecraft.util.Mth
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.level.gamerules.GameRules
import net.minecraft.world.level.portal.TeleportTransition
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/** Places that matter to the asker: where they respawn, where the world starts, and where they last died. */
object LocationTools {
  /** A spot in a world, as Bukkit's `Location`. */
  class Place(val level: ServerLevel, val point: Vec3) {
    val x get() = Mth.floor(point.x)
    val y get() = Mth.floor(point.y)
    val z get() = Mth.floor(point.z)
  }

  /** The world players spawn in: since 1.21.9 the game keeps one spawn for the server, in any dimension. */
  fun respawnWorld(server: MinecraftServer): ServerLevel = server.findRespawnDimension()

  /** Where the player respawns now, as Bukkit's `getRespawnLocation`: a bed or anchor that's still there, or null. */
  fun respawnPoint(player: ServerPlayer): Place? {
    val config = player.respawnConfig ?: return null
    // A dimension that's gone: the game would send the player to the world spawn, Bukkit says there's no point.
    player.level().server.getLevel(config.respawnData().dimension()) ?: return null
    // Without using an anchor's charge; a bed or anchor that's gone leaves the game falling back to the world spawn.
    val transition = player.findRespawnPositionAndUseSpawnBlock(false, TeleportTransition.DO_NOTHING)
    return if (transition.missingRespawnBlock()) null else Place(transition.newLevel(), transition.position())
  }

  /** The block where the player last died, as the game remembers it across runs. */
  fun lastDeath(player: ServerPlayer): Place? = player.lastDeathLocation.orElse(null)?.let { place(player.level().server, it) }

  private fun place(server: MinecraftServer, pos: GlobalPos): Place? =
    server.getLevel(pos.dimension())?.let { Place(it, Vec3(pos.pos().x.toDouble(), pos.pos().y.toDouble(), pos.pos().z.toDouble())) }

  /** The world spawn with the radius new players land within, from the `respawn_radius` game rule. */
  private fun worldSpawn(player: ServerPlayer): JsonElement {
    val level = respawnWorld(player.level().server)
    val pos = player.level().server.respawnData.pos()
    val spawn = describe(Place(level, Vec3(pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble())), player)
    if (spawn !is JsonObject) return spawn
    val radius = level.gameRules.get(GameRules.RESPAWN_RADIUS)
    return JsonObject(spawn + ("respawn_radius" to JsonPrimitive(radius)))
  }

  /** A place as the model needs it, with distance and direction when the player can walk there. */
  private fun describe(place: Place?, player: ServerPlayer): JsonElement {
    place ?: return JsonNull
    return buildJsonObject {
      put("x", place.x)
      put("y", place.y)
      put("z", place.z)
      put("dimension", Dimensions.id(place.level))
      if (place.level == player.level()) {
        val dx = place.point.x - player.x
        val dz = place.point.z - player.z
        put("distance", Math.round(Math.hypot(dx, dz)))
        put("direction", Facts.direction(dx, dz))
      }
    }
  }

  private val locations = object : Tool {
    override val name = "get_locations"

    override fun run(context: ToolContext, args: JsonObject): ToolOutput {
      val player = context.requirePlayer()
      val death = lastDeath(player)
      val respawn = respawnPoint(player)
      val result = buildJsonObject {
        put("respawn_point", describe(respawn, player))
        put("world_spawn", worldSpawn(player))
        put("last_death", describe(death, player))
      }
      val summary = if (death?.level == player.level()) {
        context.lang.text("summary.death_near", "blocks" to Math.round(death.point.distanceTo(player.position())))
      } else if (respawn != null) context.lang.text("summary.respawn_set") else context.lang.text("summary.respawn_none")
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
      val record = context.features.deaths.latest(player.uuid)
      // A death from before this run is known only by the game's last death point.
      val place = record?.let { player.level().server.allLevels.firstOrNull { level -> Worlds.name(level) == it.world }?.let { level -> Place(level, Vec3(it.x + 0.5, it.y.toDouble(), it.z + 0.5)) } }
        ?: lastDeath(player) ?: throw ToolError("this player hasn't died yet")
      val level = place.level
      val minutes = PlayerTools.stat(player, Stats.TIME_SINCE_DEATH) / 1_200
      val loaded = level.hasChunk(place.x shr 4, place.z shr 4)
      // Only items of the kinds the player dropped count, so someone else's junk nearby doesn't.
      val kinds = record?.drops?.keys.orEmpty()
      val items = if (loaded) {
        level.getEntitiesOfClass(ItemEntity::class.java, AABB.ofSize(place.point, 16.0, 16.0, 16.0)) { record == null || Recipes.id(it.item.item) in kinds }
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
          put("items_on_ground", items.sumOf { it.item.count })
          items.minOfOrNull { Deaths.DESPAWN_TICKS - it.tickCount }?.let { put("seconds_until_first_despawn", (it / 20).coerceAtLeast(0)) }
        }
      }
      val summary = if (level == player.level()) {
        context.lang.text("summary.death_distance", "blocks" to Math.round(place.point.distanceTo(player.position())))
      } else context.lang.text("summary.death_elsewhere")
      return ToolOutput(result, summary)
    }
  }

  val all = listOf<Tool>(locations, death)
}
