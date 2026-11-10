package chat.allay.core

import chat.allay.core.tools.ClientTool
import chat.allay.core.tools.TimeZones
import chat.allay.core.tools.ToolOutput
import chat.allay.core.tools.ToolRegistry
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A question through a backend that drops, restarts, and comes back, against a real local HTTP server. */
class ReconnectTest {
  private val servers = mutableListOf<HttpServer>()
  private val log = Logger.getAnonymousLogger()
  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

  @AfterTest fun stop() = servers.forEach { it.stop(0) }

  /** What reached the fake backend: method, path, `Last-Event-ID`, and the body. */
  private data class Seen(val method: String, val path: String, val lastEventId: String?, val body: String)

  private fun serve(answer: (Seen, HttpExchange) -> Unit): Pair<String, MutableList<Seen>> {
    val seen = CopyOnWriteArrayList<Seen>()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
      val request = Seen(exchange.requestMethod, exchange.requestURI.path, exchange.requestHeaders.getFirst("Last-Event-ID"), exchange.requestBody.readBytes().decodeToString())
      seen += request
      answer(request, exchange)
      exchange.close()
    }
    // A stream may hold its request open while a tool's result comes in on another, as with the real backend.
    server.executor = java.util.concurrent.Executors.newCachedThreadPool()
    server.start()
    servers += server
    return "http://127.0.0.1:${server.address.port}" to seen
  }

  private fun HttpExchange.reply(status: Int, body: String = "") {
    val bytes = body.toByteArray()
    sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
    if (bytes.isNotEmpty()) responseBody.use { it.write(bytes) }
  }

  /** Streams [events] as `id`, `event`, `data` frames, then ends the stream: with no `done` among them, it was cut off. */
  private fun HttpExchange.events(vararg events: Triple<Int?, String, String>) {
    responseHeaders.add("Content-Type", "text/event-stream")
    sendResponseHeaders(200, 0)
    responseBody.use { body ->
      for ((id, name, data) in events) body.write(((id?.let { "id: $it\n" } ?: "") + "event: $name\ndata: $data\n\n").toByteArray())
    }
  }

  private val asked = AtomicInteger()

  private val world = object : ClientTool<Unit> {
    override val name = "get_world"
    override fun run(context: Unit, args: JsonObject): ToolOutput {
      asked.incrementAndGet()
      return ToolOutput(buildJsonObject { put("dimension", "overworld") }, "overworld")
    }
  }

  private fun service(url: String): Service<Unit> {
    val config = Config(YamlConfig(YamlConfig.parse("server:\n  url: \"$url\"\n  token: \"${"t".repeat(32)}\"\nhttp:\n  reconnect_seconds: 30")), "test")
    val client = HttpClient(log, config).client
    val data = PlayerData(object : PlayerData.Backend {
      override suspend fun state(userId: String): PlayerState? = null
      override suspend fun saveState(userId: String, state: PlayerState) {}
      override suspend fun death(userId: String, death: chat.allay.core.tools.Death) {}
    }, scope, null, log, { false }, { false }, { false })
    val host = object : Host<Unit> {
      override val log = this@ReconnectTest.log
      override val pluginVersion = "test"
      override val software = "Paper"
      override val gameVersion = "26.3"
      override val loader = "spigot"
      override fun context(run: Run) = Unit
      override fun <T> onMain(seconds: Long, block: () -> T): T = block()
    }
    val credentials = Credentials(config, License(createTempDirectory("allay").toFile()))
    return Service(config, client, ToolRegistry(listOf(world)) { true }, TimeZones(data) { "UTC" }, "server", host, scope, credentials)
  }

  private val player = object : Asker {
    override val name = "Steve"
    override val uuid: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    override val operator = false
    override val locale: String? = null
    override fun can(permission: String) = false
  }

  private fun run() = Run(player, Run.Mode.ASK, Texts(mapOf("en" to emptyMap()), "en").lang("en"), 3)

  private val listener = object : Service.Listener {
    override fun toolStarted(callId: String, name: String, args: JsonObject?) {}
    override fun toolFinished(callId: String, summary: String?, failed: Boolean, refused: Boolean) {}
  }

  @Test fun `a stream cut off mid-answer reads on from the last event it got`() = runBlocking {
    val (url, seen) = serve { request, exchange ->
      when (request.method) {
        "POST" -> exchange.events(Triple(1, "start", """{"run_id":"r1","model":"m"}"""), Triple(2, "text", """{"delta":"Hel"}"""))
        else -> exchange.events(Triple(3, "text", """{"delta":"lo"}"""), Triple(4, "done", """{"answer":"Hello","duration_ms":1}"""))
      }
    }
    val outcome = service(url).chat(run(), "hi", listener)
    assertEquals("Hello", outcome.answer)
    assertEquals(listOf("POST /api/v2/chat", "GET /api/v2/chat/r1/events"), seen.map { "${it.method} ${it.path}" })
    assertEquals("2", seen[1].lastEventId)
  }

  @Test fun `a backend that restarts gets the same question again under the same request id`() = runBlocking {
    val tries = AtomicInteger()
    val (url, seen) = serve { _, exchange ->
      if (tries.incrementAndGet() < 3) exchange.reply(502, "bad gateway")
      else exchange.events(Triple(1, "start", """{"run_id":"r2","model":"m"}"""), Triple(2, "done", """{"answer":"Back","duration_ms":1}"""))
    }
    val run = run()
    assertEquals("Back", service(url).chat(run, "hi", listener).answer)
    assertEquals(3, seen.size)
    assertTrue(seen.all { run.requestId in it.body }, "every try names the same question")
  }

  @Test fun `a backend that numbers nothing fails as before once its run started`() = runBlocking {
    val (url, seen) = serve { _, exchange -> exchange.events(Triple(null, "start", """{"run_id":"r3","model":"m"}"""), Triple(null, "text", """{"delta":"Hel"}""")) }
    // Without ids the cut-off text is all there is, so it's the answer, as before 6.35.
    assertEquals("Hel", service(url).chat(run(), "hi", listener).answer)
    assertEquals(1, seen.size)
  }

  @Test fun `a refusal goes through at once`() = runBlocking {
    val (url, seen) = serve { _, exchange -> exchange.reply(402, """{"error":{"code":"QUOTA_EXCEEDED","message":"spent"}}""") }
    val error = assertFailsWith<ApiErrorException> { service(url).chat(run(), "hi", listener) }
    assertEquals("QUOTA_EXCEEDED", error.apiError.code)
    assertEquals(1, seen.size)
  }

  @Test fun `a tool's result goes again until the backend takes it, and the call runs once`() = runBlocking {
    val posts = AtomicInteger()
    val (url, _) = serve { request, exchange ->
      when {
        request.path.endsWith("/tools/c1") -> if (posts.incrementAndGet() == 1) exchange.reply(503, "restarting") else exchange.reply(200, """{"accepted":true}""")
        request.method == "POST" -> exchange.events(
          Triple(1, "start", """{"run_id":"r4","model":"m"}"""),
          Triple(2, "tool_call", """{"call_id":"c1","name":"get_world","args":{}}"""),
        )
        else -> {
          // The rest waits until the result got through, as the backend's run would.
          while (posts.get() < 2) Thread.sleep(20)
          exchange.events(Triple(2, "tool_call", """{"call_id":"c1","name":"get_world","args":{}}"""), Triple(3, "done", """{"answer":"Overworld","duration_ms":1}"""))
        }
      }
    }
    assertEquals("Overworld", service(url).chat(run(), "where am I", listener).answer)
    assertEquals(2, posts.get())
    assertEquals(1, asked.get(), "the tool ran once, though the stream brought its call twice")
  }

  @Test fun `pauses start short and the wait ends after its time without getting through`() {
    assertEquals(listOf(1L, 2L, 4L, 8L, 15L, 15L), (1..6).map(Retry::pause))
    var now = 0L
    val reach = Reach(30) { now }
    val dropped = java.io.IOException("connection reset")
    assertEquals(1L, reach.next(dropped))
    now = 20_000
    assertEquals(2L, reach.next(dropped))
    reach.reached()
    now = 45_000
    assertEquals(1L, reach.next(dropped), "getting through started the wait over")
    now = 80_000
    assertNull(reach.next(dropped))
    assertNull(Reach(30) { 0L }.next(ApiErrorException(ApiError("QUOTA_EXCEEDED", "spent"))))
    assertEquals(20L, Reach(30) { 0L }.next(ApiErrorException(ApiError("RATE_LIMITED", "slow down", retryAfter = 20))))
  }
}
