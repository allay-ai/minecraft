package chat.allay.modcore.gametest

import chat.allay.core.Run
import chat.allay.core.tools.ToolError
import chat.allay.expansion.Ingredient
import chat.allay.expansion.ItemInfo
import chat.allay.expansion.Recipe
import chat.allay.expansion.ToolException
import chat.allay.expansion.v2.Call
import chat.allay.expansion.v2.Expansion
import chat.allay.modcore.ModPlayer
import chat.allay.modcore.gametest.Players.run
import chat.allay.modcore.gametest.Players.survivor

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.Registries
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.Enchantment
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.item.enchantment.ItemEnchantments

/**
 * What the model learns about this server's things, on a real one: the game's potions by the names players use, a
 * data pack's item and brew (the tests' own pack, `allay_gametest`), the anvil's item wherever it lies, and an
 * expansion's tool, item, and prompt, under the same checks as Allay's own tools.
 */
class CatalogGameTests {
  private fun equal(helper: GameTestHelper, actual: Any?, expected: Any?, what: String) =
    helper.assertTrue(actual == expected, "$what: expected $expected, got $actual")

  private fun result(player: ServerPlayer, name: String, args: JsonObject = buildJsonObject {}) = run(player, name, args).result.jsonObject

  private fun refused(block: () -> Unit): String? = (runCatching(block).exceptionOrNull() as? ToolError)?.message

  @GameTest
  fun thePotionOfInfestationIsBrewed(helper: GameTestHelper) {
    val asker = survivor(helper)
    for (name in listOf("infestation", "Potion of Infestation", "infested")) {
      val recipe = result(asker, "get_brewing_recipe", buildJsonObject { put("potion", name) })
      equal(helper, recipe["potion"]!!.jsonPrimitive.content, "infested", "the potion $name names")
      val steps = recipe["steps"]!!.jsonArray.map { it.jsonObject["add"]!!.jsonPrimitive.content }
      equal(helper, steps, listOf("nether_wart", "stone"), "water, nether wart, then stone")
    }
    val turned = refused { run(asker, "get_recipes", buildJsonObject { put("item", "Potion of Infestation") }) }
    helper.assertTrue(turned?.endsWith("call get_brewing_recipe with infested") == true, "get_recipes names the potion's id: $turned")
    val found = result(asker, "search_items", buildJsonObject { put("query", "infestation") })["matches"]!!.jsonArray.first().jsonObject
    equal(helper, found["id"]!!.jsonPrimitive.content, "infested", "search finds the potion")
    equal(helper, found["kind"]!!.jsonPrimitive.content, "potion", "as a potion")
    helper.assertTrue("brewing" in found["made"]!!.jsonArray.map { it.jsonPrimitive.content }, "brewed: $found")
    asker.discard()
    helper.succeed()
  }

  @GameTest
  fun aDataPacksItemAndBrewAreFound(helper: GameTestHelper) {
    val asker = survivor(helper)
    val ruby = result(asker, "search_items", buildJsonObject { put("query", "ruby") })["matches"]!!.jsonArray.first().jsonObject
    equal(helper, ruby["id"]!!.jsonPrimitive.content, "allay_gametest:ruby", "the pack's item by its model")
    equal(helper, ruby["kind"]!!.jsonPrimitive.content, "custom", "built on the game's emerald")
    equal(helper, ruby["base"]!!.jsonPrimitive.content, "emerald", "its base")
    val recipe = result(asker, "get_recipes", buildJsonObject { put("item", "Ruby") })
    equal(helper, recipe["item"]!!.jsonPrimitive.content, "allay_gametest:ruby", "the recipe's item")
    val ingredients = recipe["recipes"]!!.jsonArray.single().jsonObject["ingredients"]!!.jsonArray.map { it.jsonPrimitive.content }
    equal(helper, ingredients, listOf("emerald", "redstone"), "the pack's own recipe")
    // An emerald's recipes don't list the ruby that's an emerald underneath.
    val emerald = result(asker, "get_recipes", buildJsonObject { put("item", "emerald") })["recipes"]!!.jsonArray.map { it.toString() }
    helper.assertTrue(emerald.none { "redstone" in it }, "the ruby stays out of emerald's recipes: $emerald")
    // Luck can't be brewed in the game; the pack's brew makes it.
    val luck = result(asker, "get_brewing_recipe", buildJsonObject { put("potion", "luck") })["steps"]!!.jsonArray.map { it.jsonObject["add"]!!.jsonPrimitive.content }
    equal(helper, luck, listOf("nether_wart", "dirt"), "the pack's brew")
    val server = result(asker, "get_server")
    helper.assertTrue("datapacks" in server && "mods" in server, "the server lists mods and data packs: ${server.keys}")
    asker.discard()
    helper.succeed()
  }

