package chat.allay.spigot.tools

import chat.allay.core.*
import chat.allay.core.tools.*

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.World
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.EnchantmentStorageMeta
import org.bukkit.potion.PotionEffect
import chat.allay.core.tools.Facts.round1

/**
 * Tools that change the game for the asker. Only operators get them: the plugin lists them only
 * for operators, and every tool checks again when it runs, so a non-operator can't talk the
 * assistant into a sword.
 */
object OperatorTools {
  private fun tool(name: String, body: (ToolContext, Player, JsonObject) -> ToolOutput) = object : Tool {
    override val name = name
    override val operator = true

    override fun run(context: ToolContext, args: JsonObject): ToolOutput {
      val player = context.requirePlayer()
      if (!player.isOp) throw ToolError(NOT_OPERATOR)
      return body(context, player, args)
    }
  }

  const val NOT_OPERATOR = "only operators can do this"

  private fun key(id: String) = NamespacedKey.fromString(id) ?: throw ToolError("$id is not a valid id")

  private val give = tool("give_item") { context, player, args ->
    val id = OperatorArgs.id(args.string("item")) ?: throw ToolError("item is required")
    val material = Material.matchMaterial(id)?.takeIf { it.isItem && !it.isAir } ?: throw ToolError("there is no item called $id")
    val count = OperatorArgs.count(args.int("count"))
    val enchantments = (args["enchantments"] as? JsonObject)?.map { (name, level) ->
      val enchantment = Registry.ENCHANTMENT.get(key(OperatorArgs.id(name) ?: name)) ?: throw ToolError("there is no enchantment called $name")
      enchantment to OperatorArgs.enchantmentLevel((level as? JsonPrimitive)?.content?.toIntOrNull())
    }.orEmpty()
    val max = material.maxStackSize.coerceAtLeast(1)
    var dropped = 0
    var left = count
    while (left > 0) {
      val stack = ItemStack(material, minOf(max, left))
      // A book stores its enchantments for an anvil; anything else takes them on itself, any level on any item.
      val book = stack.itemMeta as? EnchantmentStorageMeta
      if (book != null) {
        enchantments.forEach { (enchantment, level) -> book.addStoredEnchant(enchantment, level, true) }
        stack.itemMeta = book
      } else {
        enchantments.forEach { (enchantment, level) -> stack.addUnsafeEnchantment(enchantment, level) }
      }
      left -= stack.amount
      player.inventory.addItem(stack).values.forEach { rest ->
        dropped += rest.amount
        player.world.dropItemNaturally(player.location, rest)
      }
    }
    val result = buildJsonObject {
      put("item", material.key.key)
      put("count", count)
      if (dropped > 0) put("dropped_at_feet", dropped)
    }
    ToolOutput(result, context.lang.text("summary.given", "item" to material.key.key, "count" to count))
  }

  private fun worldFor(player: Player, dimension: String?): World {
    val wanted = dimension?.let(Dimensions::parse) ?: return player.world
    return player.server.worlds.firstOrNull { Dimensions.id(it) == wanted } ?: throw ToolError("this server has no $wanted world")
  }

  private val teleport = tool("teleport") { context, player, args ->
    val name = args.string("player")
    val target = if (name != null) {
      val other = (context.playerNamed(name) ?: player.server.getPlayerExact(name))?.takeIf { it.isOnline } ?: throw ToolError("player $name is offline")
      other.location
    } else {
      val world = worldFor(player, args.string("dimension"))
      // The schema is flat (no unions), so either a player or all three coordinates must be there.
      val x = args.double("x")
      val y = args.double("y")
      val z = args.double("z")
      if (x == null || y == null || z == null) throw ToolError("give either player, or all of x, y, and z")
      if (y < world.minHeight || y > world.maxHeight + 64) throw ToolError("y must be ${world.minHeight}–${world.maxHeight + 64}")
      if (Math.abs(x) > 29_999_984 || Math.abs(z) > 29_999_984) throw ToolError("the point is outside the world")
      Location(world, x, y, z, player.location.yaw, player.location.pitch)
    }
    if (!player.teleport(target)) throw ToolError("the server refused the teleport")
    val result = buildJsonObject {
      put("x", target.blockX)
      put("y", target.blockY)
      put("z", target.blockZ)
      put("dimension", Dimensions.id(target.world!!))
    }
    ToolOutput(result, name?.let { context.lang.text("summary.teleported_to_player", "player" to it) } ?: context.lang.text("summary.teleported", "x" to target.blockX, "y" to target.blockY, "z" to target.blockZ))
  }

  private val gamemode = tool("set_gamemode") { context, player, args ->
    val mode = OperatorArgs.gamemode(args.string("mode"))
    player.gameMode = GameMode.valueOf(mode.uppercase())
    ToolOutput(buildJsonObject { put("mode", mode) }, context.lang.text("summary.gamemode", "mode" to mode))
  }

  private val heal = tool("heal") { context, player, _ ->
    val max = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
    player.health = max
    player.foodLevel = 20
    player.saturation = 20f
    player.fireTicks = 0
    ToolOutput(buildJsonObject { put("health", round1(max)) }, context.lang.text("summary.status", "health" to round1(max), "max" to round1(max), "food" to 20))
  }

  private val effect = tool("apply_effect") { context, player, args ->
    if (args.bool("clear") == true) {
      val count = player.activePotionEffects.size
      player.activePotionEffects.forEach { player.removePotionEffect(it.type) }
      return@tool ToolOutput(buildJsonObject { put("cleared", count) }, context.lang.text("summary.effects_cleared", "count" to count))
    }
    val id = OperatorArgs.id(args.string("effect")) ?: throw ToolError("effect is required, or clear: true")
    val type = Registry.EFFECT.get(key(id)) ?: throw ToolError("there is no effect called $id")
    val seconds = OperatorArgs.effectSeconds(args.int("seconds"))
    val level = OperatorArgs.effectLevel(args.int("level"))
    player.addPotionEffect(PotionEffect(type, seconds * 20, level - 1))
    ToolOutput(buildJsonObject { put("effect", id); put("seconds", seconds); put("level", level) }, context.lang.text("summary.effect", "effect" to id, "level" to level, "seconds" to seconds))
  }

  private val time = tool("set_time") { context, player, args ->
    val ticks = OperatorArgs.time((args["time"] as? JsonPrimitive)?.content)
    player.world.time = ticks
    ToolOutput(buildJsonObject { put("time", ticks) }, context.lang.text("summary.time", "ticks" to ticks))
  }

  private val weather = tool("set_weather") { context, player, args ->
    val weather = OperatorArgs.weather(args.string("weather"))
    val ticks = OperatorArgs.weatherSeconds(args.int("seconds")) * 20
    val world = player.world
    when (weather) {
      "clear" -> {
        world.setStorm(false)
        world.isThundering = false
        world.clearWeatherDuration = ticks
      }
      else -> {
        world.setStorm(true)
        world.weatherDuration = ticks
        world.isThundering = weather == "thunder"
        if (weather == "thunder") world.thunderDuration = ticks
      }
    }
    ToolOutput(buildJsonObject { put("weather", weather); put("seconds", ticks / 20) }, context.lang.text("summary.weather", "weather" to context.lang.text("weather.$weather")))
  }

  val all = listOf(give, teleport, gamemode, heal, effect, time, weather)
  val NAMES = all.map { it.name }.toSet()
}
