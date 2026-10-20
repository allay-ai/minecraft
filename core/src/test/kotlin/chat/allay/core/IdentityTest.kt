package chat.allay.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Logger
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A byok server's identity, and credentials that go to the backend and nowhere else, against a real local HTTP server. */
class IdentityTest {
  private val key = "sk-or-v1-0123456789abcdef0123456789a1b2"
  private val servers = mutableListOf<HttpServer>()

  /** What each request to a fake server carried: method, path, and its credential headers. */
  private data class Seen(val method: String, val path: String, val authorization: String?, val providerKey: String?)

  private fun serve(headers: Map<String, String> = emptyMap(), answer: (String, String) -> Pair<Int, String>): Pair<String, MutableList<Seen>> {
    val seen = CopyOnWriteArrayList<Seen>()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
      val path = exchange.requestURI.path
      seen += Seen(exchange.requestMethod, path, exchange.requestHeaders.getFirst("Authorization"), exchange.requestHeaders.getFirst("X-Provider-Key"))
      val (status, body) = answer(exchange.requestMethod, path)
      headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
      val bytes = body.toByteArray()
      exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
      if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
      exchange.close()
    }
    server.start()
    servers += server
    return "http://127.0.0.1:${server.address.port}" to seen
  }

  @AfterTest fun stop() = servers.forEach { it.stop(0) }

  private fun config(url: String, extra: String = "") =
    Config(YamlConfig(YamlConfig.parse("server:\n  url: \"$url\"\n$extra\nprovider:\n  mode: byok\n  byok:\n    api_key: \"$key\"")), "test")

  private val log = Logger.getAnonymousLogger()

  @Test fun `a byok server gets a license once, keeps it, and sends it from then on`() {
    val (url, seen) = serve { _, _ -> 200 to """{"license":"lic1.secretsecretsecret","license_id":"lic1","claim_code":"c","pay_url":"https://t.me/b?start=c"}""" }
    val folder = createTempDirectory("allay").toFile()
    val license = License(folder)
    val config = config(url)
    val credentials = Credentials(config, license)
    val client = HttpClient(log, config, credentials).client
    val identity = Identity(Billing(client, config, license), config, { "Moonlands" }, log)
    identity.ensure()
    identity.ensure()
    assertEquals("lic1.secretsecretsecret", license.read())
    assertEquals(listOf("POST /api/v2/license"), seen.map { "${it.method} ${it.path}" })
    // The license creation itself goes without an identity, since there's none yet.
    assertNull(seen[0].authorization)
    assertEquals("Bearer lic1.secretsecretsecret", credentials.headers()["Authorization"])
    folder.deleteRecursively()
  }

  @Test fun `a self-hosted backend says it has no billing, and the server stops asking`() {
    val (url, seen) = serve { _, _ -> 200 to """{"billing":false}""" }
    val folder = createTempDirectory("allay").toFile()
    val license = License(folder)
    val config = config(url)
    val identity = Identity(Billing(HttpClient(log, config, Credentials(config, license)).client, config, license), config, { null }, log)
    identity.ensure()
    identity.ensure()
    assertNull(license.read())
    assertEquals(1, seen.size)
    folder.deleteRecursively()
  }

  @Test fun `with server token or a license, no license is asked for, and a failure waits a minute before the next try`() {
    val (url, seen) = serve { _, _ -> 429 to """{"error":{"code":"RATE_LIMITED","message":"slow","retry_after":30}}""" }
    val folder = createTempDirectory("allay").toFile()
    val license = License(folder)
    var now = 0L
    val withToken = config(url, "  token: \"self-plugin-token-0123456789abcdef\"")
    Identity(Billing(HttpClient(log, withToken).client, withToken, license), withToken, { null }, log).ensure()
    assertTrue(seen.isEmpty())

    val config = config(url)
    val identity = Identity(Billing(HttpClient(log, config).client, config, license), config, { null }, log) { now }
    identity.ensure()
    identity.ensure()
    assertEquals(1, seen.size)
    now += Identity.RETRY_MS
    identity.ensure()
    assertEquals(2, seen.size)
    folder.deleteRecursively()
  }

  @Test fun `a redirect to another host takes neither the key nor the token along`() {
    val (elsewhere, stolen) = serve { _, _ -> 200 to "{}" }
    val (url, seen) = serve(mapOf("Location" to "$elsewhere/grab")) { _, _ -> 302 to "" }
    val folder = createTempDirectory("allay").toFile()
    val config = config(url, "  token: \"self-plugin-token-0123456789abcdef\"")
    val client = HttpClient(log, config, Credentials(config, License(folder))).client
    client.newCall(okhttp3.Request.Builder().url("$url/api/v2/users/console").build()).execute().close()
    assertEquals("Bearer self-plugin-token-0123456789abcdef", seen.single().authorization)
    assertEquals(key, seen.single().providerKey)
    assertEquals(listOf(Seen("GET", "/grab", null, null)), stolen.toList())
    folder.deleteRecursively()
  }
}
