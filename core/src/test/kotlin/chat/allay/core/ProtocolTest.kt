package chat.allay.core

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import chat.allay.core.Protocol.Event
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolTest {
  @Test fun `requests leave out what is unknown`() {
    val request = Protocol.ChatRequest(
      mode = "ask", message = "hi", user_id = "console",
      server = Protocol.ServerInfo(software = "Paper", version = "26.3", plugin_version = "3.0.0"), tools = listOf("get_world"),
    )
    val text = Protocol.json.encodeToString(Protocol.ChatRequest.serializer(), request)
    assertEquals(
      """{"mode":"ask","message":"hi","platform":"minecraft","visibility":"private","user_id":"console","server":{"software":"Paper","version":"26.3","plugin_version":"3.0.0"},"tools":["get_world"]}""",
      text,
    )
  }

  @Test fun `the server says what Allay runs on and where`() {
    val server = Protocol.ServerInfo(software = "Paper", version = "26.3", plugin_version = "6.10.0", loader = "spigot", side = "server")
    assertEquals(
      """{"software":"Paper","version":"26.3","plugin_version":"6.10.0","loader":"spigot","side":"server"}""",
      Protocol.json.encodeToString(Protocol.ServerInfo.serializer(), server),
    )
  }

  @Test fun `what the operator wrote about the server goes as server about, block text and all`() {
    val config = Config(YamlConfig(YamlConfig.parse("server:\n  about: |\n    Moonlands: hard survival.\n    Claims with a golden shovel.\n")), "test")
    assertEquals("Moonlands: hard survival.\nClaims with a golden shovel.", config.server.about)
    assertEquals("", Config(YamlConfig(YamlConfig.parse("server:\n  url: x\n")), "test").server.about)
    val server = Protocol.ServerInfo(software = "Paper", version = "26.3", plugin_version = "6.20.0", about = config.server.about)
    assertEquals(
      """{"software":"Paper","version":"26.3","plugin_version":"6.20.0","about":"Moonlands: hard survival.\nClaims with a golden shovel."}""",
      Protocol.json.encodeToString(Protocol.ServerInfo.serializer(), server),
    )
  }

  @Test fun `full privacy goes with the player only when it's on`() {
    val off = Protocol.PlayerInfo("Steve", "u", op = false)
    val on = off.copy(private = true)
    assertEquals("""{"name":"Steve","uuid":"u","op":false}""", Protocol.json.encodeToString(Protocol.PlayerInfo.serializer(), off))
    assertEquals("""{"name":"Steve","uuid":"u","op":false,"private":true}""", Protocol.json.encodeToString(Protocol.PlayerInfo.serializer(), on))
  }

  @Test fun `every event decodes`() {
    assertEquals("r1", assertIs<Event.Started>(Protocol.decode("start", """{"run_id":"r1","model":"m"}""")).data.run_id)
    assertEquals("hm", assertIs<Event.Reasoning>(Protocol.decode("reasoning", """{"delta":"hm"}""")).delta)
    assertEquals("Hi", assertIs<Event.Text>(Protocol.decode("text", """{"delta":"Hi"}""")).delta)
    val call = assertIs<Event.Call>(Protocol.decode("tool_call", """{"call_id":"c","name":"get_inventory","args":{"include_enderchest":true}}""")).data
    assertEquals(JsonPrimitive(true), call.args["include_enderchest"])
    val status = assertIs<Event.Status>(Protocol.decode("tool_status", """{"call_id":"s","name":"search_web","kind":"server","state":"finished","summary":"5 sources"}""")).data
    assertEquals("5 sources", status.summary)
    assertEquals(listOf("time set day"), assertIs<Event.Command>(Protocol.decode("commands", """{"is_sequence":true,"commands":["time set day"]}""")).data.commands)
    assertEquals(42, assertIs<Event.Spent>(Protocol.decode("usage", """{"input_tokens":1,"output_tokens":42}""")).data.output_tokens)
    assertEquals("ok", assertIs<Event.Finished>(Protocol.decode("done", """{"answer":"ok","duration_ms":5}""")).data.answer)
    assertEquals("TOOL_TIMEOUT", assertIs<Event.Failed>(Protocol.decode("error", """{"code":"TOOL_TIMEOUT","message":"x"}""")).data.code)
  }

  @Test fun `unknown events, extra fields, and broken JSON are tolerated`() {
    assertNull(Protocol.decode("future_event", "{}"))
    assertNull(Protocol.decode("text", "not json"))
    assertIs<Event.Text>(Protocol.decode("text", """{"delta":"a","extra":1}"""))
    assertIs<Event.Call>(Protocol.decode("tool_call", """{"call_id":"c","name":"get_world"}""")).also { assertTrue(it.data.args.isEmpty()) }
  }

  @Test fun `tool results carry either a result or an error`() {
    val ok = Protocol.ToolResult(ok = true, result = buildJsonObject { put("day", 3) })
    assertEquals("""{"ok":true,"result":{"day":3}}""", Protocol.json.encodeToString(Protocol.ToolResult.serializer(), ok))
    val failed = Protocol.ToolResult(ok = false, error = "no player")
    assertEquals("""{"ok":false,"error":"no player"}""", Protocol.json.encodeToString(Protocol.ToolResult.serializer(), failed))
  }
}
