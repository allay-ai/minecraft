package chat.allay.modcore.gametest

import chat.allay.core.tools.OPERATOR_TOOLS
import chat.allay.core.tools.ToolError
import chat.allay.modcore.gametest.Players.run
import chat.allay.modcore.gametest.Players.survivor
import chat.allay.modcore.tools.OperatorTools
import chat.allay.modcore.tools.WorldTools

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.permissions.LevelBasedPermissionSet
import net.minecraft.server.players.NameAndId
import net.minecraft.world.RandomizableContainer
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.GameType
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.CropBlock
import net.minecraft.world.level.block.entity.ChestBlockEntity
import net.minecraft.world.level.storage.loot.BuiltInLootTables
import net.minecraft.world.phys.Vec3

/**
 * The tools that read the player and the world, on a real server: what each returns, and the rules a player could
 * trip over. Other players show only as a count, containers only where the player could open them, and operator
 * tools refuse anyone else.
 */
class InfoGameTests {
  private fun equal(helper: GameTestHelper, actual: Any?, expected: Any?, what: String) =
    helper.assertTrue(actual == expected, "$what: expected $expected, got $actual")

  private fun result(player: ServerPlayer, name: String, args: JsonObject = buildJsonObject {}) = run(player, name, args).result.jsonObject

  private fun refused(block: () -> Unit): String? = (runCatching(block).exceptionOrNull() as? ToolError)?.message

  /** Turns [player] to look at the middle of the block at [pos]. */
  private fun look(player: ServerPlayer, pos: BlockPos) {
    val eye = player.eyePosition
    val dx = pos.x + 0.5 - eye.x
    val dy = pos.y + 0.5 - eye.y
    val dz = pos.z + 0.5 - eye.z
    player.yRot = Math.toDegrees(Math.atan2(-dx, dz)).toFloat()
    player.xRot = Math.toDegrees(-Math.atan2(dy, Math.hypot(dx, dz))).toFloat()
  }

