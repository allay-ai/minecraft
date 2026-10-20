package chat.allay.core

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit.SECONDS
import java.util.logging.Logger

/** The client every backend call goes through; [credentials] adds who pays to each request (never to the log). */
class HttpClient(private val log: Logger, private val config: Config, private val credentials: Credentials? = null) {
  val client = run {
    val http = config.http

    val builder = OkHttpClient.Builder()
      .callTimeout(if (http.callTimeout == 0L) 0 else http.callTimeout, SECONDS)
      .connectTimeout(http.connectTimeout, SECONDS)
      .readTimeout(http.readTimeout, SECONDS)
      .writeTimeout(http.writeTimeout, SECONDS)
      .followRedirects(http.followRedirects)
      .followSslRedirects(http.followRedirects)
      .retryOnConnectionFailure(http.retryOnConnectionFailure)
      .connectionPool(ConnectionPool(http.maxIdleConnections, http.keepAliveDuration, SECONDS))

    if (http.compressionEnabled) {
      builder.addInterceptor { chain ->
        val originalRequest = chain.request()
        val compressedRequest = originalRequest.newBuilder().header("Accept-Encoding", "gzip, deflate").build()
        chain.proceed(compressedRequest)
      }
    }

    builder.addInterceptor { chain ->
      val originalRequest = chain.request()
      val requestBuilder = originalRequest.newBuilder().header("User-Agent", http.userAgent)
      http.customHeaders.forEach { (key, value) -> if (value?.isNotBlank() == true) requestBuilder.header(key, value) }
      chain.proceed(requestBuilder.build())
    }

    credentials?.let { builder.addNetworkInterceptor(it.interceptor()) }

    if (http.logging) {
      builder.addInterceptor { chain ->
        val request = chain.request()
        // A player's id in the path is personal data, so the log leaves it out.
        val url = loggable(request.url.toString())
        log.info("🔄 Requesting HTTP/${request.method} $url")
        val response = chain.proceed(request)
        log.info("✅ Response from HTTP/${response.code} $url")
        response
      }
    }

    val dispatcher = Dispatcher()
    dispatcher.maxRequestsPerHost = http.maxRequestsPerHost
    builder.dispatcher(dispatcher)
    builder.build()
  }

  init {
    log.info("✅ HttpClient initialized")
  }

  companion object {
    private val USER = Regex("/users/[^/?#]+")

    /** A URL as the log may show it: the player in `/users/<id>` becomes `…`. */
    fun loggable(url: String) = url.replace(USER, "/users/…")
  }
}