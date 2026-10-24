package chat.allay.core

import chat.allay.core.tools.CommandCatalog
import chat.allay.core.tools.CommandCatalog.Check
import chat.allay.core.tools.CommandCatalog.Entry
import chat.allay.core.tools.CommandCatalog.Verdict
import chat.allay.core.tools.ToolError
import chat.allay.core.tools.ToolLabels
import kotlinx.serialization.json.JsonArray
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
import java.io.InputStreamReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommandCatalogTest {
  private val lang = Texts(Texts.BUNDLED.associateWith { code -> InputStreamReader(javaClass.getResourceAsStream("/lang/$code.yml")!!, Charsets.UTF_8).use(Texts::read) }, "en").lang("en")

  /** A server's commands; the handle says who may run each: everyone, operators, or nobody here. */
  private val catalog = CommandCatalog(listOf(
    Entry("sethome", listOf("createhome"), "Homes", "Sets your home where you stand", "/sethome [name]", "all"),
    Entry("home", listOf("homes"), "Homes", "Takes you §ehome", null, "all"),
    Entry("plan", emptyList(), "Plan", "Player analytics", null, "all"),
    Entry("teleport", listOf("tp"), CommandCatalog.MINECRAFT, null, null, "op"),
    Entry("plugins", listOf("pl"), "bukkit", null, null, "all"),
    Entry("ac", emptyList(), "SecretAntiCheat", "Anti-cheat", null, "all"),
    Entry("mystery", emptyList(), null, null, null, "all"),
  ))

  private val hidden = setOf("secretanticheat")
  private val blocked = setOf("pl")
  private val player = { handle: String -> handle == "all" }

  private fun run(args: JsonObject, check: (String) -> Check? = { null }) =
    catalog.run(lang, args, hidden, blocked, player, check).result.jsonObject

  private fun names(result: JsonObject) = result["commands"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }

  @Test fun `only what the player may run shows, and no hidden plugin`() {
    val all = run(buildJsonObject {})
    val listed = names(all["results"]!!.jsonArray.single().jsonObject)
    assertEquals(listOf("home", "mystery", "plan", "plugins", "sethome"), listed)
    val sources = all["sources"]!!.jsonArray.map { it.jsonObject["source"]!!.let { s -> if (s is JsonNull) null else s.jsonPrimitive.content } to it.jsonObject["commands"]!!.jsonPrimitive.int }
    assertEquals(listOf("Homes" to 2, "Plan" to 1, "bukkit" to 1, null to 1), sources)
    assertFalse("SecretAntiCheat" in all.toString(), "a hidden plugin never shows: $all")
    assertFalse("teleport" in all.toString(), "an operator's command stays out for a player: $all")
  }

  @Test fun `queries answer each in order, by name, alias, or description`() {
    val results = run(buildJsonObject { put("queries", JsonArray(listOf("/homes", "sets home", "tp", "analytics").map(::JsonPrimitive))) })["results"]!!.jsonArray.map { it.jsonObject }
    assertEquals(listOf("/homes", "sets home", "tp", "analytics"), results.map { it["query"]!!.jsonPrimitive.content })
    assertEquals("home", names(results[0]).first())
    assertEquals("sethome", names(results[1]).first())
    assertEquals(emptyList(), names(results[2]), "teleport isn't the player's: ${results[2]}")
    assertTrue("note" in results[2])
    assertEquals(listOf("plan"), names(results[3]))
  }

  @Test fun `a command carries its fields, color codes gone, and blocked ones never may run`() {
    val home = run(buildJsonObject { put("plugin", "homes") })["results"]!!.jsonArray.single().jsonObject
    val commands = home["commands"]!!.jsonArray.map { it.jsonObject }
    assertEquals(listOf("home", "sethome"), commands.map { it["name"]!!.jsonPrimitive.content })
    val sethome = commands[1]
    assertEquals(listOf("createhome"), sethome["aliases"]!!.jsonArray.map { it.jsonPrimitive.content })
    assertEquals("Homes", sethome["source"]!!.jsonPrimitive.content)
    assertEquals("/sethome [name]", sethome["usage"]!!.jsonPrimitive.content)
    assertTrue(sethome["may_run"]!!.jsonPrimitive.boolean)
    assertEquals("Takes you home", commands[0]["description"]!!.jsonPrimitive.content)
    val plugins = run(buildJsonObject { put("query", "plugins") })["results"]!!.jsonArray.single().jsonObject["commands"]!!.jsonArray.first().jsonObject
    assertFalse(plugins["may_run"]!!.jsonPrimitive.boolean, "an alias in blocked_commands blocks the command: $plugins")
    val mystery = run(buildJsonObject { put("query", "mystery") })["results"]!!.jsonArray.single().jsonObject["commands"]!!.jsonArray.first().jsonObject
    assertEquals(JsonNull, mystery["source"], "an unknown owner is null, not left out")
  }

  @Test fun `an unknown or hidden plugin names the sources there are`() {
    for (plugin in listOf("essentials", "secretanticheat")) {
      val answer = run(buildJsonObject { put("plugin", plugin) })
      assertTrue(answer["note"]!!.jsonPrimitive.content.contains("Homes"), "$answer")
      assertFalse(answer["note"]!!.jsonPrimitive.content.contains("SecretAntiCheat"), "$answer")
    }
  }

  @Test fun `limits cut the list and count the rest`() {
    val answer = run(buildJsonObject { put("limit", 2) })["results"]!!.jsonArray.single().jsonObject
    assertEquals(2, names(answer).size)
    assertEquals(3, answer["more"]!!.jsonPrimitive.int)
    assertFailsWith<ToolError> { run(buildJsonObject { put("queries", JsonArray((1..9).map { JsonPrimitive("q$it") })) }) }
  }

  @Test fun `a check says why a line won't run`() {
    val verdicts = mapOf(
      "sethome base" to Check(Verdict.OK, "Homes", "/sethome [name]"),
      "sethom" to Check(Verdict.UNKNOWN),
      "tp @s 0 64 0" to Check(Verdict.FORBIDDEN, CommandCatalog.MINECRAFT),
      "pl" to Check(Verdict.OK, "bukkit"),
      "ac reload" to Check(Verdict.OK, "SecretAntiCheat", "/ac reload"),
      "give @s stone x" to Check(Verdict.SYNTAX, CommandCatalog.MINECRAFT, "/give <targets> <item> [<count>]", "Expected integer at position 14"),
    )
    val output = catalog.run(lang, buildJsonObject { put("check", JsonArray(verdicts.keys.map { JsonPrimitive("/$it") })) }, hidden, blocked, player, { verdicts[it] })
    val checks = output.result.jsonObject["checks"]!!.jsonArray.map { it.jsonObject }
    assertEquals(verdicts.keys.toList(), checks.map { it["command"]!!.jsonPrimitive.content })
    assertEquals(listOf(true, false, false, false, false, false), checks.map { it["ok"]!!.jsonPrimitive.boolean })
    assertEquals("/sethome [name]", checks[0]["usage"]!!.jsonPrimitive.content)
    assertTrue(checks[1]["error"]!!.jsonPrimitive.content.contains("close: /sethome"), "${checks[1]}")
    assertTrue(checks[2]["error"]!!.jsonPrimitive.content.contains("may not run"), "${checks[2]}")
    assertTrue(checks[3]["error"]!!.jsonPrimitive.content.contains("blocked"), "${checks[3]}")
    assertTrue(checks[4]["error"]!!.jsonPrimitive.content.startsWith("no command /ac"), "a hidden plugin's command is as unknown as the plugin: ${checks[4]}")
    assertFalse("SecretAntiCheat" in checks[4].toString())
    assertTrue(checks[5]["error"]!!.jsonPrimitive.content.contains("Expected integer"), "${checks[5]}")
    assertEquals("5 won't run", output.summary)
  }

  @Test fun `the chat line names what a call looks for`() {
    assertEquals("Looking through the server's commands", ToolLabels.label(lang, "search_commands", null))
    assertEquals("Looking up commands: home, warp", ToolLabels.label(lang, "search_commands", buildJsonObject { put("queries", JsonArray(listOf(JsonPrimitive("home"), JsonPrimitive("warp")))) }))
    assertEquals("Looking up Plan's commands", ToolLabels.label(lang, "search_commands", buildJsonObject { put("plugin", "Plan") }))
    assertEquals("Checking the commands", ToolLabels.label(lang, "search_commands", buildJsonObject { put("check", JsonArray(listOf(JsonPrimitive("home")))) }))
    assertEquals("Setting a reminder", ToolLabels.label(lang, "reminders", buildJsonObject { put("action", "add") }))
  }
}