  /** A chest at [relative] holding [items], or with a loot table still waiting when [loot]. */
  private fun chest(helper: GameTestHelper, relative: BlockPos, vararg items: ItemStack, loot: Boolean = false): BlockPos {
    val pos = helper.absolutePos(relative)
    helper.level.setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState())
    val chest = helper.level.getBlockEntity(pos) as ChestBlockEntity
    items.forEachIndexed { slot, item -> chest.setItem(slot, item) }
    // After the items: putting an item in rolls the loot and forgets the table.
    if (loot) (chest as RandomizableContainer).setLootTable(BuiltInLootTables.SIMPLE_DUNGEON)
    return pos
  }

  @GameTest
  fun statusAndInventoryDescribeTheAsker(helper: GameTestHelper) {
    val asker = survivor(helper)
    asker.inventory.setItem(0, ItemStack(Items.TORCH, 12))
    asker.inventory.setItem(40, ItemStack(Items.SHIELD))
    val status = result(asker, "get_player_status")
    equal(helper, status["gamemode"]!!.jsonPrimitive.content, "survival", "game mode")
    equal(helper, status["dimension"]!!.jsonPrimitive.content, "overworld", "dimension")
    equal(helper, status["held_item"]!!.jsonObject["id"]!!.jsonPrimitive.content, "torch", "held item")
    val inventory = result(asker, "get_inventory", buildJsonObject { put("include_enderchest", true) })
    val main = inventory["main"]!!.jsonArray
    equal(helper, main.size, 1, "stacks in the main inventory")
    equal(helper, main[0].jsonObject["count"]!!.jsonPrimitive.int, 12, "torches")
    equal(helper, inventory["offhand"]!!.jsonObject["id"]!!.jsonPrimitive.content, "shield", "off hand")
    helper.assertTrue(inventory["enderchest"]!!.jsonArray.isEmpty(), "an empty ender chest")
    val stats = result(asker, "get_statistics")
    equal(helper, stats["deaths"]!!.jsonPrimitive.int, 0, "deaths of a new player")
    equal(helper, stats.keys.size, 10, "the statistics Bukkit lists")
    asker.discard()
    helper.succeed()
  }

  @GameTest
  fun surroundingsCountOtherPlayersAndNameNoOne(helper: GameTestHelper) {
    val asker = survivor(helper)
    val other = survivor(helper, Vec3(3.5, 2.0, 3.5))
    helper.setBlock(BlockPos(2, 2, 2), Blocks.DIAMOND_ORE)
    val result = result(asker, "get_surroundings", buildJsonObject { put("radius", 4) })
    helper.assertTrue(result["other_players"]!!.jsonPrimitive.int >= 1, "the other player counts: $result")
    val types = result["entities"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content }
    helper.assertTrue("player" !in types, "players never show as entities: $types")
    helper.assertTrue(other.gameProfile.name !in result.toString(), "no player's name")
    helper.assertTrue(result["notable"]!!.jsonArray.any { it.jsonObject["id"]!!.jsonPrimitive.content == "diamond_ore" }, "the ore is notable: $result")
    asker.discard()
    other.discard()
    helper.succeed()
  }

  @GameTest
  fun lookAtReadsAChestAndKeepsLootChestsShut(helper: GameTestHelper) {
    val asker = survivor(helper)
    val open = chest(helper, BlockPos(1, 2, 3), ItemStack(Items.DIAMOND, 4))
    look(asker, open)
    val seen = result(asker, "look_at")
    equal(helper, seen["kind"]!!.jsonPrimitive.content, "block", "what's under the crosshair")
    equal(helper, seen["id"]!!.jsonPrimitive.content, "chest", "the block")
    equal(helper, seen["container"]!!.jsonObject["items"]!!.jsonObject["diamond"]!!.jsonPrimitive.int, 4, "diamonds inside")
    val detail = run(asker, "inspect_item", buildJsonObject { put("from", "container"); put("slot", 0) }).result.jsonObject
    equal(helper, detail["id"]!!.jsonPrimitive.content, "diamond", "the item in the chest's first slot")
    // Emptied first: a broken chest drops what it holds, and the diamonds would float under the crosshair.
    (helper.level.getBlockEntity(open) as net.minecraft.world.Container).clearContent()
    helper.level.removeBlock(open, false)
    val shut = chest(helper, BlockPos(1, 2, 3), ItemStack(Items.EMERALD), loot = true)
    look(asker, shut)
    val locked = result(asker, "look_at")
    helper.assertTrue("locked" in locked && "container" !in locked, "a loot chest stays shut: $locked")
    val why = refused { run(asker, "inspect_item", buildJsonObject { put("from", "container"); put("slot", 0) }) }
    helper.assertTrue(why?.contains("unopened structure chest") == true, "inspect_item refuses it too: $why")
    asker.discard()
    helper.succeed()
  }

  @GameTest
  fun containersShowOnlyWhereThePlayerCouldOpenThem(helper: GameTestHelper) {
    val asker = survivor(helper)
    val open = chest(helper, BlockPos(3, 2, 1), ItemStack(Items.DIAMOND, 5))
    val shut = chest(helper, BlockPos(3, 2, 3), ItemStack(Items.DIAMOND, 7), loot = true)
    asker.inventory.setItem(0, ItemStack(Items.DIAMOND, 2))
    val scan = result(asker, "scan_nearby_containers", buildJsonObject { put("radius", 6) })
    fun at(pos: BlockPos) = scan["containers"]!!.jsonArray.any { it.jsonObject["x"]!!.jsonPrimitive.int == pos.x && it.jsonObject["y"]!!.jsonPrimitive.int == pos.y && it.jsonObject["z"]!!.jsonPrimitive.int == pos.z }
    helper.assertTrue(at(open), "the open chest is listed: $scan")
    helper.assertTrue(!at(shut), "the loot chest isn't: $scan")
    val find = result(asker, "find_item", buildJsonObject { put("item", "diamond"); put("radius", 6) })
    val places = find["places"]!!.jsonArray.map { it.jsonObject }
    helper.assertTrue(places.any { it["where"]!!.jsonPrimitive.content == "inventory" && it["count"]!!.jsonPrimitive.int == 2 }, "the asker's own diamonds: $places")
    helper.assertTrue(places.any { it["where"]!!.jsonPrimitive.content == "container" && it["x"]!!.jsonPrimitive.int == open.x && it["count"]!!.jsonPrimitive.int == 5 }, "the open chest's: $places")
    helper.assertTrue(places.none { it["where"]!!.jsonPrimitive.content == "container" && it["x"]!!.jsonPrimitive.int == shut.x && it["z"]!!.jsonPrimitive.int == shut.z }, "never the loot chest's: $places")
    helper.assertTrue("highlighted" in find, "the chests glow for the asker")
    chat.allay.modcore.tools.Marks.clear(asker)
    asker.discard()
    helper.succeed()
  }

  @GameTest
  fun brewingFarmsAndDarkSpotsAreScanned(helper: GameTestHelper) {
    val asker = survivor(helper)
    asker.inventory.setItem(0, ItemStack(Items.BLAZE_POWDER, 3))
    helper.setBlock(BlockPos(3, 2, 1), Blocks.BREWING_STAND)
    helper.setBlock(BlockPos(1, 1, 4), Blocks.FARMLAND)
    helper.setBlock(BlockPos(1, 2, 4), Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7))
    val brewing = result(asker, "get_brewing_items")
    equal(helper, brewing["inventory"]!!.jsonArray.size, 1, "brewing items carried")
    helper.assertTrue(brewing["brewing_stands"]!!.jsonArray.isNotEmpty(), "the stand nearby: $brewing")
    val farm = result(asker, "scan_farm", buildJsonObject { put("radius", 4) })
    helper.assertTrue(farm["crops"]!!.jsonObject["wheat"]!!.jsonObject["ripe"]!!.jsonPrimitive.int >= 1, "ripe wheat: $farm")
    val farmland = farm["farmland"]!!.jsonObject
    helper.assertTrue(farmland["dry"]!!.jsonPrimitive.int + farmland["hydrated"]!!.jsonPrimitive.int >= 1, "the farmland: $farm")
    val dark = result(asker, "get_light_spawn_check", buildJsonObject { put("radius", 3) })
    helper.assertTrue("dark_spots" in dark && "rule" in dark, "the spawn check answers: $dark")
    val recipe = result(asker, "get_brewing_recipe", buildJsonObject { put("potion", "night_vision") })
    equal(helper, recipe["steps"]!!.jsonArray.size, 2, "awkward, then night vision")
    asker.discard()
    helper.succeed()
  }

  @GameTest
  fun gearExperienceAndFoodAreRead(helper: GameTestHelper) {
    val asker = survivor(helper)
    val pickaxe = ItemStack(Items.DIAMOND_PICKAXE)
    pickaxe.set(DataComponents.DAMAGE, 1_500)
    pickaxe.enchant(asker.level().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow(Enchantments.UNBREAKING), 3)
    asker.inventory.setItem(1, pickaxe)
    asker.inventory.setItem(2, ItemStack(Items.BREAD, 3))
    asker.inventory.setItem(0, ItemStack(Items.DIAMOND_SWORD))
    asker.experienceLevel = 5
    val wear = result(asker, "get_item_durability")["items"]!!.jsonArray.map { it.jsonObject }
    val worn = wear.single { it["id"]!!.jsonPrimitive.content == "diamond_pickaxe" }
    equal(helper, worn["max_damage"]!!.jsonPrimitive.int, 1_561, "the pickaxe's durability")
    equal(helper, worn["unbreaking"]!!.jsonPrimitive.int, 3, "its unbreaking")
    helper.assertTrue("almost_broken" in worn, "a worn pickaxe: $worn")
    equal(helper, result(asker, "get_xp_info")["level"]!!.jsonPrimitive.int, 5, "the level")
    val foods = result(asker, "get_food_options")["foods"]!!.jsonArray.map { it.jsonObject }
    equal(helper, foods.single()["count"]!!.jsonPrimitive.int, 3, "bread")
    val plan = result(asker, "get_anvil_plan", buildJsonObject { putJsonObject("enchantments") { put("sharpness", 5) } })
    equal(helper, plan["item"]!!.jsonPrimitive.content, "diamond_sword", "the item in hand")
    helper.assertTrue(plan["steps"]!!.jsonArray.isNotEmpty(), "one step at least: $plan")
    val wrong = refused { run(asker, "get_anvil_plan", buildJsonObject { putJsonObject("enchantments") { put("protection", 4) } }) }
    equal(helper, wrong, "protection doesn't go on diamond_sword", "a book that doesn't fit")
    asker.discard()
    helper.succeed()
  }

  @GameTest
  fun worldServerAndRecipesAnswer(helper: GameTestHelper) {
    val asker = survivor(helper)
    val world = result(asker, "get_world")
    equal(helper, world["dimension"]!!.jsonPrimitive.content, "overworld", "dimension")
    helper.assertTrue(world["rules"]!!.jsonObject.containsKey("keep_inventory"), "the rules that change advice: $world")
    val server = result(asker, "get_server")
    equal(helper, server["software"]!!.jsonPrimitive.content, WorldTools.SOFTWARE, "software")
    equal(helper, server["version"]!!.jsonPrimitive.content, "26.3", "game version")
    helper.assertTrue(server["online"]!!.jsonPrimitive.int >= 1, "the asker is online")
    val mods = server["mods"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }
    helper.assertTrue("Allay" in mods, "Allay lists itself: $mods")
    helper.assertTrue(mods.none { it == "Minecraft" || it == "Java HotSpot(TM) 64-Bit Server VM" || it == "Fabric Loader" || it == "NeoForge" }, "no built-ins: $mods")
    val table = result(asker, "get_recipes", buildJsonObject { put("item", "crafting_table") })
    val shaped = table["recipes"]!!.jsonArray[0].jsonObject
    equal(helper, shaped["type"]!!.jsonPrimitive.content, "crafting_shaped", "a crafting table's recipe")
    helper.assertTrue(shaped["key"]!!.jsonObject["a"]!!.jsonPrimitive.content.startsWith("#planks"), "any planks read as a tag: $shaped")
    val potion = refused { run(asker, "get_recipes", buildJsonObject { put("item", "potion") }) }
    helper.assertTrue(potion?.contains("get_brewing_recipe") == true, "potions point to brewing: $potion")
    // A plan takes the parts on the way from what the player has, and takes nothing.
    asker.inventory.setItem(9, ItemStack(Items.DIAMOND, 3))
    asker.inventory.setItem(10, ItemStack(Items.OAK_LOG, 1))
    val pickaxe = result(asker, "get_recipes", buildJsonObject { put("item", "diamond_pickaxe"); put("plan", true) })["plan"]!!.jsonObject
    equal(helper, pickaxe["takes"]!!.jsonObject["oak_log"]!!.jsonPrimitive.int, 1, "planks and sticks from the log: $pickaxe")
    equal(helper, pickaxe["needs"]!!.jsonArray.map { it.jsonPrimitive.content }, listOf("crafting_table"), "no table near: $pickaxe")
    equal(helper, asker.inventory.getItem(9).count, 3, "a plan takes nothing")
    val two = result(asker, "get_recipes", buildJsonObject { put("items", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("diamond_pickaxe")))); put("plan", true); put("count", 2) })
    val short = two["results"]!!.jsonArray[0].jsonObject["plan"]!!.jsonObject
    equal(helper, short["missing"]?.jsonObject?.get("diamond")?.jsonPrimitive?.int, 3, "two pickaxes need three more diamonds: $short")
    // A set shares what the player has: eight ingots make the helmet, and the boots then lack one.
    asker.inventory.setItem(9, ItemStack(Items.IRON_INGOT, 8))
    val set = result(asker, "get_recipes", buildJsonObject { put("items", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("iron_helmet"), JsonPrimitive("iron_boots")))); put("plan", true) })
    val (helmet, boots) = set["results"]!!.jsonArray.map { it.jsonObject["plan"]!!.jsonObject }
    equal(helper, helmet["takes"]?.jsonObject?.get("iron_ingot")?.jsonPrimitive?.int, 5, "the helmet's ingots: $helmet")
    helper.assertTrue("missing" in boots, "the boots get what's left: $boots")
    val advancements = result(asker, "get_advancements", buildJsonObject { put("filter", "all") })
    val todo = advancements["todo"]!!.jsonArray.map { it.jsonObject }
    helper.assertTrue(advancements["todo_count"]!!.jsonPrimitive.int > todo.size && todo.size == 40, "the list is capped, the count isn't: $advancements")
    helper.assertTrue(todo.none { it["key"]!!.jsonPrimitive.content.startsWith("recipes/") } && todo.all { "title" in it && "description" in it }, "no recipe unlocks: $todo")
    val structure = refused { run(asker, "locate_structure", buildJsonObject { put("structure", "moonbase") }) }
    equal(helper, structure, "no structure called moonbase", "an unknown structure")
    helper.assertTrue("found" in result(asker, "locate_biome", buildJsonObject { put("biome", "plains") }), "a biome search answers")
    asker.discard()
    helper.succeed()
  }

  @GameTest
  fun commandsShowOnlyWhatThePlayerMayRun(helper: GameTestHelper) {
    val asker = survivor(helper)
    fun commands(args: JsonObject) = result(asker, "search_commands", args)["results"]!!.jsonArray.map { it.jsonObject }
    val found = commands(buildJsonObject { put("queries", kotlinx.serialization.json.JsonArray(listOf("help", "teleport", "ask").map(::JsonPrimitive))) })
    equal(helper, found.map { it["query"]!!.jsonPrimitive.content }, listOf("help", "teleport", "ask"), "one answer per query, in order")
    val help = found[0]["commands"]!!.jsonArray.first().jsonObject
    equal(helper, help["name"]!!.jsonPrimitive.content, "help", "help is found")
    equal(helper, help["source"]!!.jsonPrimitive.content, "minecraft", "the game's own command")
    helper.assertTrue(help["usage"]!!.jsonPrimitive.content.startsWith("/help"), "usage as /help shows it: $help")
    helper.assertTrue(help["may_run"]!!.jsonPrimitive.boolean && help["aliases"]!!.jsonArray.isEmpty(), "help: $help")
    helper.assertTrue(found[1]["commands"]!!.jsonArray.none { it.jsonObject["name"]!!.jsonPrimitive.content == "teleport" }, "an operator's command stays out: ${found[1]}")
    val ask = found[2]["commands"]!!.jsonArray.first().jsonObject
    equal(helper, ask["name"]!!.jsonPrimitive.content, "ask", "Allay's own command is found")
    // modcore's classes sit in Allay's jar, but beside the mod in a dev run, where Fabric can't tell whose they are.
    helper.assertTrue(ask["source"].let { it is kotlinx.serialization.json.JsonNull || it!!.jsonPrimitive.content == "allay" }, "Allay's command is Allay's: $ask")
    equal(helper, chat.allay.modcore.Mod.loader.owner(chat.allay.modcore.Mod.loader.javaClass), "allay", "the loader names the mod whose jar holds a class")
    equal(helper, chat.allay.modcore.Mod.loader.owner(net.minecraft.server.MinecraftServer::class.java), "minecraft", "the game's classes are the game's")
    val checks = result(asker, "search_commands", buildJsonObject { put("check", kotlinx.serialization.json.JsonArray(listOf("/help", "tp @s 0 64 0", "nosuchcommand", "me", "random value abc").map(::JsonPrimitive))) })["checks"]!!.jsonArray.map { it.jsonObject }
    equal(helper, checks.map { it["ok"]!!.jsonPrimitive.boolean }, listOf(true, false, false, false, false), "the checks: $checks")
    helper.assertTrue(checks[1]["error"]!!.jsonPrimitive.content.contains("may not run"), "not the player's: ${checks[1]}")
    helper.assertTrue(checks[2]["error"]!!.jsonPrimitive.content.startsWith("no command /nosuchcommand"), "unknown: ${checks[2]}")
    helper.assertTrue(checks[3]["error"]!!.jsonPrimitive.content.contains("incomplete"), "a command without its arguments: ${checks[3]}")
    helper.assertTrue(checks[4]["error"]!!.jsonPrimitive.content.contains("doesn't parse"), "arguments the game can't parse: ${checks[4]}")
    asker.discard()
    helper.succeed()
  }

  @GameTest
  fun villagersAndLocationsAreFound(helper: GameTestHelper) {
    val asker = survivor(helper)
    val villager = helper.spawn(EntityTypes.VILLAGER, BlockPos(3, 2, 3))
    val found = result(asker, "find_villagers", buildJsonObject { put("radius", 8) })
    helper.assertTrue(found["villagers"]!!.jsonArray.isNotEmpty(), "the villager: $found")
    val places = result(asker, "get_locations")
    equal(helper, places["respawn_point"], JsonNull, "no bed yet")
    helper.assertTrue(places["world_spawn"]!!.jsonObject.containsKey("respawn_radius"), "the world spawn: $places")
    equal(helper, places["last_death"], JsonNull, "no death yet")
    helper.assertTrue(refused { run(asker, "get_death_info") } == "this player hasn't died yet", "no death to tell")
    villager.discard()
    asker.discard()
    helper.succeed()
  }

  @GameTest
  fun aDeathIsRememberedAsTheGameDropsIt(helper: GameTestHelper) {
    val asker = survivor(helper)
    asker.inventory.setItem(0, ItemStack(Items.DIRT, 5))
    val cursed = ItemStack(Items.IRON_HELMET)
    cursed.enchant(asker.level().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow(Enchantments.VANISHING_CURSE), 1)
    asker.inventory.setItem(39, cursed)
    asker.experienceLevel = 3
    asker.kill(helper.level)
    val death = Players.features.deaths.latest(asker.uuid)
    helper.assertTrue(death != null, "the death is remembered")
    equal(helper, death!!.drops, mapOf("dirt" to 5), "what dropped; the cursed helmet vanished")
    equal(helper, death.experience, 21, "experience as the game drops it: seven a level")
    equal(helper, death.dimension, "overworld", "where")
    helper.assertTrue(death.message?.contains(asker.gameProfile.name) == true, "the death message: ${death.message}")
    val info = result(asker, "get_death_info")
    equal(helper, info["dropped"]!!.jsonObject["dirt"]!!.jsonPrimitive.int, 5, "the tool tells the drops")
    equal(helper, info["dropped_experience"]!!.jsonPrimitive.int, 21, "and the experience")
    asker.discard()
    helper.succeed()
  }

  @GameTest
  fun operatorToolsRefuseEveryoneElse(helper: GameTestHelper) {
    equal(helper, OperatorTools.NAMES, OPERATOR_TOOLS, "the operator tools the core knows")
    val asker = survivor(helper)
    OperatorTools.NAMES.forEach { name ->
      equal(helper, refused { run(asker, name, buildJsonObject { put("item", "diamond"); put("mode", "creative") }) }, OperatorTools.NOT_OPERATOR, "$name for a non-operator")
    }
    helper.assertTrue(asker.inventory.countItem(Items.DIAMOND) == 0 && asker.gameMode() == GameType.SURVIVAL, "nothing changed")
    asker.discard()
    helper.succeed()
  }

  @GameTest
  fun operatorToolsWorkForOperators(helper: GameTestHelper) {
    val asker = survivor(helper)
    val server = helper.level.server
    // The test server's operators get no permissions by default; game masters' are what the tools ask for.
    server.playerList.op(NameAndId(asker.gameProfile), java.util.Optional.of(LevelBasedPermissionSet.GAMEMASTER), java.util.Optional.of(false))
    try {
      val given = result(asker, "give_item", buildJsonObject { put("item", "diamond"); put("count", 3) })
      equal(helper, given["count"]!!.jsonPrimitive.int, 3, "diamonds given")
      equal(helper, asker.inventory.countItem(Items.DIAMOND), 3, "diamonds in the inventory")
      result(asker, "set_gamemode", buildJsonObject { put("mode", "creative") })
      equal(helper, asker.gameMode(), GameType.CREATIVE, "game mode")
      asker.hurtServer(helper.level, helper.level.damageSources().generic(), 5f)
      result(asker, "heal")
      equal(helper, asker.health, asker.maxHealth, "healed")
      result(asker, "apply_effect", buildJsonObject { put("effect", "speed"); put("seconds", 30); put("level", 2) })
      helper.assertTrue(asker.activeEffects.any { it.amplifier == 1 }, "speed II")
      val target = helper.absolutePos(BlockPos(2, 2, 2))
      val moved = result(asker, "teleport", buildJsonObject { put("x", target.x + 0.5); put("y", target.y.toDouble()); put("z", target.z + 0.5) })
      equal(helper, moved["x"]!!.jsonPrimitive.int, target.x, "teleported")
      result(asker, "set_time", buildJsonObject { put("time", "1000") })
      equal(helper, Math.floorMod(WorldTools.fullTime(helper.level), 24_000L), 1_000L, "the time of day")
      result(asker, "set_weather", buildJsonObject { put("weather", "clear"); put("seconds", 600) })
      helper.assertTrue(!helper.level.weatherData.isRaining, "clear skies")
    } finally {
      server.playerList.deop(NameAndId(asker.gameProfile))
    }
    asker.discard()
    helper.succeed()
  }
}
