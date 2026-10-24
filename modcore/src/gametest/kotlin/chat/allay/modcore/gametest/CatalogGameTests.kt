package chat.allay.modcore.gametest

import chat.allay.core.Run
import chat.allay.core.tools.ToolError
import chat.allay.expansion.AllayApi
import chat.allay.expansion.AllayExpansion
import chat.allay.expansion.AllayExpansions
import chat.allay.expansion.AllayTool
import chat.allay.expansion.Ingredient
import chat.allay.expansion.ItemInfo
import chat.allay.expansion.Recipe
import chat.allay.expansion.Schema
import chat.allay.expansion.ToolCall
import chat.allay.expansion.ToolException
import chat.allay.expansion.ToolResult
import chat.allay.modcore.ModPlayer
import chat.allay.modcore.gametest.Players.run
import chat.allay.modcore.gametest.Players.survivor

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.Registries
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
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

  private fun enchanted(player: ServerPlayer, item: ItemStack, book: Boolean, vararg enchantments: Pair<net.minecraft.resources.ResourceKey<net.minecraft.world.item.enchantment.Enchantment>, Int>): ItemStack {
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

  /** An expansion as another mod would write it: a tool, an operator tool, an item with a brew, and a prompt. */
  private val expansion = object : AllayExpansion {
    override fun id() = "gametest"
    override fun requiresApi() = AllayApi.VERSION
    override fun name() = "Game Test"
    override fun tools() = listOf(
      object : AllayTool {
        override fun name() = "caffeine"
        override fun description() = "The asker's caffeine level"
        override fun parameters(): Schema = Schema.arguments().string("can", "A can's id", false)
        override fun run(call: ToolCall): ToolResult {
          val player = call.asker().unwrap(ServerPlayer::class.java) ?: throw ToolException("no player")
          if (call.args().string("can") == "bad") throw ToolException("no such can")
          return ToolResult.of(mapOf("player" to player.gameProfile.name, "level" to 64, "locale" to call.asker().locale())).summary("caffeine 64")
        }
      },
      object : AllayTool {
        override fun name() = "give_can"
        override fun description() = "Gives the operator a can"
        override fun access() = AllayTool.Access.OPERATOR
        override fun run(call: ToolCall): ToolResult = ToolResult.text("given")
      },
    )
    override fun items() = listOf(
      ItemInfo.of("gametest:energy").name("en_us", "Energy Drink").name("ru_ru", "Энергетик").description("A test drink").base("potion")
        .recipe(Recipe.brewing(Ingredient.potion("awkward"), Ingredient.item("sugar_cane"))),
    )
    override fun promptExpansion() = "Energy drinks come from sugar cane."
  }

  @GameTest
  fun anExpansionsToolItemAndPromptReachTheModel(helper: GameTestHelper) {
    AllayExpansions.register(expansion)
    try {
      val asker = survivor(helper)
      val expansions = Players.features.expansions!!
      val audience = ModPlayer(asker)
      equal(helper, expansions.offered(audience, Run.Mode.ASK, false, false).map { it.name }, listOf("x_gametest_caffeine"), "a survivor gets the reading tool only")
      equal(helper, expansions.offered(audience, Run.Mode.TASK, false, false), emptyList<Any>(), "an agent gets none")
      equal(helper, expansions.prompts(Run.Mode.ASK).single().prompt, "Energy drinks come from sugar cane.", "the prompt")
      val output = expansions.run("x_gametest_caffeine", Players.context(asker), Run.Mode.ASK, false, buildJsonObject {})
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
      asker.discard()
    } finally {
      AllayExpansions.unregister("gametest")
    }
    helper.succeed()
  }
}