  @GameTest
  fun severalThingsAreLookedUpAtOnce(helper: GameTestHelper) {
    val asker = survivor(helper)
    val found = result(asker, "search_items", buildJsonObject { put("queries", JsonArray(listOf("ruby", "infestation", "unobtainium").map(::JsonPrimitive))) })["results"]!!.jsonArray.map { it.jsonObject }
    equal(helper, found.map { it["query"]!!.jsonPrimitive.content }, listOf("ruby", "infestation", "unobtainium"), "each name in its place")
    equal(helper, found[0]["matches"]!!.jsonArray.first().jsonObject["id"]!!.jsonPrimitive.content, "allay_gametest:ruby", "the pack's item")
    equal(helper, found[1]["matches"]!!.jsonArray.first().jsonObject["id"]!!.jsonPrimitive.content, "infested", "the potion")
    helper.assertTrue(found[2]["matches"]!!.jsonArray.isEmpty() && "note" in found[2], "nothing, with a note: ${found[2]}")
    val made = result(asker, "get_recipes", buildJsonObject { put("items", JsonArray(listOf("Ruby", "Potion of Infestation", "stick").map(::JsonPrimitive))) })["results"]!!.jsonArray.map { it.jsonObject }
    equal(helper, made[0]["item"]!!.jsonPrimitive.content, "allay_gametest:ruby", "the ruby's recipe")
    helper.assertTrue(made[1]["error"]!!.jsonPrimitive.content.endsWith("call get_brewing_recipe with infested"), "the potion goes to brewing: ${made[1]}")
    helper.assertTrue(made[2]["recipes"]!!.jsonArray.size in 1..3, "a stick's recipes, three at most: ${made[2]}")
    val tooMany = refused { run(asker, "search_items", buildJsonObject { put("queries", JsonArray((1..9).map { JsonPrimitive("stone$it") })) }) }
    equal(helper, tooMany, "ask for at most 8 at once", "nine names are too many")
    asker.discard()
    helper.succeed()
  }

  private fun enchanted(player: ServerPlayer, item: ItemStack, book: Boolean, vararg enchantments: Pair<ResourceKey<Enchantment>, Int>): ItemStack {
    val registry = player.level().registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
    val mutable = ItemEnchantments.Mutable(ItemEnchantments.EMPTY)
    enchantments.forEach { (key, level) -> mutable.set(registry.getOrThrow(key), level) }
    item.set(if (book) DataComponents.STORED_ENCHANTMENTS else DataComponents.ENCHANTMENTS, mutable.toImmutable())
    return item
  }

  @GameTest
  fun theAnvilFindsTheItemInTheInventory(helper: GameTestHelper) {
    val asker = survivor(helper)
    asker.inventory.selectedSlot = 0
    asker.inventory.setItem(12, ItemStack(Items.DIAMOND_SWORD))
    asker.inventory.setItem(5, enchanted(asker, ItemStack(Items.ENCHANTED_BOOK), true, Enchantments.SHARPNESS to 5))
    val plan = result(asker, "get_anvil_plan")
    equal(helper, plan["found"]!!.jsonPrimitive.content, "true", "a plan with an empty hand: $plan")
    equal(helper, plan["slot"]!!.jsonPrimitive.int, 12, "for the sword in the inventory")
    equal(helper, plan["target"]!!.jsonPrimitive.content, "inventory row 1 column 4", "where it lies")
    equal(helper, result(asker, "get_anvil_plan", buildJsonObject { put("item", "diamond sword") })["slot"]!!.jsonPrimitive.int, 12, "the sword by name")
    // A slot given that holds nothing for the sword is passed over, not a reason to plan nothing.
    asker.inventory.setItem(7, ItemStack(Items.COBBLESTONE, 4))
    val unfit = result(asker, "get_anvil_plan", buildJsonObject { put("slot", 12); put("slots", JsonArray(listOf(5, 7).map(::JsonPrimitive))) })
    equal(helper, unfit["unfit_slots"]!!.jsonArray.map { it.jsonPrimitive.int }, listOf(7), "the cobblestone passed over: $unfit")
    // A bow the player wears nothing for, and a book for it too: two items gain, and the player picks.
    asker.inventory.setItem(20, ItemStack(Items.BOW))
    asker.inventory.setItem(6, enchanted(asker, ItemStack(Items.ENCHANTED_BOOK), true, Enchantments.POWER to 5))
    val choose = result(asker, "get_anvil_plan")
    equal(helper, choose["found"]!!.jsonPrimitive.content, "false", "no guess between two: $choose")
    equal(helper, choose["choose"]!!.jsonArray.map { it.jsonObject["slot"]!!.jsonPrimitive.int }.toSet(), setOf(12, 20), "both items to pick from")
    equal(helper, result(asker, "get_anvil_plan", buildJsonObject { put("slot", 20) })["item"]!!.jsonPrimitive.content, "bow", "the one picked")
    // The ideal books' plan for the item the player wears.
    asker.inventory.setItem(38, ItemStack(Items.DIAMOND_CHESTPLATE))
    val worn = result(asker, "get_anvil_plan", buildJsonObject { put("item", "diamond_chestplate"); put("enchantments", buildJsonObject { put("protection", 4) }) })
    equal(helper, worn["slot"]!!.jsonPrimitive.int, 38, "the chestplate worn")
    helper.assertTrue(EnchantmentHelper.getEnchantmentsForCrafting(asker.inventory.getItem(12)).isEmpty, "a plan changes nothing")
    asker.discard()
    helper.succeed()
  }

