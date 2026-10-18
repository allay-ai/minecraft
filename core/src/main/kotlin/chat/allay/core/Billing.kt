package chat.allay.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/** Keys and licenses as people may see them: a few characters at each end, never the whole thing. */
object Secrets {
  /** `sk-or-v1-…a1b2` becomes `sk-or…a1b2`: at most the first 5 and the last 4 characters, fewer for short values. */
  fun mask(value: String?): String {
    val text = value?.trim().orEmpty()
    return when {
      text.isEmpty() -> ""
      text.length >= 16 -> text.take(5) + "…" + text.takeLast(4)
      text.length >= 8 -> text.take(2) + "…"
      else -> "…"
    }
  }

  /** [text] with every one of [secrets] masked, so an error message can't carry a key into chat or the log. */
  fun scrub(text: String, secrets: Collection<String?>): String = secrets.asSequence()
    .filterNotNull().map(String::trim).filter { it.length >= 6 }.sortedByDescending { it.length }
    .fold(text) { result, secret -> result.replace(secret, mask(secret)) }
}

/**
 * The server's license, `<id>.<secret>`, in `license.key` next to `config.yml`. It stays out of `config.yml` so a
 * config shared for help never carries it. Only the owner may read the file where the system knows permissions.
 */
class License(folder: File) {
  val file = File(folder, FILE)

  @Volatile private var cached: Pair<Long, String?>? = null

  /** The license, or null when there's no file or it doesn't hold one. Reread only when the file changes. */
  fun read(): String? {
    if (!file.isFile) return null
    val modified = file.lastModified()
    cached?.takeIf { it.first == modified }?.let { return it.second }
    val value = runCatching { file.readLines().firstOrNull()?.trim() }.getOrNull()?.takeIf(::valid)
    cached = modified to value
    return value
  }

  /** A file is there but holds no license: someone edited it, and a new license must not overwrite it. */
  val broken get() = file.isFile && read() == null

  /** The public half, which the backend's status also names. */
  val id get() = read()?.substringBefore('.')

  @Synchronized fun write(license: String) {
    require(valid(license)) { "not a license" }
    val folder = file.absoluteFile.parentFile
    folder.mkdirs()
    val temp = File(folder, "$FILE.tmp")
    temp.delete()
    temp.createNewFile()
    ownerOnly(temp)
    temp.writeText(license.trim() + "\n")
    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    cached = null
  }

  private fun ownerOnly(file: File) {
    try {
      Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
    } catch (_: UnsupportedOperationException) {
      // Windows: no POSIX bits, so take away what java.io can.
      file.setReadable(false, false)
      file.setReadable(true, true)
      file.setWritable(false, false)
      file.setWritable(true, true)
    }
  }

  companion object {
    const val FILE = "license.key"
    private val LICENSE = Regex("[^\\s.]+\\.\\S+")

    fun valid(value: String) = LICENSE.matches(value.trim())
  }
}

/**
 * Who pays for each request, by `provider.mode`: in byok the server's key goes in `X-Provider-Key` and the rest in the
 * body's `provider` block; in hosted the license goes in `Authorization` once there is one. Neither is ever logged.
 */
class Credentials(private val config: Config, val license: License) {
  private val provider get() = config.provider

  val byok get() = provider.byok

  fun headers(): Map<String, String> = if (byok) {
    provider.apiKey.takeIf { it.isNotEmpty() }?.let { mapOf("X-Provider-Key" to it) }.orEmpty()
  } else {
    license.read()?.let { mapOf("Authorization" to "Bearer $it") }.orEmpty()
  }

  /**
   * The body's `provider` block. [model] is the one the asker's tier picks: in byok mode it replaces the configured
   * model; in hosted mode it goes alone, and only a backend with billing off (self-hosted) honors it.
   */
  fun block(model: String? = null): Protocol.Provider? = when {
    byok -> Protocol.Provider(provider.baseUrl, model ?: provider.model, provider.searchModel)
    model != null -> Protocol.Provider("", model, "")
    else -> null
  }

