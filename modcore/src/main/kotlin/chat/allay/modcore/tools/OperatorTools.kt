package chat.allay.modcore.tools

import chat.allay.core.*
import chat.allay.core.tools.*
import chat.allay.modcore.automation.Slots

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

  /** The ids of a registry, as tools name them, for close matches in a refusal. */
  private fun keys(registry: net.minecraft.core.Registry<*>) = registry.keySet().map { Worlds.key(it) }

  /**
   * What `give_item` hands out: the game's item, or a mod's or data pack's item (`redbull:original`) as its recipe makes
   * it, since that's the only exact copy the server has of one. A potion type goes on a bottle or an arrow.
   */
  private fun template(context: ToolContext, player: ServerPlayer, id: String, potion: String?, firework: JsonObject? = null): ItemStack {
    // A firework to the player's description: colors, shape, fade, trail, crackle, flight.
    if (id in SpecialRecipes.FIREWORK_ITEMS && firework != null) return FireworkItems.of(id, Fireworks.parse(firework, id == "firework_star"), 1)
    val custom = Catalogs.build(context).find(id, Catalog.Kind.CUSTOM)
    if (custom != null) {
      return Recipes.all(player.level().server).asSequence().mapNotNull { Recipes.result(it.value()) }.firstOrNull { !it.isEmpty && Catalogs.id(it) == custom.id }?.copyWithCount(1)
        ?: throw ToolError("${custom.id} comes from ${custom.source} and has no recipe to copy it from; look for that mod's own command with search_commands")
    }
    val item = Identifier.tryParse(id)?.let { BuiltInRegistries.ITEM.getOptional(it).orElse(null) }?.takeIf { it != GameItems.AIR }
    if (item == null) {
      if (Identifier.tryParse(id)?.let { BuiltInRegistries.POTION.containsKey(it) } == true) throw ToolError("$id is a potion: pass item potion (or splash_potion, lingering_potion, tipped_arrow) and potion $id")
      throw ToolError(Names.missing(id, InteractTools.itemIds))
    }
    val stack = ItemStack(item)
    if (potion != null) {
      if (Recipes.id(item) !in OperatorArgs.POTION_ITEMS) throw ToolError("potion goes only with ${OperatorArgs.POTION_ITEMS.joinToString()}")
      val wanted = OperatorArgs.id(potion) ?: potion
      val type = BuiltInRegistries.POTION.get(key(wanted)).orElse(null)
        ?: throw ToolError("no potion called $wanted; close ones: ${Names.close(wanted, keys(BuiltInRegistries.POTION)).joinToString().ifEmpty { "none" }}")
      stack.set(net.minecraft.core.component.DataComponents.POTION_CONTENTS, net.minecraft.world.item.alchemy.PotionContents(type))
    }
    return stack
  }

  private val give = tool("give_item") { context, player, args ->
    val id = OperatorArgs.id(args.string("item")) ?: throw ToolError("item is required")
    val template = template(context, player, id, args.string("potion"), args["firework"] as? JsonObject)
    val count = OperatorArgs.count(args.int("count"))
    val enchantments = (args["enchantments"] as? JsonObject)?.map { (name, level) ->
      val wanted = OperatorArgs.id(name) ?: name
      val registry = Anvils.registry(player)
      val enchantment = registry.get(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENCHANTMENT, key(wanted))).orElse(null)
        ?: throw ToolError("there is no enchantment called $wanted; close ones: ${Names.close(wanted, registry.listElementIds().map { Worlds.key(it.identifier()) }.toList()).joinToString().ifEmpty { "none" }}")
      enchantment to OperatorArgs.enchantmentLevel((level as? JsonPrimitive)?.content?.toIntOrNull())
    }.orEmpty()
    val max = template.maxStackSize.coerceAtLeast(1)
    var dropped = 0
    var left = count
    while (left > 0) {
      val stack = template.copyWithCount(minOf(max, left))
      // Any level on any item, as Bukkit's `addUnsafeEnchantment`; a book stores them for an anvil, as the plugin does.
      enchantments.forEach { (enchantment, level) -> stack.enchant(enchantment, level) }
      left -= stack.count
      // As Bukkit's `addItem`: the game's own `add` voids what doesn't fit in creative, and nothing would fall.
      Slots.add(player, stack)?.let { rest ->
        dropped += rest.count
        Slots.drop(player, rest)
      }
    }
    val given = Catalogs.id(template)
    val result = buildJsonObject {
      put("item", given)
      args.string("potion")?.let { put("potion", template.get(net.minecraft.core.component.DataComponents.POTION_CONTENTS)?.potion()?.orElse(null)?.unwrapKey()?.orElse(null)?.identifier()?.path) }
      put("count", count)
      if (dropped > 0) put("dropped_at_feet", dropped)
    }
    ToolOutput(result, context.lang.text("summary.given", "item" to given, "count" to count))
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
      x = args.double("x") ?: throw ToolError("give either player, or x and z (and y when known)")
      z = args.double("z") ?: throw ToolError("give either player, or x and z (and y when known)")
      if (Math.abs(x) > 29_999_984 || Math.abs(z) > 29_999_984) throw ToolError("the point is outside the world")
      // Without y (structures come without one), the top of the ground there, as Bukkit's `getHighestBlockYAt` + 1.
      y = args.double("y") ?: level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, net.minecraft.util.Mth.floor(x), net.minecraft.util.Mth.floor(z)).toDouble()
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
    val danger = danger(level, net.minecraft.core.BlockPos(bx, by, bz))
    val result = buildJsonObject {
      put("x", bx)
      put("y", by)
      put("z", bz)
      put("dimension", Dimensions.id(level))
      if (name == null && args.double("y") == null) put("surface", true)
      put("safe", danger == null)
      danger?.let { put("danger", it) }
    }
    ToolOutput(result, name?.let { context.lang.text("summary.teleported_to_player", "player" to it) } ?: context.lang.text("summary.teleported", "x" to bx, "y" to by, "z" to bz))
  }

  /** Why a player wouldn't stand safely with their feet at [feet], or null; Bukkit's `isPassable` is an empty collision shape. */
  private fun danger(level: ServerLevel, feet: net.minecraft.core.BlockPos): String? {
    fun solid(pos: net.minecraft.core.BlockPos) = !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty
    val head = feet.above()
    val ground = (1..64).firstOrNull { down -> feet.below(down).let { solid(it) || !level.getFluidState(it).isEmpty || it.y <= level.minY } } ?: 64
    val lava = listOf(feet, head, feet.below()).any { level.getFluidState(it).`is`(net.minecraft.tags.FluidTags.LAVA) }
    val water = listOf(feet, head).any { !level.getFluidState(it).isEmpty }
    return OperatorArgs.danger(solid(feet), solid(head), lava, water, ground - 1)
  }

  private val gamemode: Tool = tool("set_gamemode") { context, player, args ->
    val mode = OperatorArgs.gamemode(args.string("mode"))
    player.setGameMode(GameType.byName(mode))
    // A mod may refuse the change (a world with a forced mode): say what the player is in now.
    val now = player.gameMode().getName()
    val result = buildJsonObject {
      put("mode", now)
      if (now != mode) put("blocked", "a mod or the world kept the player in $now")
    }
    ToolOutput(result, context.lang.text("summary.gamemode", "mode" to now), refused = now != mode)
  }

  private val heal = tool("heal") { context, player, _ ->
    val max = player.getAttribute(Attributes.MAX_HEALTH)?.value ?: 20.0
    player.health = max.toFloat()
    player.foodData.foodLevel = 20
    player.foodData.setSaturation(20f)
    player.remainingFireTicks = 0
    // Poison, wither, and hunger would undo it in seconds.
    val cleared = player.activeEffects.map { it.effect }.filter { BuiltInRegistries.MOB_EFFECT.getKey(it.value())?.path in OperatorArgs.HARMFUL }
      .onEach { player.removeEffect(it) }.mapNotNull { BuiltInRegistries.MOB_EFFECT.getKey(it.value())?.path }
    val result = buildJsonObject {
      put("health", round1(max))
      if (cleared.isNotEmpty()) put("cleared_effects", kotlinx.serialization.json.buildJsonArray { cleared.forEach { add(JsonPrimitive(it)) } })
    }
    ToolOutput(result, context.lang.text("summary.status", "health" to round1(max), "max" to round1(max), "food" to 20))
  }

  private val effect = tool("apply_effect") { context, player, args ->
    if (args.bool("clear") == true) {
      val count = player.activeEffects.size
      player.activeEffects.map { it.effect }.forEach { player.removeEffect(it) }
      return@tool ToolOutput(buildJsonObject { put("cleared", count) }, context.lang.text("summary.effects_cleared", "count" to count))
    }
    val id = OperatorArgs.id(args.string("effect")) ?: throw ToolError("effect is required, or clear: true")
    val type = BuiltInRegistries.MOB_EFFECT.get(key(id)).orElse(null)
      ?: throw ToolError("there is no effect called $id; close ones: ${Names.close(id, keys(BuiltInRegistries.MOB_EFFECT)).joinToString().ifEmpty { "none" }}")
    val seconds = OperatorArgs.effectSeconds(args.int("seconds"))
    val level = OperatorArgs.effectLevel(args.int("level"))
    player.addEffect(MobEffectInstance(type, seconds * 20, level - 1))
    // The game keeps a stronger effect the player already has (a beacon's Haste II over Haste I): say what's on now.
    val now = player.getEffect(type)
    val nowLevel = now?.amplifier?.plus(1) ?: level
    val nowSeconds = now?.let { if (it.isInfiniteDuration) -1 else it.duration / 20 } ?: seconds
    val result = buildJsonObject {
      put("effect", id)
      put("seconds", nowSeconds)
      put("level", nowLevel)
      // A higher level, a longer time, or an endless one (-1) the player had stays.
      if (nowLevel > level || nowSeconds < 0 || nowSeconds > seconds) put("kept_stronger", true)
    }
    ToolOutput(result, context.lang.text("summary.effect", "effect" to id, "level" to nowLevel, "seconds" to nowSeconds))
  }

  private val time: Tool = tool("set_time") { context, player, args ->
    val ticks = OperatorArgs.time((args["time"] as? JsonPrimitive)?.content)
    val level = skyWorld(player)
    // As Bukkit's `World.setTime`: forward to the next such time of day, on the dimension's own clock.
    val clock = level.dimensionType().defaultClock().orElse(null) ?: throw ToolError("Cannot set time in world without world clock")
    val full = WorldTools.fullTime(level)
    level.clockManager().setTotalTicks(clock, full + Math.floorMod(ticks - full, 24_000L))
    val now = Math.floorMod(WorldTools.fullTime(level), 24_000L)
    val result = buildJsonObject {
      put("time", now)
      put("world", Dimensions.id(level))
      if (!level.gameRules.get(net.minecraft.world.level.gamerules.GameRules.ADVANCE_TIME)) put("frozen", "the daylight cycle is off, so the time stays here")
      if (now != ticks) put("overridden", "a mod kept the time at $now")
    }
    ToolOutput(result, context.lang.text("summary.time", "ticks" to now), refused = now != ticks)
  }

  private val weather: Tool = tool("set_weather") { context, player, args ->
    val weather = OperatorArgs.weather(args.string("weather"))
    val ticks = OperatorArgs.weatherSeconds(args.int("seconds")) * 20
    // The same steps as Bukkit's `setStorm`, `setThundering`, and the durations.
    val level = skyWorld(player)
    val data = level.weatherData
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
    val now = if (data.isThundering) "thunder" else if (data.isRaining) "rain" else "clear"
    val result = buildJsonObject {
      put("weather", now)
      put("world", Dimensions.id(level))
      put("seconds", ticks / 20)
      if (!level.gameRules.get(net.minecraft.world.level.gamerules.GameRules.ADVANCE_WEATHER)) put("frozen", "the weather cycle is off, so it stays $now until changed again")
      if (now != weather) put("overridden", "a mod kept the weather $now")
    }
    ToolOutput(result, context.lang.text("summary.weather", "weather" to context.lang.text("weather.$now")), refused = now != weather)
  }

  /** The world whose sky the player means: their own, or the overworld from the Nether or the End, which have none. */
  fun skyWorld(player: ServerPlayer): ServerLevel =
    player.level().takeIf { it.dimension() == net.minecraft.world.level.Level.OVERWORLD } ?: player.level().server.overworld()

  val all = listOf(give, teleport, gamemode, heal, effect, time, weather)
  val NAMES = all.map { it.name }.toSet()
}
