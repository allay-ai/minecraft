package chat.allay.core

import chat.allay.expansion.AllayApi
import chat.allay.expansion.AllayExpansion
import chat.allay.expansion.AllayTool
import chat.allay.expansion.Ingredient
import chat.allay.expansion.ItemInfo
import chat.allay.expansion.Recipe
import chat.allay.expansion.Schema
import chat.allay.expansion.ToolCall
import chat.allay.expansion.ToolResult
import chat.allay.core.tools.Catalog
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.UUID
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExpansionsTest {
  private val lines = mutableListOf<String>()
  private val log = Logger.getAnonymousLogger().apply {
    useParentHandlers = false
    addHandler(object : Handler() {
      override fun publish(record: LogRecord) { lines += record.message }
      override fun flush() {}
      override fun close() {}
    })
  }
  private var yaml = mapOf<String, Any?>()
  private val expansions = Expansions(log, { Config(YamlConfig(yaml, emptyMap()), "test") }, { null })

  private fun tool(name: String, access: AllayTool.Access = AllayTool.Access.READ, public: Boolean = false, permission: String? = null) = object : AllayTool {
    override fun name() = name
    override fun description() = "Does $name"
    override fun parameters(): Schema = Schema.arguments().string("can", "Which can", true)
    override fun access() = access
    override fun inPublic() = public
    override fun permission() = permission
    override fun run(call: ToolCall): ToolResult = ToolResult.of(mapOf("ok" to true)).summary("done")
  }

  private fun expansion(id: String, api: String = AllayApi.VERSION, tools: List<AllayTool> = emptyList(), prompt: String? = null, items: List<ItemInfo> = emptyList()) = object : AllayExpansion {
    override fun id() = id
    override fun requiresApi() = api
    override fun name() = "Red Bull"
    override fun version() = "1.4.0"
    override fun tools() = tools
    override fun items() = items
    override fun promptExpansion() = prompt
  }

  private fun asker(operator: Boolean = false, granted: Set<String> = emptySet()) = object : Audience {
    override val name = "Steve"
    override val uuid: UUID? = UUID.randomUUID()
    override val operator = operator
    override fun can(permission: String) = permission in granted
    override fun can(permission: String, byDefault: Boolean) = permission in granted || byDefault
    override fun send(text: String) {}
    override fun send(line: Line) {}
  }

  @Test fun `tools reach the model by access, mode, and audience`() {
    expansions.register(expansion("redbull", tools = listOf(
      tool("caffeine", public = true),
      tool("drink", AllayTool.Access.OWN),
      tool("give_can", AllayTool.Access.OPERATOR),
    )))
    fun names(asker: Audience, mode: Run.Mode = Run.Mode.ASK, public: Boolean = false, task: Boolean = false) = expansions.offered(asker, mode, public, task).map { it.name }
    assertEquals(listOf("x_redbull_caffeine", "x_redbull_drink"), names(asker()))
    assertEquals(listOf("x_redbull_caffeine", "x_redbull_drink", "x_redbull_give_can"), names(asker(operator = true)))
    assertEquals(listOf("x_redbull_caffeine"), names(asker(operator = true), Run.Mode.ASKX))
    assertEquals(listOf("x_redbull_caffeine"), names(asker(operator = true), public = true))
    assertEquals(emptyList(), names(asker(operator = true), Run.Mode.TASK))
    assertEquals(emptyList(), names(asker(operator = true), task = true))
    val offered = expansions.offered(asker(), Run.Mode.ASK, false, false).first()
    assertEquals("read", offered.access)
    assertEquals("redbull", offered.expansion)
    assertEquals(listOf("can"), (offered.parameters["required"] as kotlinx.serialization.json.JsonArray).map { it.jsonPrimitive.content })
  }

  @Test fun `a permission the player lacks keeps the tool out`() {
    val strict = object : Audience by asker() {
      override fun can(permission: String, byDefault: Boolean) = false
    }
    expansions.register(expansion("redbull", tools = listOf(tool("caffeine", permission = "redbull.allay"))))
    assertEquals(emptyList(), expansions.offered(strict, Run.Mode.ASK, false, false))
    assertEquals(1, expansions.offered(asker(), Run.Mode.ASK, false, false).size)
  }

  @Test fun `broken expansions stay out and say why`() {
    expansions.register(expansion("Red Bull"))
    expansions.register(expansion("future", api = "1.9.0"))
    expansions.register(expansion("major", api = "2.0.0"))
    expansions.register(expansion("tools", tools = listOf(tool("Bad Name"))))
    expansions.register(expansion("twice", tools = listOf(tool("same"), tool("same"))))
    assertEquals(emptyList(), expansions.active())
    assertTrue(lines.any { "needs expansion API 1.9.0" in it })
    assertTrue(lines.any { "lower-case" in it })
    assertTrue(lines.any { "share a name" in it })
  }

  @Test fun `config switches expansions off`() {
    expansions.register(expansion("redbull", prompt = "Cans give caffeine."))
    assertEquals(listOf("Cans give caffeine."), expansions.prompts(Run.Mode.ASK).map { it.prompt })
    assertEquals(emptyList(), expansions.prompts(Run.Mode.TASK))
    yaml = mapOf("expansions" to mapOf("disabled" to listOf("RedBull", "redbull")))
    assertEquals(emptyList(), expansions.active())
    yaml = mapOf("expansions" to mapOf("enabled" to false))
    assertEquals(emptyList(), expansions.active())
  }

  @Test fun `a long prompt is cut`() {
    expansions.register(expansion("redbull", prompt = "x".repeat(5000)))
    assertEquals(Expansions.MAX_PROMPT, expansions.prompts(Run.Mode.ASK).single().prompt!!.length)
  }

  @Test fun `items join the catalog with their recipes and brews`() {
    val crude = ItemInfo.of("redbull:crude_taurine").name("en_us", "Crude Taurine").base("potion")
      .recipe(Recipe.brewing(Ingredient.potion("water"), Ingredient.item("redbull:bull_bile")))
    val can = ItemInfo.of("redbull:original").name("en_us", "Red Bull Original")
      .recipe(Recipe.shapeless(listOf(Ingredient.item("redbull:empty_can"), Ingredient.item("sugar")), 1))
    expansions.register(expansion("redbull", items = listOf(crude, can)))
    val entries = expansions.entries()
    assertEquals(listOf("redbull:crude_taurine", "redbull:original"), entries.map { it.id })
    assertEquals("Red Bull", entries.first().source)
    assertEquals("crafting_shapeless", Catalog.describe(can.recipes().single())!!["type"]!!.jsonPrimitive.content)
    assertNull(Catalog.describe(crude.recipes().single()))
    val brew = Catalog.brew(crude.id(), crude.recipes().single())!!
    assertEquals("potion water", brew.from.label)
    assertEquals("redbull:bull_bile", brew.reagent)
    assertEquals("redbull:crude_taurine", brew.to.label)
  }

  @Test fun `values cross between JSON and the API's plain types`() {
    val json = buildJsonObject { put("name", "can"); put("count", 3); put("half", 2.5); put("on", true) }
    assertEquals(mapOf("name" to "can", "count" to 3L, "half" to 2.5, "on" to true), Expansions.plain(json))
    val back = Expansions.json(mapOf("list" to listOf(1, "a", null), "uuid" to UUID(0, 0), "other" to File("x")))
    assertEquals("""{"list":[1,"a",null],"uuid":"00000000-0000-0000-0000-000000000000","other":"x"}""", back.toString())
    assertEquals(JsonPrimitive(true), Expansions.json(true))
  }
}
