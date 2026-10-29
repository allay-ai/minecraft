package chat.allay.spigot.tools

import chat.allay.core.*
import chat.allay.core.tools.*

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.bukkit.Bukkit
import org.bukkit.Registry
import org.bukkit.World
import chat.allay.core.tools.Facts.round1

/** Tools about the world around the asker and the server itself. */
object WorldTools {
  /** Rules that change advice; names differ between versions, so both spellings are tried. */
  private val RULES = setOf(
    "keep_inventory", "keepInventory", "mob_griefing", "mobGriefing", "players_sleeping_percentage", "playersSleepingPercentage",
    "do_insomnia", "doInsomnia", "spawn_phantoms", "natural_regeneration", "naturalRegeneration", "do_immediate_respawn", "doImmediateRespawn",
    "advance_time", "do_daylight_cycle", "doDaylightCycle", "advance_weather", "do_weather_cycle", "doWeatherCycle", "fall_damage", "fallDamage",
  )

  private fun worldOf(context: ToolContext): World = context.player?.world ?: Bukkit.getWorlds().first()

  private val world = object : Tool {
    override val name = "get_world"

    override fun run(context: ToolContext, args: JsonObject): ToolOutput {
      val world = worldOf(context)
      val day = world.fullTime / 24_000 + 1
      val phase = Facts.dayPhase(world.time)
      val result = buildJsonObject {
        put("dimension", Dimensions.id(world))
        put("time", world.time)
        put("phase", phase)
        put("day", day)
        put("moon", Facts.moonPhase(world.fullTime))
        put("weather", if (world.isThundering) "thunder" else if (world.hasStorm()) "rain" else "clear")
        put("difficulty", world.difficulty.name.lowercase())
        // Through the registry on purpose: GameRule is an interface in Spigot and a class in Paper,
        // so a jar built against Spigot breaks on Paper when it touches GameRule's static members.
        putJsonObject("rules") {
          world.gameRules.filter { it in RULES }.forEach { name ->
            val rule = org.bukkit.NamespacedKey.fromString(name.lowercase())?.let { Registry.GAME_RULE.get(it) } ?: return@forEach
            when (val value = world.getGameRuleValue(rule)) {
              is Boolean -> put(name, value)
              is Number -> put(name, value)
              else -> {}
            }
          }
        }
        put("border", round1(world.worldBorder.size))
        // From the Nether or the End, the sky players mean (can I sleep, is it raining up there) is the overworld's.
        context.player?.let(OperatorTools::skyWorld)?.takeIf { it != world }?.let { sky ->
          putJsonObject("overworld") {
            put("time", sky.time)
            put("phase", Facts.dayPhase(sky.time))
            put("weather", if (sky.isThundering) "thunder" else if (sky.hasStorm()) "rain" else "clear")
          }
        }
      }
      return ToolOutput(result, context.lang.text("summary.world", "day" to day, "phase" to context.lang.text("phases.$phase")))
    }
  }

  private val server = object : Tool {
    override val name = "get_server"

    override fun run(context: ToolContext, args: JsonObject): ToolOutput {
      val server = Bukkit.getServer()
      val version = version(server.bukkitVersion)
      // Vanished players stay out of the count, unless the asker is one of them or an operator.
      val online = chat.allay.spigot.Vanish.visibleTo(context.sender, server.onlinePlayers).size.toLong()
      val result = buildJsonObject {
        put("software", server.name)
        put("version", version)
        put("online", online)
        put("max_players", server.maxPlayers)
        put("plugin_version", org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(WorldTools::class.java).description.version)
        val hidden = context.features.config().tools.hiddenPlugins
        put("plugins", buildJsonArray {
          server.pluginManager.plugins.filter { it.isEnabled && it.name.lowercase() !in hidden }.sortedBy { it.name.lowercase() }.forEach { plugin ->
            add(buildJsonObject {
              put("name", plugin.name)
              put("version", plugin.description.version)
              plugin.description.description?.let(Facts::shortDescription)?.let { put("description", it) }
            })
          }
        })
        datapacks(hidden).let { (packs, features) ->
          put("datapacks", buildJsonArray { packs.forEach { (name, description) -> add(buildJsonObject { put("name", name); description?.let(Facts::shortDescription)?.let { put("description", it) } }) } })
          if (features.isNotEmpty()) put("experiments", buildJsonArray { features.forEach { add(JsonPrimitive(it)) } })
        }
      }
      return ToolOutput(result, context.lang.text("summary.server_info", "software" to server.name, "version" to version, "online" to online))
    }
  }

