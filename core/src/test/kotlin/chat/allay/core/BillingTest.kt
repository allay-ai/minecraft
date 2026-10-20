package chat.allay.core

import kotlinx.serialization.json.Json
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BillingTest {
  private val key = "sk-or-v1-0123456789abcdef0123456789a1b2"

  private fun config(yaml: String) = Config(YamlConfig(YamlConfig.parse(yaml)), "test")

  private val texts by lazy {
    Texts(Texts.BUNDLED.associateWith { code -> InputStreamReader(javaClass.getResourceAsStream("/lang/$code.yml")!!, Charsets.UTF_8).use(Texts::read) }, "en")
  }

  @Test fun `an old config without the provider block is hosted with defaults`() {
    val provider = config("server:\n  url: \"https://allay.chat\"").provider
    assertEquals(Config.HOSTED, provider.mode)
    assertFalse(provider.byok)
    assertEquals("https://openrouter.ai/api/v1", provider.baseUrl)
    assertEquals("", provider.apiKey)
    assertEquals("", provider.model)
    assertEquals("", provider.searchModel)
    assertNull(provider.legacyKey)
  }

  @Test fun `byok reads its block, and anything but byok is hosted`() {
    val provider = config("provider:\n  mode: BYOK\n  byok:\n    base_url: \"https://api.example.com/v1/\"\n    api_key: \" $key \"\n    model: m\n    search_model: s").provider
    assertTrue(provider.byok)
    assertEquals("https://api.example.com/v1", provider.baseUrl)
    assertEquals(key, provider.apiKey)
    assertEquals("m", provider.model)
    assertEquals("s", provider.searchModel)
    assertEquals(Config.HOSTED, config("provider:\n  mode: byk").provider.mode)
    assertEquals(key, config("http:\n  custom_headers:\n    X-OpenRouter-Token: \"$key\"").provider.legacyKey)
  }

  @Test fun `secrets show at most five characters in front and four behind`() {
    assertEquals("sk-or…a1b2", Secrets.mask(key))
    assertEquals("ab…", Secrets.mask("abcdefghij"))
    assertEquals("…", Secrets.mask("short"))
    assertEquals("", Secrets.mask(null))
    val text = Secrets.scrub("bad key $key, license lic1.s3cr3tvalue!", listOf(key, "lic1.s3cr3tvalue", null, ""))
    assertEquals("bad key sk-or…a1b2, license lic1.…alue!", text)
  }

  @Test fun `the license file is written for the owner only and read back`() {
    val folder = createTempDirectory("allay").toFile()
    val license = License(folder)
    assertNull(license.read())
    assertFalse(license.broken)
    license.write("k3f9a2.secretsecretsecret")
    assertEquals("k3f9a2.secretsecretsecret", license.read())
    assertEquals("k3f9a2", license.id)
    if (Files.getFileStore(license.file.toPath()).supportsFileAttributeView("posix")) {
      assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(license.file.toPath())))
    }
    assertFailsWith<IllegalArgumentException> { license.write("no dot here") }
    license.file.writeText("garbage\n")
    license.file.setLastModified(license.file.lastModified() + 2000)
    assertNull(license.read())
    assertTrue(license.broken)
    folder.deleteRecursively()
  }

  @Test fun `a license that becomes readable counts at once, though the file didn't change`() {
    val folder = createTempDirectory("allay").toFile()
    val license = License(folder).apply { write("k3f9a2.secretsecretsecret") }
    val path = license.file.toPath()
    // Root reads anything, and Windows has no POSIX modes: nothing to check there.
    if (!Files.getFileStore(path).supportsFileAttributeView("posix") || System.getProperty("user.name") == "root") return folder.deleteRecursively().let {}
    val modified = license.file.lastModified()
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("---------"))
    assertNull(License(folder).read())
    val stuck = License(folder)
    assertNull(stuck.read())
    assertTrue(stuck.unreadable)
    // Not overwritten by a new license either.
    assertTrue(stuck.broken)
    // chown and chmod leave the modification time as it was.
    Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
    assertEquals(modified, license.file.lastModified())
    assertEquals("k3f9a2.secretsecretsecret", stuck.read())
    assertFalse(stuck.unreadable)
    folder.deleteRecursively()
  }

  @Test fun `credentials send the key in byok and the license in hosted`() {
    val folder = createTempDirectory("allay").toFile()
    val license = License(folder).apply { write("id1.secret1secret1") }
    val byok = Credentials(config("provider:\n  mode: byok\n  byok:\n    api_key: \"$key\"\n    model: m"), license)
    // Since 6.0 a byok server sends its license too, as its identity.
    assertEquals(mapOf("Authorization" to "Bearer id1.secret1secret1", "X-Provider-Key" to key), byok.headers())
    assertEquals(Protocol.Provider("https://openrouter.ai/api/v1", "m", ""), byok.block())
    assertEquals("key sk-or…a1b2", byok.scrub("key $key"))
    assertEquals(mapOf("Authorization" to "Bearer id1.secret1secret1"), Credentials(config("provider:\n  mode: byok"), license).headers())
    assertEquals(emptyMap(), Credentials(config("provider:\n  mode: byok"), License(createTempDirectory("allay").toFile())).headers())
    val hosted = Credentials(config(""), license)
    assertEquals(mapOf("Authorization" to "Bearer id1.secret1secret1"), hosted.headers())
    assertNull(hosted.block())
    assertEquals("x id1.s…ret1 y", hosted.scrub("x id1.secret1secret1 y"))
    folder.deleteRecursively()
  }

  @Test fun `a self-hosted backend's token wins over the license, and never shows`() {
    val folder = createTempDirectory("allay").toFile()
    val license = License(folder).apply { write("id1.secret1secret1") }
    val token = "self-plugin-token-0123456789abcdef"
    val credentials = Credentials(config("server:\n  url: \"http://allay:3000\"\n  token: \" $token \"\nprovider:\n  mode: byok\n  byok:\n    api_key: \"$key\""), license)
    assertEquals(token, credentials.token)
    assertEquals(mapOf("Authorization" to "Bearer $token", "X-Provider-Key" to key), credentials.headers())
    assertEquals("401 for self-…cdef", credentials.scrub("401 for $token"))
    assertNull(Credentials(config(""), license).token)
    folder.deleteRecursively()
  }

  @Test fun `the log never shows a player's id in a URL`() {
    assertEquals("https://allay.chat/api/v2/users/…/memories", HttpClient.loggable("https://allay.chat/api/v2/users/5627dd98-e6be-3c21-b8a8-e92344183641/memories"))
    assertEquals("https://allay.chat/api/v2/users/…", HttpClient.loggable("https://allay.chat/api/v2/users/console"))
    assertEquals("https://allay.chat/api/v2/chat", HttpClient.loggable("https://allay.chat/api/v2/chat"))
  }

  @Test fun `requests carry the provider block only in byok`() {
    val request = Protocol.ChatRequest(
      mode = "ask", message = "hi", user_id = "console",
      server = Protocol.ServerInfo(software = "Paper", version = "26.3", plugin_version = "4.1.0"), tools = emptyList(),
      provider = Protocol.Provider("https://openrouter.ai/api/v1", "", "perplexity/sonar"),
    )
    val text = Protocol.json.encodeToString(Protocol.ChatRequest.serializer(), request)
    assertTrue(text.endsWith(""""provider":{"base_url":"https://openrouter.ai/api/v1","model":"","search_model":"perplexity/sonar"}}"""), text)
  }

  @Test fun `the license status decodes`() {
    val json = Json { ignoreUnknownKeys = true }
    val status = json.decodeFromString<LicenseStatus>(
      """{"billing":true,"license_id":"k3f9a2","linked":true,"active":true,"plan":"month","paid_until":"2026-11-14T10:00:00Z",
        |"renews":true,"quota":{"used_percent":62,"resets_at":"2026-11-14T10:00:00Z"},"last_payment_at":"2026-10-14T10:00:00Z","extra":1}""".trimMargin(),
    )
    assertEquals("month", status.plan)
    assertEquals(62.0, status.quota?.used_percent)
    assertTrue(status.renews)
    val off = json.decodeFromString<LicenseStatus>("""{"billing":false}""")
    assertFalse(off.billing)
    assertNull(off.plan)
    val fresh = json.decodeFromString<LicenseStatus>("""{"billing":true,"license_id":"k","linked":false,"active":false,"plan":null,"paid_until":null,"renews":false,"quota":null,"last_payment_at":null}""")
    assertNull(fresh.paid_until)
    val claim = json.decodeFromString<LicenseClaim>("""{"license":"k.s","license_id":"k","claim_code":"c","pay_url":"https://t.me/bot?start=c","claim_expires_at":"2026-10-15T10:00:00Z"}""")
    assertEquals("https://t.me/bot?start=c", claim.pay_url)
    assertTrue(claim.billing)
  }

  @Test fun `error codes come with their details, before the stream and in it`() {
    val parser = ErrorParser()
    val quota = parser.parse("""{"error":{"code":"QUOTA_EXCEEDED","message":"spent","resets_at":"2026-11-14T10:00:00Z"}}""", 402)!!
    assertEquals(ApiError("QUOTA_EXCEEDED", "spent", 402, resetsAt = "2026-11-14T10:00:00Z"), quota)
    assertEquals(30L, parser.parse("""{"error":{"code":"RATE_LIMITED","message":"slow down","retry_after":30}}""", 429)!!.retryAfter)
    assertEquals("QUESTION_TOO_LONG", parser.parse("""{"error":"too long","code":"QUESTION_TOO_LONG","status":"error","timestamp":"t"}""", 400)!!.code)
    assertNull(parser.parse("<html>", 502))
    val event = assertIs<Protocol.Event.Failed>(Protocol.decode("error", """{"code":"MODEL_NO_TOOLS","message":"no tools"}""")).data
    assertEquals("MODEL_NO_TOOLS", event.code)
    assertEquals(5L, assertIs<Protocol.Event.Failed>(Protocol.decode("error", """{"code":"RATE_LIMITED","message":"","retry_after":5}""")).data.retry_after)
  }

  @Test fun `players read billing errors in their language`() {
    val en = texts.lang("en")
    val utc = ZoneOffset.UTC
    assertEquals(
      "&eAllay has used up this server's limit for now. It refills on 14 November, or sooner if an admin tops it up.",
      ErrorHandler.message(en, ApiError("QUOTA_EXCEEDED", "", 402, resetsAt = "${java.time.Year.now(utc)}-11-14T10:00:00Z"), utc),
    )
    assertEquals(en.text("errors.quota_spent"), ErrorHandler.message(en, ApiError("QUOTA_EXCEEDED", "", 402), utc))
    assertEquals("&eAllay is swamped with questions from this server. Try again in 1 second.", ErrorHandler.message(en, ApiError("RATE_LIMITED", "", 429, retryAfter = 1)))
    assertEquals(en.text("errors.http.429"), ErrorHandler.message(en, ApiError("RATE_LIMITED", "", 429)))
    for (code in ErrorCodes.SUBSCRIPTION + ErrorCodes.PROVIDER) assertTrue(en.has("errors.api.$code"), code)
    for (code in ErrorCodes.PROVIDER + ErrorCodes.LICENSE_INVALID + ErrorCodes.TOKEN) assertTrue(en.has("billing.hint.$code"), code)
    for (code in ErrorCodes.TOKEN) assertTrue(en.has("errors.api.$code"), code)
    assertTrue(en.has("billing.hint.identity"))
  }

  @Test fun `dates read as each language says them`() {
    val now = Instant.parse("2026-10-14T10:00:00Z")
    val utc = ZoneOffset.UTC
    assertEquals("14 November", BillingDates.format(texts.lang("en"), "2026-11-14T10:00:00Z", utc, now))
    assertEquals("14 ноября", BillingDates.format(texts.lang("ru"), "2026-11-14T10:00:00Z", utc, now))
    assertEquals("14. November", BillingDates.format(texts.lang("de"), "2026-11-14T10:00:00Z", utc, now))
    assertEquals("14 de noviembre", BillingDates.format(texts.lang("es"), "2026-11-14T10:00:00Z", utc, now))
    assertEquals("14 de novembro de 2027", BillingDates.format(texts.lang("pt"), "2027-11-14T10:00:00Z", utc, now))
    assertNull(BillingDates.format(texts.lang("en"), null, utc, now))
    assertNull(BillingDates.format(texts.lang("en"), "soon", utc, now))
  }

  @Test fun `the quota bar fills and changes color`() {
    assertEquals("&a" + "■".repeat(12) + "&8" + "□".repeat(8), QuotaBar.render(62.0))
    assertEquals("&e" + "■".repeat(16) + "&8" + "□".repeat(4), QuotaBar.render(80.0))
    assertEquals("&c" + "■".repeat(20) + "&8", QuotaBar.render(130.0))
    assertEquals("&a&8" + "□".repeat(20), QuotaBar.render(-5.0))
    assertEquals(99, QuotaBar.percent(99.6))
  }

  @Test fun `operators hear each change once`() {
    val now = Instant.parse("2026-10-14T10:00:00Z")
    val period = "2026-11-14T10:00:00Z"
    fun status(used: Double, payment: String? = "2026-10-14T09:00:00Z", until: String = period, renews: Boolean = true) =
      LicenseStatus(plan = "month", paid_until = until, renews = renews, quota = LicenseStatus.Quota(used, period), last_payment_at = payment, active = true, linked = true)

    val (paid, first) = Announcements.next(Announced(), status(10.0), now)
    assertEquals(listOf<Notice>(Notice.Paid(period)), first)
    assertEquals(emptyList(), Announcements.next(paid, status(10.0), now).second)

    val (at80, crossed) = Announcements.next(paid, status(85.0), now)
    assertEquals(listOf<Notice>(Notice.Quota(80, period)), crossed)
    assertEquals(emptyList(), Announcements.next(at80, status(90.0), now).second)
    val (at100, spent) = Announcements.next(at80, status(100.0), now)
    assertEquals(listOf<Notice>(Notice.Quota(100, period)), spent)
    // A top-up drops the level; passing 80 again is news again.
    val (topped, _) = Announcements.next(at100, status(40.0), now)
    assertEquals(listOf<Notice>(Notice.Quota(80, period)), Announcements.next(topped, status(81.0), now).second)

    val soon = "2026-10-16T10:00:00Z"
    val (warned, ending) = Announcements.next(paid, status(10.0, until = soon, renews = false), now)
    assertEquals(listOf<Notice>(Notice.Expiring(soon)), ending)
    assertEquals(emptyList(), Announcements.next(warned, status(10.0, until = soon, renews = false), now).second)
    assertEquals(emptyList(), Announcements.next(paid, status(10.0, until = soon, renews = true), now).second)
    assertEquals(emptyList(), Announcements.next(Announced(), LicenseStatus(billing = false), now).second)
  }

  @Test fun `announcements survive a restart`() {
    val folder = createTempDirectory("allay").toFile()
    val log = java.util.logging.Logger.getAnonymousLogger()
    val status = LicenseStatus(last_payment_at = "2026-10-14T09:00:00Z", paid_until = "2026-11-14T10:00:00Z")
    assertEquals(1, Announcements(folder, log).check(status).size)
    assertEquals(0, Announcements(folder, log).check(status).size)
    folder.deleteRecursively()
  }

  @Test fun `the server's name is its MOTD as plain text`() {
    assertEquals("Moonlands survival", Billing.serverName("§6§lMoonlands\n  §7survival  "))
    assertEquals("x".repeat(64), Billing.serverName("&a" + "x".repeat(80)))
    assertEquals("", Billing.serverName("§r "))
  }
}
