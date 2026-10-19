package chat.allay.core

/**
 * A request the backend never started may go again: it was restarting, busy, or rate-limited. A failure mid-answer
 * never repeats, since tools may have acted already.
 */
object Retry {
  /** Tries after the first before the error reaches the player. */
  const val TIMES = 3

  /** Seconds until the next try, or null when [failure] isn't safe to repeat. */
  fun after(failure: Throwable?): Long? {
    val error = (failure as? ApiErrorException)?.apiError
    return when {
      error?.code == "RATE_LIMITED" -> (error.retryAfter ?: SECONDS).coerceIn(5L, 300L)
      error?.code == "QUEUE_ERROR" -> SECONDS
      // A proxy in front of a backend that restarts answers 502–504 without a body of ours.
      error?.code == "HTTP_ERROR" && error.statusCode in 502..504 -> SECONDS
      generateSequence(failure) { it.cause }.any { it is java.net.ConnectException || it is java.net.UnknownHostException } -> SECONDS
      else -> null
    }
  }

  private const val SECONDS = 15L
}
