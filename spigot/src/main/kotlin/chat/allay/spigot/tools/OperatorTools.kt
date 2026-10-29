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

  /** The keys of a registry, as tools name them, for close matches in a refusal. */
  private fun keys(registry: Registry<*>) = registry.mapNotNull { (it as? org.bukkit.Keyed)?.key?.key }

  /**
   * What `give_item` hands out: the game's item, or a plugin's or data pack's item (`redbull:original`) as its recipe
   * makes it, since that's the only exact copy the server has of one. A potion type goes on a bottle or an arrow.
   */
  private fun template(context: ToolContext, id: String, potion: String?, firework: JsonObject? = null): ItemStack {
    // A firework to the player's description: colors, shape, fade, trail, crackle, flight.
    if (id in SpecialRecipes.FIREWORK_ITEMS && firework != null) return FireworkItems.of(id, Fireworks.parse(firework, id == "firework_star"), 1)
    val custom = Catalogs.build(context).find(id, Catalog.Kind.CUSTOM)
    if (custom != null) {
      return org.bukkit.Bukkit.recipeIterator().asSequence().map { it.result }.firstOrNull { !it.type.isAir && Catalogs.id(it) == custom.id }?.clone()?.apply { amount = 1 }
        ?: throw ToolError("${custom.id} comes from ${custom.source} and has no recipe to copy it from; look for that plugin's own command with search_commands")
    }
    val material = Material.matchMaterial(id)?.takeIf { it.isItem && !it.isAir }
    if (material == null) {
      if (Registry.POTION.get(key(id)) != null) throw ToolError("$id is a potion: pass item potion (or splash_potion, lingering_potion, tipped_arrow) and potion $id")
      throw ToolError(Names.missing(id, InteractTools.itemIds))
    }
    val stack = ItemStack(material)
    if (potion != null) {
      if (material.key.key !in OperatorArgs.POTION_ITEMS) throw ToolError("potion goes only with ${OperatorArgs.POTION_ITEMS.joinToString()}")
      val wanted = OperatorArgs.id(potion) ?: potion
      val type = Registry.POTION.get(key(wanted)) ?: throw ToolError("no potion called $wanted; close ones: ${Names.close(wanted, keys(Registry.POTION)).joinToString().ifEmpty { "none" }}")
      val meta = stack.itemMeta as org.bukkit.inventory.meta.PotionMeta
      meta.basePotionType = type
      stack.itemMeta = meta
    }
    return stack
  }

  private val give = tool("give_item") { context, player, args ->
    val id = OperatorArgs.id(args.string("item")) ?: throw ToolError("item is required")
    val template = template(context, id, args.string("potion"), args["firework"] as? JsonObject)
    val material = template.type
    val count = OperatorArgs.count(args.int("count"))
    val enchantments = (args["enchantments"] as? JsonObject)?.map { (name, level) ->
      val wanted = OperatorArgs.id(name) ?: name
      val enchantment = Registry.ENCHANTMENT.get(key(wanted))
        ?: throw ToolError("there is no enchantment called $wanted; close ones: ${Names.close(wanted, keys(Registry.ENCHANTMENT)).joinToString().ifEmpty { "none" }}")
      enchantment to OperatorArgs.enchantmentLevel((level as? JsonPrimitive)?.content?.toIntOrNull())
    }.orEmpty()
    val max = template.maxStackSize.coerceAtLeast(1)
    var dropped = 0
    var left = count
    while (left > 0) {
      val stack = template.clone().apply { amount = minOf(max, left) }
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
    val given = Catalogs.id(template)
    val result = buildJsonObject {
      put("item", given)
      args.string("potion")?.let { put("potion", (template.itemMeta as? org.bukkit.inventory.meta.PotionMeta)?.basePotionType?.key?.key) }
      put("count", count)
      if (dropped > 0) put("dropped_at_feet", dropped)
    }
    ToolOutput(result, context.lang.text("summary.given", "item" to given, "count" to count))
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
      val z = args.double("z")
      if (x == null || z == null) throw ToolError("give either player, or x and z (and y when known)")
      if (Math.abs(x) > 29_999_984 || Math.abs(z) > 29_999_984) throw ToolError("the point is outside the world")
      // Without y (structures come without one), the top of the ground there, so nobody lands in rock or the sky.
      val y = args.double("y") ?: (world.getHighestBlockYAt(Math.floor(x).toInt(), Math.floor(z).toInt()) + 1.0)
      if (y < world.minHeight || y > world.maxHeight + 64) throw ToolError("y must be ${world.minHeight}–${world.maxHeight + 64}")
      Location(world, x, y, z, player.location.yaw, player.location.pitch)
    }
    if (!player.teleport(target)) throw ToolError("the server refused the teleport")
    val danger = danger(target)
    val result = buildJsonObject {
      put("x", target.blockX)
      put("y", target.blockY)
      put("z", target.blockZ)
      put("dimension", Dimensions.id(target.world!!))
      if (name == null && args.double("y") == null) put("surface", true)
      put("safe", danger == null)
      danger?.let { put("danger", it) }
    }
    ToolOutput(result, name?.let { context.lang.text("summary.teleported_to_player", "player" to it) } ?: context.lang.text("summary.teleported", "x" to target.blockX, "y" to target.blockY, "z" to target.blockZ))
  }

  /** Why the player wouldn't stand safely where they are now, or null. */
  private fun danger(at: Location): String? {
    val feet = at.block
    val head = feet.getRelative(0, 1, 0)
    val ground = (1..64).firstOrNull { down -> feet.getRelative(0, -down, 0).let { it.type.isSolid || it.isLiquid || it.y <= at.world!!.minHeight } } ?: 64
    val lava = listOf(feet, head, feet.getRelative(0, -1, 0)).any { it.type == Material.LAVA }
    return OperatorArgs.danger(!feet.isPassable, !head.isPassable, lava, feet.isLiquid || head.isLiquid, ground - 1)
  }

  private val gamemode = tool("set_gamemode") { context, player, args ->
    val mode = OperatorArgs.gamemode(args.string("mode"))
    player.gameMode = GameMode.valueOf(mode.uppercase())
    // A plugin may cancel the change (a world with a forced mode): say what the player is in now.
    val now = player.gameMode.name.lowercase()
    val result = buildJsonObject {
      put("mode", now)
      if (now != mode) put("blocked", "a plugin or the world kept the player in $now")
    }
    ToolOutput(result, context.lang.text("summary.gamemode", "mode" to now), refused = now != mode)
  }

  private val heal = tool("heal") { context, player, _ ->
    val max = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
    player.health = max
    player.foodLevel = 20
    player.saturation = 20f
    player.fireTicks = 0
    // Poison, wither, and hunger would undo it in seconds.
    val cleared = player.activePotionEffects.filter { it.type.key.key in OperatorArgs.HARMFUL }.onEach { player.removePotionEffect(it.type) }.map { it.type.key.key }
    val result = buildJsonObject {
      put("health", round1(max))
      if (cleared.isNotEmpty()) put("cleared_effects", kotlinx.serialization.json.buildJsonArray { cleared.forEach { add(JsonPrimitive(it)) } })
    }
    ToolOutput(result, context.lang.text("summary.status", "health" to round1(max), "max" to round1(max), "food" to 20))
  }

  private val effect = tool("apply_effect") { context, player, args ->
    if (args.bool("clear") == true) {
      val count = player.activePotionEffects.size
      player.activePotionEffects.forEach { player.removePotionEffect(it.type) }
      return@tool ToolOutput(buildJsonObject { put("cleared", count) }, context.lang.text("summary.effects_cleared", "count" to count))
    }
    val id = OperatorArgs.id(args.string("effect")) ?: throw ToolError("effect is required, or clear: true")
    val type = Registry.EFFECT.get(key(id)) ?: throw ToolError("there is no effect called $id; close ones: ${Names.close(id, keys(Registry.EFFECT)).joinToString().ifEmpty { "none" }}")
    val seconds = OperatorArgs.effectSeconds(args.int("seconds"))
    val level = OperatorArgs.effectLevel(args.int("level"))
    player.addPotionEffect(PotionEffect(type, seconds * 20, level - 1))
    // The game keeps a stronger effect the player already has (a beacon's Haste II over Haste I): say what's on now.
    val now = player.getPotionEffect(type)
    val nowLevel = now?.amplifier?.plus(1) ?: level
    val nowSeconds = now?.duration?.let { if (it < 0) -1 else it / 20 } ?: seconds
    val result = buildJsonObject {
      put("effect", id)
      put("seconds", nowSeconds)
      put("level", nowLevel)
      // A higher level, a longer time, or an endless one (-1) the player had stays.
      if (nowLevel > level || nowSeconds < 0 || nowSeconds > seconds) put("kept_stronger", true)
    }
    ToolOutput(result, context.lang.text("summary.effect", "effect" to id, "level" to nowLevel, "seconds" to nowSeconds))
  }

  /** The world whose sky the player means: their own, or the overworld from the Nether or the End, which have none. */
  fun skyWorld(player: Player): World = player.world.takeIf { it.environment == World.Environment.NORMAL }
    ?: player.server.worlds.firstOrNull { it.environment == World.Environment.NORMAL } ?: player.world

  /** A boolean game rule by any of its names (they changed between versions); null when the server has none of them. */
  private fun rule(world: World, vararg names: String): Boolean? = names.firstNotNullOfOrNull { name ->
    NamespacedKey.fromString(name)?.let { Registry.GAME_RULE.get(it) }?.let { world.getGameRuleValue(it) as? Boolean }
  }

  private val time = tool("set_time") { context, player, args ->
    val ticks = OperatorArgs.time((args["time"] as? JsonPrimitive)?.content)
    val world = skyWorld(player)
    world.time = ticks
    // A plugin may cancel the skip: read back what the clock says.
    val now = world.time
    val result = buildJsonObject {
      put("time", now)
      put("world", Dimensions.id(world))
      if (rule(world, "advance_time", "do_daylight_cycle") == false) put("frozen", "the daylight cycle is off, so the time stays here")
      if (now != ticks) put("overridden", "a plugin kept the time at $now")
    }
    ToolOutput(result, context.lang.text("summary.time", "ticks" to now), refused = now != ticks)
  }

  private val weather = tool("set_weather") { context, player, args ->
    val weather = OperatorArgs.weather(args.string("weather"))
    val ticks = OperatorArgs.weatherSeconds(args.int("seconds")) * 20
    val world = skyWorld(player)
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
    // A plugin that locks the weather cancels the change: read back what the sky does.
    val now = if (world.isThundering) "thunder" else if (world.hasStorm()) "rain" else "clear"
    val result = buildJsonObject {
      put("weather", now)
      put("world", Dimensions.id(world))
      put("seconds", ticks / 20)
      if (rule(world, "advance_weather", "do_weather_cycle") == false) put("frozen", "the weather cycle is off, so it stays $now until changed again")
      if (now != weather) put("overridden", "a plugin kept the weather $now")
    }
    ToolOutput(result, context.lang.text("summary.weather", "weather" to context.lang.text("weather.$now")), refused = now != weather)
  }

  val all = listOf(give, teleport, gamemode, heal, effect, time, weather)
  val NAMES = all.map { it.name }.toSet()
}
