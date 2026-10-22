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

/** Makes [file] readable and writable by its owner alone: `license.key` and the spool of unsent player data. */
internal fun ownerOnly(file: File) {
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

/**
 * The server's license, `<id>.<secret>`, in `license.key` next to `config.yml`. It stays out of `config.yml` so a
 * config shared for help never carries it. Only the owner may read the file where the system knows permissions.
 */
class License(folder: File) {
  val file = File(folder, FILE)

  @Volatile private var cached: Pair<Long, String?>? = null

  /**
   * The license, or null when there's no file, it doesn't hold one, or Allay can't read it. Reread only when the file
   * changes; a failed read isn't kept, so a file that becomes readable (its owner or mode fixed) counts at once.
   */
  fun read(): String? {
    if (!file.isFile) return null
    val modified = file.lastModified()
    cached?.takeIf { it.first == modified }?.let { return it.second }
    val line = try {
      file.readLines().firstOrNull()?.trim()
    } catch (_: java.io.IOException) {
      return null
    }
    val value = line?.takeIf(::valid)
    cached = modified to value
    return value
  }

  /** A file is there, but Allay may not read it: the server runs as another user than the file's owner. */
  val unreadable get() = file.isFile && !file.canRead()

  /** A file is there but gives no license, unreadable or edited: a new license must not overwrite it. */
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

  companion object {
    const val FILE = "license.key"
    private val LICENSE = Regex("[^\\s.]+\\.\\S+")

    fun valid(value: String) = LICENSE.matches(value.trim())
  }
}

/**
 * Who asks and who pays, on every request to the backend. `Authorization` names the server (since backend 6.0): a
 * self-hosted backend's token from `server.token`, else the license, which a byok server also gets as its identity.
 * In byok the server's key goes in `X-Provider-Key` and the rest in the body's `provider` block. None is ever logged.
 */
class Credentials(private val config: Config, val license: License) {
  private val provider get() = config.provider

  val byok get() = provider.byok

  /** The token of a self-hosted backend, when `server.token` is set. */
  val token get() = config.server.token.takeIf { it.isNotEmpty() }

  fun headers(): Map<String, String> = buildMap {
    (token ?: license.read())?.let { put("Authorization", "Bearer $it") }
    if (byok) provider.apiKey.takeIf { it.isNotEmpty() }?.let { put("X-Provider-Key", it) }
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

  /** [text] without the key, the license, or the token in it. */
  fun scrub(text: String): String {
    val license = license.read()
    return Secrets.scrub(text, listOf(provider.apiKey, provider.legacyKey, license, license?.substringAfter('.'), token))
  }

  /**
   * Adds [headers] to requests for the backend, and to no other host. A network interceptor, so it runs again for every
   * redirect: a backend that redirects elsewhere never takes the key or the token along.
   */
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

/**
 * A byok server's identity on a hosted backend (since 6.0): the backend wants a license from every server, and gives one
 * free. Before a request, a byok server without `server.token` and without a license gets one and keeps it in
 * `license.key`. A self-hosted backend answers `billing: false`, and the server asks no more. Blocking; off the server thread.
 */
class Identity(private val billing: Billing, private val config: Config, private val serverName: () -> String?, private val log: java.util.logging.Logger, private val now: () -> Long = System::currentTimeMillis) {
  @Volatile private var settled = false
  @Volatile private var lastTry: Long? = null

  fun ensure() {
    if (settled || !config.provider.byok || config.server.token.isNotEmpty() || billing.license.read() != null || billing.license.broken) return
    synchronized(this) {
      if (settled || billing.license.read() != null || lastTry?.let { now() - it < RETRY_MS } == true) return
      lastTry = now()
      try {
        val claim = billing.link(serverName())
        settled = true
        if (claim.billing && claim.license != null) log.info("The backend gave this server a license as its identity; it's in ${License.FILE} (it pays for nothing)")
      } catch (error: Exception) {
        log.warning("Couldn't get a license as this server's identity: ${(error as? ApiErrorException)?.apiError?.code ?: error.javaClass.simpleName}")
      }
    }
  }

  companion object {
    /** After a failure, the next question tries again no sooner than this. */
    const val RETRY_MS = 60_000L
  }
}

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
   * The bot shows the server's name to whoever pays, so they know which server it is: `server.name`, else [serverName] (the MOTD).
   */
  fun link(serverName: String?): LicenseClaim {
    if (license.broken) throw ApiErrorException(ApiError(LICENSE_FILE_BROKEN, "license.key holds no license"))
    val hadLicense = license.read() != null
    val name = config.server.name.ifEmpty { null } ?: serverName?.let { Billing.serverName(it) }?.ifEmpty { null }
    val body = json.encodeToString(LinkRequest(name))
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

    /** A MOTD as plain text for the admin panel: no color or format codes, its lines kept, at most 128 characters. */
    fun motd(motd: String): String =
      motd.replace(FORMAT, "").lines().map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n").take(128).trim()
  }
}
