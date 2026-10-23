package chat.allay.modcore.tools

import chat.allay.core.*
import chat.allay.core.tools.*
import chat.allay.modcore.ModSender
import chat.allay.modcore.Vanish

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import chat.allay.modcore.Loader
import chat.allay.modcore.Mod
import net.minecraft.SharedConstants
import net.minecraft.core.HolderSet
import net.minecraft.core.registries.Registries
import net.minecraft.server.level.ServerLevel
import chat.allay.core.tools.Facts.round1

/** Tools about the world around the asker and the server itself. */
object WorldTools {
  /** Rules that change advice; names differ between versions, so both spellings are tried. */
  private val RULES = setOf(
    "keep_inventory", "keepInventory", "mob_griefing", "mobGriefing", "players_sleeping_percentage", "playersSleepingPercentage",
    "do_insomnia", "doInsomnia", "spawn_phantoms", "natural_regeneration", "naturalRegeneration", "do_immediate_respawn", "doImmediateRespawn",
    "advance_time", "do_daylight_cycle", "doDaylightCycle", "advance_weather", "do_weather_cycle", "doWeatherCycle", "fall_damage", "fallDamage",
  )

  private fun worldOf(context: ToolContext): ServerLevel = context.player?.level() ?: context.server.overworld()

  /** The world's clock as Bukkit's `getFullTime` reads it: the dimension's default clock, 0 in one without. */
  fun fullTime(level: ServerLevel): Long = level.defaultClockTime

  private val world = object : Tool {
    override val name = "get_world"

    override fun run(context: ToolContext, args: JsonObject): ToolOutput {
      val world = worldOf(context)
      val fullTime = fullTime(world)
      val time = Math.floorMod(fullTime, 24_000L)
      val day = fullTime / 24_000 + 1
      val phase = Facts.dayPhase(time)
      val weather = world.weatherData
      val result = buildJsonObject {
        put("dimension", Dimensions.id(world))
        put("time", time)
        put("phase", phase)
        put("day", day)
        put("moon", Facts.moonPhase(fullTime))
        put("weather", if (weather.isThundering) "thunder" else if (weather.isRaining) "rain" else "clear")
        put("difficulty", world.difficulty.serializedName)
        putJsonObject("rules") {
          world.gameRules.availableRules().filter { it.id() in RULES }.forEach { rule ->
            when (val value: Any? = world.gameRules.get(rule)) {
              is Boolean -> put(rule.id(), value)
              is Number -> put(rule.id(), value)
              else -> {}
            }
          }
        }
        put("border", round1(world.worldBorder.size))
      }
      return ToolOutput(result, context.lang.text("summary.world", "day" to day, "phase" to context.lang.text("phases.$phase")))
    }
  }

  /** The mods a player would name: not the game, Java, the loader, or the libraries mods carry inside them. */
  fun mods(hidden: Set<String>): List<Loader.ModInfo> = Mod.loader.mods()
    .filter { mod -> mod.id.lowercase() !in hidden && mod.name.lowercase() !in hidden }
    .sortedBy { it.name.lowercase() }

  private val server = object : Tool {
    override val name = "get_server"

    override fun run(context: ToolContext, args: JsonObject): ToolOutput {
      val server = context.server
      val version = SharedConstants.getCurrentVersion().name()
      // Vanished players stay out of the count, unless the asker is one of them or an operator.
      val source = (context.asker as? ModSender)?.source
      val online = server.playerList.players.count { source == null || Vanish.visible(source, it) }.toLong()
      val result = buildJsonObject {
        put("software", SOFTWARE)
        put("version", version)
        put("online", online)
        put("max_players", server.playerList.maxPlayers)
        put("plugin_version", context.features.mod.version)
        // Mods stand where Bukkit lists plugins: the model reads the same keys on both.
        put("plugins", buildJsonArray {
          mods(context.features.config().tools.hiddenPlugins).forEach { mod ->
            add(buildJsonObject {
              put("name", mod.name)
              put("version", mod.version)
              mod.description?.let(Facts::shortDescription)?.let { put("description", it) }
            })
          }
        })
      }
      return ToolOutput(result, context.lang.text("summary.server_info", "software" to SOFTWARE, "version" to version, "online" to online))
    }
  }

