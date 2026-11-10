package chat.allay.core

import java.time.ZoneId
import java.time.ZoneOffset

/**
 * What a player reads when a question fails, in their language: `errors.api.<code>`, then `errors.http.<status>`,
 * then the general ones. [zone] is the reader's, for the day a spent quota refills.
 */
object ErrorHandler {
  fun message(lang: Lang, error: Any?, zone: ZoneId = ZoneOffset.UTC): String = when (error) {
    is ApiErrorException -> api(lang, error.apiError, zone)
    is ApiError -> api(lang, error, zone)
    is ErrorResponse -> lang.has("errors.api.${error.code}").let { if (it) lang.text("errors.api.${error.code}") else lang.text("errors.other", "message" to error.error) }
    is Unreachable -> lang.text("errors.unreachable")
    is Exception -> when {
      isTimeout(error) -> lang.text("errors.timeout")
      isNetwork(error) -> lang.text("errors.network")
      else -> lang.text("errors.default")
    }
    else -> lang.text("errors.default")
  }

  private fun api(lang: Lang, error: ApiError, zone: ZoneId): String = when {
    // Without resets_at only a new payment helps; without retry_after the plain 429 text says enough.
    error.code == ErrorCodes.QUOTA_EXCEEDED -> BillingDates.format(lang, error.resetsAt, zone)
      ?.let { lang.text("errors.api.QUOTA_EXCEEDED", "date" to it) } ?: lang.text("errors.quota_spent")
    error.code == ErrorCodes.RATE_LIMITED -> error.retryAfter?.takeIf { it > 0 }
      ?.let { lang.text("errors.api.RATE_LIMITED", "seconds" to it) } ?: lang.text("errors.http.429")
    lang.has("errors.api.${error.code}") -> lang.text("errors.api.${error.code}")
    error.statusCode != null && lang.has("errors.http.${error.statusCode}") -> lang.text("errors.http.${error.statusCode}")
    else -> lang.text("errors.other", "message" to error.message)
  }

  private fun isTimeout(error: Exception): Boolean {
    val message = error.message ?: return false
    return message.contains("timeout", ignoreCase = true) || message.contains("SocketTimeoutException", ignoreCase = true)
  }

  private fun isNetwork(error: Exception): Boolean {
    val message = error.message ?: return false
    return message.contains("ConnectException", ignoreCase = true) || message.contains("UnknownHostException", ignoreCase = true) || message.contains("NetworkException", ignoreCase = true)
  }
}