  @GameTest
  fun anExpansionsToolItemAndPromptReachTheModel(helper: GameTestHelper) {
    GameTestExpansion.register()
    try {
      val asker = survivor(helper)
      val expansions = Players.features.expansions!!
      val audience = ModPlayer(asker)
      equal(helper, expansions.offered(audience, Run.Mode.ASK, false, false).map { it.name }, listOf("x_gametest_caffeine"), "a survivor gets the reading tool only")
      equal(helper, expansions.offered(audience, Run.Mode.TASK, false, false), emptyList<Any>(), "an agent gets none")
      equal(helper, expansions.prompts(Run.Mode.ASK).single().prompt, "Energy drinks come from sugar cane.", "the prompt")
      val output = expansions.run("x_gametest_caffeine", Players.context(asker), Run.Mode.ASK, false, buildJsonObject {}).now!!
      equal(helper, output.result.jsonObject["level"]!!.jsonPrimitive.int, 64, "the tool ran")
      equal(helper, output.result.jsonObject["player"]!!.jsonPrimitive.content, asker.gameProfile.name, "for the asker, as the game's player")
      equal(helper, output.summary, "caffeine 64", "its line")
      val bad = refused { expansions.run("x_gametest_caffeine", Players.context(asker), Run.Mode.ASK, false, buildJsonObject { put("can", "bad") }) }
      equal(helper, bad, "no such can", "its own refusal reaches the model")
      val notOp = refused { expansions.run("x_gametest_give_can", Players.context(asker), Run.Mode.ASK, false, buildJsonObject {}) }
      equal(helper, notOp, "only operators can do this", "an operator tool refuses a survivor")
      val drink = result(asker, "search_items", buildJsonObject { put("query", "энергетик") })["matches"]!!.jsonArray.first().jsonObject
      equal(helper, drink["id"]!!.jsonPrimitive.content, "gametest:energy", "the expansion's item by its Russian name")
      equal(helper, drink["source"]!!.jsonPrimitive.content, "Game Test", "from the expansion")
      val brew = result(asker, "get_brewing_recipe", buildJsonObject { put("potion", "Energy Drink") })
      equal(helper, brew["steps"]!!.jsonArray.map { it.jsonObject["add"]!!.jsonPrimitive.content }, listOf("nether_wart", "sugar_cane"), "the expansion's brew")
      // A game in English, Allay in Russian: names come in Russian, to tools and to expansions alike.
      Players.features.settings.update(asker.uuid) { it.copy(language = "ru") }
      Players.features.locales.chose(audience)
      val russian = result(asker, "search_items", buildJsonObject { put("query", "gametest:energy") })["matches"]!!.jsonArray.first().jsonObject
      equal(helper, russian["name"]!!.jsonPrimitive.content, "Энергетик", "the name in the language Allay answers in")
      val heard = expansions.run("x_gametest_caffeine", Players.context(asker), Run.Mode.ASK, false, buildJsonObject {}).now!!.result.jsonObject
      equal(helper, heard["locale"]!!.jsonPrimitive.content, "en_us", "the game's language")
      equal(helper, heard["language"]!!.jsonPrimitive.content, "ru_ru", "Allay's language as a game language")
      Players.features.settings.update(asker.uuid) { it.copy(language = null) }
      Players.features.locales.chose(audience)
      asker.discard()
    } finally {
      GameTestExpansion.unregister()
    }
    helper.succeed()
  }
}

@JvmRecord private data class Caffeine(val player: String, val level: Int, val locale: String, val language: String)

/** An expansion as another mod would write it: a tool, an operator tool, an item with a brew, and a prompt. */
private object GameTestExpansion : Expansion("gametest", "Game Test") {
  val can = string("can", "A can's id")

  val caffeine = tool("caffeine", "The asker's caffeine level", ::caffeine).args(can).summary { "caffeine ${it.level}" }
  val giveCan = tool("give_can", "Gives the operator a can", ::giveCan).operator()

  override fun items() = listOf(
    ItemInfo.of("gametest:energy").name("en_us", "Energy Drink").name("ru_ru", "Энергетик").description("A test drink").base("potion")
      .recipe(Recipe.brewing(Ingredient.potion("awkward"), Ingredient.item("sugar_cane"))),
  )

  override fun prompt() = "Energy drinks come from sugar cane."

  private fun caffeine(call: Call): Caffeine {
    val player = call.player(ServerPlayer::class.java)
    if (call[can] == "bad") throw ToolException("no such can")
    return Caffeine(player.gameProfile.name, 64, call.asker().locale(), call.asker().language())
  }

  private fun giveCan(call: Call) = "given"
}