  /** What the server runs, where Bukkit says CraftBukkit or Paper. */
  val SOFTWARE: String get() = Mod.loader.software

  private val locate = object : Tool {
    override val name = "locate_structure"

    override fun run(context: ToolContext, args: JsonObject): ToolOutput {
      val player = context.requirePlayer()
      val wanted = (args["structure"] as? JsonPrimitive)?.contentOrNull?.lowercase()?.removePrefix("minecraft:")?.trim()
        ?.takeIf { it.isNotEmpty() } ?: throw ToolError("say which structure, for example village or stronghold")
      val level = player.level()
      // Searching runs on the server thread, so only a few candidates and a capped radius.
      val candidates = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).listElements().toList()
        .filter { it.key().identifier().path == wanted || it.key().identifier().path.startsWith("${wanted}_") }.take(6)
      if (candidates.isEmpty()) throw ToolError("no structure called $wanted")
      val origin = player.position()
      val found = candidates.mapNotNull { level.chunkSource.generator.findNearestMapStructure(level, HolderSet.direct(it), player.blockPosition(), context.structureRadius, false) }
        .minByOrNull { it.first.distToLowCornerSqr(origin.x, origin.y, origin.z) }
        ?: return ToolOutput(buildJsonObject { put("found", false); put("radius_blocks", context.structureRadius * 16) }, context.lang.text("summary.not_found"))
      val at = found.first
      val dx = at.x - origin.x
      val dz = at.z - origin.z
      val distance = Math.round(Math.hypot(dx, dz))
      val direction = Facts.direction(dx, dz)
      val result = buildJsonObject {
        put("found", true)
        put("structure", Worlds.key(found.second))
        put("x", at.x)
        put("z", at.z)
        put("distance", distance)
        put("direction", direction)
      }
      return ToolOutput(result, context.lang.text("summary.distance", "blocks" to distance, "direction" to context.lang.text("directions.$direction")))
    }
  }

  private val biome = object : Tool {
    override val name = "locate_biome"

    override fun run(context: ToolContext, args: JsonObject): ToolOutput {
      val player = context.requirePlayer()
      val wanted = (args["biome"] as? JsonPrimitive)?.contentOrNull?.lowercase()?.removePrefix("minecraft:")?.trim()?.replace(' ', '_')
        ?.takeIf { it.isNotEmpty() } ?: throw ToolError("say which biome, for example cherry_grove or mushroom_fields")
      val level = player.level()
      val biomes = level.registryAccess().lookupOrThrow(Registries.BIOME).listElements().toList()
      // “cherry” finds cherry_grove, “badlands” all three badlands; a handful at most, since the search runs on the server thread.
      val candidates = biomes.filter { it.key().identifier().path == wanted }.ifEmpty { biomes.filter { wanted in it.key().identifier().path } }.take(6)
      if (candidates.isEmpty()) throw ToolError("no biome called $wanted")
      val origin = player.position()
      val radius = context.features.biomeRadius()
      // The same steps as /locate biome: every 32 blocks across, every 64 up and down.
      val keys = candidates.map { it.key() }.toSet()
      val found = level.findClosestBiome3d({ biome -> biome.unwrapKey().map(keys::contains).orElse(false) }, player.blockPosition(), radius, 32, 64)
        ?: return ToolOutput(buildJsonObject { put("found", false); put("radius_blocks", radius) }, context.lang.text("summary.not_found"))
      val at = found.first
      val dx = at.x - origin.x
      val dz = at.z - origin.z
      val distance = Math.round(Math.hypot(dx, dz))
      val direction = Facts.direction(dx, dz)
      val result = buildJsonObject {
        put("found", true)
        put("biome", Worlds.key(found.second))
        put("x", at.x)
        put("y", at.y)
        put("z", at.z)
        put("distance", distance)
        put("direction", direction)
      }
      return ToolOutput(result, context.lang.text("summary.distance", "blocks" to distance, "direction" to context.lang.text("directions.$direction")))
    }
  }

  val all = listOf(world, server, locate, biome)
}
