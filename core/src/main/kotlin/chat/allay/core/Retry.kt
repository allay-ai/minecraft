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

  /**
   * Seconds before try number [tries] (1 for the first): 15, 45, 135, at most 300, so a backend that's down for long
   * isn't asked every few seconds; never sooner than the backend asked for ([asked]).
   */
  fun backoff(tries: Int, asked: Long = SECONDS): Long {
    var seconds = SECONDS
    repeat((tries - 1).coerceIn(0, 4)) { seconds *= 3 }
    return maxOf(seconds, asked).coerceAtMost(MAX_SECONDS)
  }

  private const val SECONDS = 15L
  private const val MAX_SECONDS = 300L
}
