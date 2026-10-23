package chat.allay.fabric.tools

import chat.allay.core.*
import chat.allay.core.tools.*
import chat.allay.fabric.automation.Slots

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.permissions.Permissions
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items as GameItems
import net.minecraft.world.level.GameType
import chat.allay.core.tools.Facts.round1

/**
 * Tools that change the game for the asker. Only operators get them: the mod lists them only for operators, and
 * every tool checks again when it runs, so a non-operator can't talk the assistant into a sword.
 */
object OperatorTools {
  /** Bukkit's `isOp()`: the permission level that game masters' commands need. */
  fun operator(player: ServerPlayer) = player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)

  private fun tool(name: String, body: (ToolContext, ServerPlayer, JsonObject) -> ToolOutput) = object : Tool {
    override val name = name
    override val operator = true

    override fun run(context: ToolContext, args: JsonObject): ToolOutput {
      val player = context.requirePlayer()
      if (!operator(player)) throw ToolError(NOT_OPERATOR)
      return body(context, player, args)
    }
  }

  const val NOT_OPERATOR = "only operators can do this"

  private fun key(id: String) = Identifier.tryParse(id) ?: throw ToolError("$id is not a valid id")

  private val give = tool("give_item") { context, player, args ->
    val id = OperatorArgs.id(args.string("item")) ?: throw ToolError("item is required")
    val item = Identifier.tryParse(id)?.let { BuiltInRegistries.ITEM.getOptional(it).orElse(null) }?.takeIf { it != GameItems.AIR } ?: throw ToolError("there is no item called $id")
    val count = OperatorArgs.count(args.int("count"))
    val enchantments = (args["enchantments"] as? JsonObject)?.map { (name, level) ->
      val enchantment = Anvils.registry(player).get(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENCHANTMENT, key(OperatorArgs.id(name) ?: name))).orElse(null)
        ?: throw ToolError("there is no enchantment called $name")
      enchantment to OperatorArgs.enchantmentLevel((level as? JsonPrimitive)?.content?.toIntOrNull())
    }.orEmpty()
    val max = item.defaultMaxStackSize.coerceAtLeast(1)
    var dropped = 0
    var left = count
    while (left > 0) {
      val stack = ItemStack(item, minOf(max, left))
      // Any level on any item, as Bukkit's `addUnsafeEnchantment`; a book stores them for an anvil, as the plugin does.
      enchantments.forEach { (enchantment, level) -> stack.enchant(enchantment, level) }
      left -= stack.count
      // As Bukkit's `addItem`: the game's own `add` voids what doesn't fit in creative, and nothing would fall.
      Slots.add(player, stack)?.let { rest ->
        dropped += rest.count
        Slots.drop(player, rest)
      }
    }
    val result = buildJsonObject {
      put("item", Recipes.id(item))
      put("count", count)
      if (dropped > 0) put("dropped_at_feet", dropped)
    }
    ToolOutput(result, context.lang.text("summary.given", "item" to Recipes.id(item), "count" to count))
  }

  private fun worldFor(player: ServerPlayer, dimension: String?): ServerLevel {
    val wanted = dimension?.let(Dimensions::parse) ?: return player.level()
    return player.level().server.allLevels.firstOrNull { Dimensions.id(it) == wanted } ?: throw ToolError("this server has no $wanted world")
  }

  private val teleport = tool("teleport") { context, player, args ->
    val name = args.string("player")
    val level: ServerLevel
    val x: Double
    val y: Double
    val z: Double
    var yaw = player.yRot
    var pitch = player.xRot
    if (name != null) {
      // An operator may go to anyone by name, a vanished player too.
      val other = (context.playerNamed(name) ?: player.level().server.playerList.getPlayerByName(name))?.takeUnless { it.hasDisconnected() } ?: throw ToolError("player $name is offline")
      level = other.level()
      x = other.x
      y = other.y
      z = other.z
      yaw = other.yRot
      pitch = other.xRot
    } else {
      level = worldFor(player, args.string("dimension"))
      // The schema is flat (no unions), so either a player or all three coordinates must be there.
      x = args.double("x") ?: throw ToolError("give either player, or all of x, y, and z")
      y = args.double("y") ?: throw ToolError("give either player, or all of x, y, and z")
      z = args.double("z") ?: throw ToolError("give either player, or all of x, y, and z")
      // Bukkit's `maxHeight` is the first height above the world.
      val top = level.maxY + 1
      if (y < level.minY || y > top + 64) throw ToolError("y must be ${level.minY}–${top + 64}")
      if (Math.abs(x) > 29_999_984 || Math.abs(z) > 29_999_984) throw ToolError("the point is outside the world")
    }
    // As Bukkit: a player with passengers can't change worlds; a rider steps off first.
    if (level != player.level() && player.isVehicle) throw ToolError("the server refused the teleport")
    player.stopRiding()
    if (!player.teleportTo(level, x, y, z, emptySet(), yaw, pitch, true)) throw ToolError("the server refused the teleport")
    val bx = net.minecraft.util.Mth.floor(x)
    val by = net.minecraft.util.Mth.floor(y)
    val bz = net.minecraft.util.Mth.floor(z)
    val result = buildJsonObject {
      put("x", bx)
      put("y", by)
      put("z", bz)
      put("dimension", Dimensions.id(level))
    }
    ToolOutput(result, name?.let { context.lang.text("summary.teleported_to_player", "player" to it) } ?: context.lang.text("summary.teleported", "x" to bx, "y" to by, "z" to bz))
  }

  private val gamemode: Tool = tool("set_gamemode") { context, player, args ->
    val mode = OperatorArgs.gamemode(args.string("mode"))
    player.setGameMode(GameType.byName(mode))
    ToolOutput(buildJsonObject { put("mode", mode) }, context.lang.text("summary.gamemode", "mode" to mode))
  }

  private val heal = tool("heal") { context, player, _ ->
    val max = player.getAttribute(Attributes.MAX_HEALTH)?.value ?: 20.0
    player.health = max.toFloat()
    player.foodData.foodLevel = 20
    player.foodData.setSaturation(20f)
    player.remainingFireTicks = 0
    ToolOutput(buildJsonObject { put("health", round1(max)) }, context.lang.text("summary.status", "health" to round1(max), "max" to round1(max), "food" to 20))
  }

  private val effect = tool("apply_effect") { context, player, args ->
    if (args.bool("clear") == true) {
      val count = player.activeEffects.size
      player.activeEffects.map { it.effect }.forEach { player.removeEffect(it) }
      return@tool ToolOutput(buildJsonObject { put("cleared", count) }, context.lang.text("summary.effects_cleared", "count" to count))
    }
    val id = OperatorArgs.id(args.string("effect")) ?: throw ToolError("effect is required, or clear: true")
    val type = BuiltInRegistries.MOB_EFFECT.get(key(id)).orElse(null) ?: throw ToolError("there is no effect called $id")
    val seconds = OperatorArgs.effectSeconds(args.int("seconds"))
    val level = OperatorArgs.effectLevel(args.int("level"))
    player.addEffect(MobEffectInstance(type, seconds * 20, level - 1))
    ToolOutput(buildJsonObject { put("effect", id); put("seconds", seconds); put("level", level) }, context.lang.text("summary.effect", "effect" to id, "level" to level, "seconds" to seconds))
  }

  private val time: Tool = tool("set_time") { context, player, args ->
    val ticks = OperatorArgs.time((args["time"] as? JsonPrimitive)?.content)
    val level = player.level()
    // As Bukkit's `World.setTime`: forward to the next such time of day, on the dimension's own clock.
    val clock = level.dimensionType().defaultClock().orElse(null) ?: throw ToolError("Cannot set time in world without world clock")
    val full = WorldTools.fullTime(level)
    level.clockManager().setTotalTicks(clock, full + Math.floorMod(ticks - full, 24_000L))
    ToolOutput(buildJsonObject { put("time", ticks) }, context.lang.text("summary.time", "ticks" to ticks))
  }

  private val weather: Tool = tool("set_weather") { context, player, args ->
    val weather = OperatorArgs.weather(args.string("weather"))
    val ticks = OperatorArgs.weatherSeconds(args.int("seconds")) * 20
    // The same steps as Bukkit's `setStorm`, `setThundering`, and the durations.
    val data = player.level().weatherData
    when (weather) {
      "clear" -> {
        data.isRaining = false
        data.rainTime = 0
        data.isThundering = false
        data.thunderTime = 0
        data.clearWeatherTime = ticks
      }
      else -> {
        data.isRaining = true
        data.clearWeatherTime = 0
        data.rainTime = ticks
        data.isThundering = weather == "thunder"
        data.thunderTime = if (weather == "thunder") ticks else 0
      }
    }
    ToolOutput(buildJsonObject { put("weather", weather); put("seconds", ticks / 20) }, context.lang.text("summary.weather", "weather" to context.lang.text("weather.$weather")))
  }

  val all = listOf(give, teleport, gamemode, heal, effect, time, weather)
  val NAMES = all.map { it.name }.toSet()
}