  /**
   * The data packs the worlds run, a server owner's or the world's own (not the game's or a plugin's), and the game's
   * experiments turned on: what adds recipes, items' looks, structures, and rules a model can't know.
   */
  fun datapacks(hidden: Set<String>): Pair<List<Pair<String, String?>>, List<String>> {
    val (packs, features) = if (Catalogs.paper) runCatching { chat.allay.spigot.paper.PaperCatalog.datapacks() }.getOrDefault(emptyList<Pair<String, String?>>() to emptyList()) else runCatching {
      val all = Bukkit.getDataPackManager().getEnabledDataPacks(Bukkit.getWorlds().first())
      all.filter { it.source == org.bukkit.packs.DataPack.Source.WORLD || it.source == org.bukkit.packs.DataPack.Source.SERVER }.map { it.key.key.removePrefix("file/") to Legacy.strip(it.description).ifBlank { null } } to
        all.filter { it.source == org.bukkit.packs.DataPack.Source.FEATURE }.map { it.key.key }
    }.getOrDefault(emptyList<Pair<String, String?>>() to emptyList())
    return packs.filter { it.first.lowercase() !in hidden } to features
  }

  private val locate = object : Tool {
    override val name = "locate_structure"

    override fun run(context: ToolContext, args: JsonObject): ToolOutput {
      val player = context.requirePlayer()
      val wanted = (args["structure"] as? JsonPrimitive)?.contentOrNull?.lowercase()?.removePrefix("minecraft:")?.trim()
        ?.takeIf { it.isNotEmpty() } ?: throw ToolError("say which structure, for example village or stronghold")
      // Searching runs on the server thread, so only a few candidates and a capped radius.
      val all = Registry.STRUCTURE.associateBy { it.key.key }
      val candidates = Structures.match(wanted, all.keys).mapNotNull(all::get)
      if (candidates.isEmpty()) throw ToolError(Structures.missing(wanted, all.keys))
      val origin = player.location
      val found = candidates.mapNotNull { player.world.locateNearestStructure(origin, it, context.structureRadius, false) }
        .minByOrNull { it.location.distanceSquared(origin) }
        ?: return ToolOutput(buildJsonObject {
          put("found", false)
          put("searched", buildJsonArray { candidates.forEach { add(JsonPrimitive(it.key.key)) } })
          put("radius_blocks", context.structureRadius * 16)
          // A village looked for in the Nether: the dimension tells the model it's the wrong place, not too far.
          put("dimension", Dimensions.id(player.world))
        }, context.lang.text("summary.not_found"), refused = true)
      val at = found.location
      val dx = at.x - origin.x
      val dz = at.z - origin.z
      val distance = Math.round(Math.hypot(dx, dz))
      val direction = Facts.direction(dx, dz)
      val result = buildJsonObject {
        put("found", true)
        put("structure", found.structure.key.key)
        put("x", at.blockX)
        put("z", at.blockZ)
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
      // “cherry” finds cherry_grove, “badlands” all three badlands; a handful at most, since the search runs on the server thread.
      val candidates = Registry.BIOME.filter { it.key.key == wanted }.ifEmpty { Registry.BIOME.filter { wanted in it.key.key } }.take(6)
      if (candidates.isEmpty()) throw ToolError("no biome called $wanted")
      val origin = player.location
      val radius = context.features.biomeRadius()
      // The same steps as /locate biome: every 32 blocks across, every 64 up and down.
      val found = player.world.locateNearestBiome(origin, radius, 32, 64, *candidates.toTypedArray())
        ?: return ToolOutput(buildJsonObject { put("found", false); put("radius_blocks", radius) }, context.lang.text("summary.not_found"), refused = true)
      val at = found.location
      val dx = at.x - origin.x
      val dz = at.z - origin.z
      val distance = Math.round(Math.hypot(dx, dz))
      val direction = Facts.direction(dx, dz)
      val result = buildJsonObject {
        put("found", true)
        put("biome", found.biome.key.key)
        put("x", at.blockX)
        put("y", at.blockY)
        put("z", at.blockZ)
        put("distance", distance)
        put("direction", direction)
      }
      return ToolOutput(result, context.lang.text("summary.distance", "blocks" to distance, "direction" to context.lang.text("directions.$direction")))
    }
  }

  /** `26.3-R0.1-SNAPSHOT` on Spigot and `26.3.build.169-beta` on Paper both mean 26.3. */
  fun version(bukkitVersion: String): String = Regex("^\\d+(\\.\\d+)*").find(bukkitVersion)?.value?.let { found ->
    if (".build" in bukkitVersion) bukkitVersion.substringBefore(".build") else found
  } ?: bukkitVersion

  val all = listOf(world, server, locate, biome)
}