  /** [text] without the key or the license in it. */
  fun scrub(text: String): String {
    val license = license.read()
    return Secrets.scrub(text, listOf(provider.apiKey, provider.legacyKey, license, license?.substringAfter('.')))
  }

  /** Adds [headers] to requests for the backend, and to no other host. */
  fun interceptor() = Interceptor { chain ->
    val request = chain.request()
    val backend = config.server.url.toHttpUrlOrNull()
    if (backend == null || request.url.host != backend.host || request.url.port != backend.port) return@Interceptor chain.proceed(request)
    val builder = request.newBuilder()
    headers().forEach { (name, value) -> if (request.header(name) == null) builder.header(name, value) }
    chain.proceed(builder.build())
  }
}

/** `GET /api/v2/license`: the license's state. `billing: false` comes from a backend that serves everyone on its own key. */
@Serializable data class LicenseStatus(
  val billing: Boolean = true,
  val license_id: String? = null,
  val linked: Boolean = false,
  val active: Boolean = false,
  /** `week`, `month`, or `year`; null before the first payment. */
  val plan: String? = null,
  val paid_until: String? = null,
  /** A Telegram Stars subscription renews it by itself. */
  val renews: Boolean = false,
  val quota: Quota? = null,
  val last_payment_at: String? = null,
) {
  /** How much of this period's budget is spent; [resets_at] is when it refills. */
  @Serializable data class Quota(val used_percent: Double = 0.0, val resets_at: String? = null)
}

/** `POST /api/v2/license`: a claim code to pay with, and [license] when the backend just made one. */
@Serializable data class LicenseClaim(
  val billing: Boolean = true,
  val license: String? = null,
  val license_id: String? = null,
  val claim_code: String? = null,
  val pay_url: String? = null,
  val claim_expires_at: String? = null,
)

/** The license endpoints. Blocking; call them off the server thread. */
class Billing(private val client: OkHttpClient, private val config: Config, val license: License) {
  private val json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
  }
  private val errorParser = ErrorParser()

  @Serializable private data class LinkRequest(val name: String? = null)

  fun status(): LicenseStatus = json.decodeFromString(send(Request.Builder().url(url()).get().build()))

  /**
   * A payment link. With a license, a fresh claim code for it; without one, a new license, saved before this returns
   * so a crash can't lose what someone may pay for. A `license.key` that holds no license is never overwritten.
   * The bot shows [serverName] (the MOTD) to whoever pays, so they know which server it is.
   */
  fun link(serverName: String?): LicenseClaim {
    if (license.broken) throw ApiErrorException(ApiError(LICENSE_FILE_BROKEN, "license.key holds no license"))
    val hadLicense = license.read() != null
    val body = json.encodeToString(LinkRequest(serverName?.let { Billing.serverName(it) }?.ifEmpty { null }))
    val claim = json.decodeFromString<LicenseClaim>(send(Request.Builder().url(url()).post(body.toRequestBody(JSON)).build()))
    if (!hadLicense && claim.billing) claim.license?.takeIf(License::valid)?.let(license::write)
    return claim
  }

  private fun url() = "${config.server.url}/api/v2/license"

  private fun send(request: Request): String = client.newCall(request).execute().use { response ->
    if (!response.isSuccessful) throw ApiErrorException(errorParser.parseErrorFromResponse(response))
    response.body.string()
  }

  companion object {
    /** Not from the backend: `license.key` is there but holds no license. */
    const val LICENSE_FILE_BROKEN = "LICENSE_FILE_BROKEN"

    private val JSON = "application/json".toMediaType()
    private val FORMAT = Regex("[§&][0-9a-fk-orx]", RegexOption.IGNORE_CASE)

    /** A MOTD as plain text for the bot: no color or format codes, one line, at most 64 characters. */
    fun serverName(motd: String): String = motd.replace(FORMAT, "").replace(Regex("\\s+"), " ").trim().take(64).trim()
  }
}
