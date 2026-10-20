package chat.allay.core

import kotlinx.serialization.Serializable

/** [resetsAt] (ISO-8601) comes with `QUOTA_EXCEEDED`, [retryAfter] (seconds) with `RATE_LIMITED`. */
@Serializable data class ApiError(val code: String, val message: String, val statusCode: Int? = null, val resetsAt: String? = null, val retryAfter: Long? = null)
@Serializable data class ErrorResponse(val error: String, val code: String, val status: String, val timestamp: String)

object ErrorCodes {
  const val EMPTY_ACTION = "EMPTY_ACTION"
  const val ACTION_TOO_LONG = "ACTION_TOO_LONG"
  const val AI_ERROR = "AI_ERROR"
  const val TOO_MANY_COMMANDS = "TOO_MANY_COMMANDS"
  const val EMPTY_QUESTION = "EMPTY_QUESTION"
  const val QUESTION_TOO_LONG = "QUESTION_TOO_LONG"
  const val BAD_REQUEST = "BAD_REQUEST"
  const val METHOD_NOT_SUPPORTED = "METHOD_NOT_SUPPORTED"
  
  const val PARSE_ERROR = "PARSE_ERROR"
  const val INVALID_FORMAT = "INVALID_FORMAT"
  const val AI_REQUEST_FAILED = "AI_REQUEST_FAILED"
  const val REDIS_ERROR = "REDIS_ERROR"
  const val NETWORK_ERROR = "NETWORK_ERROR"
  const val NO_AI_RESPONSE = "NO_AI_RESPONSE"
  const val QUEUE_ERROR = "QUEUE_ERROR"
  const val PROCESSING_ERROR = "PROCESSING_ERROR"

  // Since 5.0: who pays for the model.
  const val LICENSE_REQUIRED = "LICENSE_REQUIRED"
  const val LICENSE_INVALID = "LICENSE_INVALID"
  const val SUBSCRIPTION_INACTIVE = "SUBSCRIPTION_INACTIVE"
  const val QUOTA_EXCEEDED = "QUOTA_EXCEEDED"
  const val RATE_LIMITED = "RATE_LIMITED"
  const val PROVIDER_AUTH_FAILED = "PROVIDER_AUTH_FAILED"
  const val MODEL_NOT_FOUND = "MODEL_NOT_FOUND"
  const val MODEL_NO_TOOLS = "MODEL_NO_TOOLS"
  const val PROVIDER_URL_REJECTED = "PROVIDER_URL_REJECTED"

  // Since 6.0: a self-hosted backend wants its PLUGIN_TOKEN in server.token.
  const val TOKEN_REQUIRED = "TOKEN_REQUIRED"
  const val TOKEN_INVALID = "TOKEN_INVALID"
  const val TOKEN_NOT_SET = "TOKEN_NOT_SET"

  /** Errors `server.token` fixes: operators learn what to set. */
  val TOKEN = setOf(TOKEN_REQUIRED, TOKEN_INVALID, TOKEN_NOT_SET)

  /** Errors a payment fixes: operators get a link to pay. */
  val SUBSCRIPTION = setOf(LICENSE_REQUIRED, LICENSE_INVALID, SUBSCRIPTION_INACTIVE, QUOTA_EXCEEDED)

  /** Errors the `provider.byok` settings cause: operators learn what to fix in `config.yml`. */
  val PROVIDER = setOf(PROVIDER_AUTH_FAILED, MODEL_NOT_FOUND, MODEL_NO_TOOLS, PROVIDER_URL_REJECTED)
}

class ApiErrorException(val apiError: ApiError) : Exception(apiError.message)