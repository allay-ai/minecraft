package chat.allay.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** What `POST /api/v2/admin/init` answered (since backend 5.7). */
sealed interface AdminInit {
  /** A one-time setup link that makes whoever opens it the panel's owner. */
  data class Link(val url: String, val expiresAt: String?) : AdminInit
  /** A one-time link that sets a new password for a panel account (since backend 5.10, once one exists). */
  data class Recovery(val url: String, val expiresAt: String?) : AdminInit
  /** The panel has an admin already and nothing to recover (only the environment's, or a backend before 5.10); [url] is where to sign in. */
  data class Exists(val url: String?) : AdminInit
  /** A backend with billing on sets up its panel from its own log. */
  data object NotSelfHosted : AdminInit
  /** A backend older than 5.7 has no panel. */
  data object Unsupported : AdminInit
}

/** `/allay init`: asks the backend for the admin panel's setup or recovery link. Blocking; call it off the server thread. */
class AdminSetup(private val client: OkHttpClient, private val config: Config) {
  fun init(): AdminInit {
    val request = Request.Builder().url("${config.server.url}/api/v2/admin/init").post(ByteArray(0).toRequestBody()).build()
    return client.newCall(request).execute().use { response -> parse(response.code, response.body.string()) }
  }

  companion object {
    private val json = Json { ignoreUnknownKeys = true }

    /** Only an https or http address the backend gave; anything else isn't a link to show. */
    private fun link(value: String?) = value?.takeIf { it.startsWith("https://") || it.startsWith("http://") }

    fun parse(status: Int, body: String): AdminInit {
      val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
      val url = runCatching { root?.get("url")?.jsonPrimitive?.content }.getOrNull()
      val code = runCatching { root?.get("error")?.jsonObject?.get("code")?.jsonPrimitive?.content }.getOrNull()
      val mode = runCatching { root?.get("mode")?.jsonPrimitive?.content }.getOrNull()
      return when {
        status == 200 -> link(url)?.let {
          val expires = runCatching { root?.get("expires_at")?.jsonPrimitive?.content }.getOrNull()
          // Before 5.10 there was no mode: every link was a setup link.
          if (mode == "recover") AdminInit.Recovery(it, expires) else AdminInit.Link(it, expires)
        } ?: throw ApiErrorException(ApiError(ErrorCodes.PARSE_ERROR, "no setup link in the answer", status))
        status == 409 && code == "ADMIN_EXISTS" -> AdminInit.Exists(link(url))
        status == 404 && code == "NOT_SELFHOSTED" -> AdminInit.NotSelfHosted
        status == 404 || status == 405 -> AdminInit.Unsupported
        else -> throw ApiErrorException(ApiError(code ?: ErrorCodes.NETWORK_ERROR, "the backend answered $status", status))
      }
    }
  }
}
