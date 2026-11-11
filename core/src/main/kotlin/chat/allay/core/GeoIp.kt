package chat.allay.core

import com.maxmind.db.Reader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Duration
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger
import java.util.zip.GZIPInputStream

/**
 * Country by IP address, from a MaxMind-format database in `plugins/Allay/geoip/`. The free DB-IP
 * country database fits and needs no account; [refresh] downloads a new one once it's older than
 * `refresh_days`. Lookups never touch the network.
 */
class GeoIp(private val folder: File, private val log: Logger) : AutoCloseable {
  private val reader = AtomicReference<Reader?>()
  private val file get() = folder.resolve("country.mmdb")
  private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build()

  /** Opens the database already on disk, if any. */
  fun open() {
    if (file.isFile) swap(runCatching { Reader(file) }.onFailure { log.warning("Can't open ${file.name}: ${it.message}") }.getOrNull())
  }

  fun isStale(settings: Config.GeoIp): Boolean {
    if (!file.isFile) return true
    val age = Duration.between(Instant.ofEpochMilli(file.lastModified()), Instant.now())
    return age > Duration.ofDays(settings.refreshDays.coerceAtLeast(1).toLong())
  }

  /** Downloads the database, blocking. Tries this month's file first, then last month's, since new ones come out a few days in. */
  fun refresh(settings: Config.GeoIp) {
    val now = YearMonth.now(ZoneOffset.UTC)
    val urls = listOf(now, now.minusMonths(1)).map { month ->
      settings.url.replace("{year}", "%04d".format(month.year)).replace("{month}", "%02d".format(month.monthValue))
    }.distinct()

    for (url in urls) {
      try {
        download(url)
        log.info("GeoIP database updated from ${URI(url).host}")
        return
      } catch (e: Exception) {
        log.fine("GeoIP download from $url failed: ${e.message}")
      }
    }
    log.warning("Can't download the GeoIP database; ${if (reader.get() != null) "keeping the old one" else "languages fall back to the game's setting"}")
  }

  private fun download(url: String) {
    folder.mkdirs()
    val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMinutes(2)).header("User-Agent", "Allay").build()
    val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
    if (response.statusCode() != 200) {
      response.body().close()
      throw IOException("HTTP ${response.statusCode()}")
    }

    val temp = File.createTempFile("country", ".mmdb.part", folder)
    try {
      response.body().use { body -> unpack(body).use { input -> temp.outputStream().use { input.copyTo(it) } } }
      Reader(temp).close() // Throws on a file that isn't a database.
      Files.move(temp.toPath(), file.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
    } finally {
      temp.delete()
    }
    swap(Reader(file))
  }

  /** Gunzips when the body starts with the gzip magic number, whatever the URL says. */
  private fun unpack(body: InputStream): InputStream {
    val buffered = body.buffered()
    buffered.mark(2)
    val gzip = buffered.read() == 0x1f && buffered.read() == 0x8b
    buffered.reset()
    return if (gzip) GZIPInputStream(buffered) else buffered
  }

  /** The ISO 3166 code, like `RU`, or null for local addresses and ones the database doesn't know. */
  fun country(address: InetAddress?): String? {
    if (address == null || address.isAnyLocalAddress || address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress) return null
    val db = reader.get() ?: return null
    return runCatching {
      @Suppress("UNCHECKED_CAST")
      val record = db.get(address, Map::class.java) as Map<String, Any?>?
      (record?.get("country") as? Map<*, *>)?.get("iso_code") as? String
    }.onFailure { log.fine("GeoIP lookup failed: ${it.message}") }.getOrNull()
  }

  private fun swap(next: Reader?) {
    reader.getAndSet(next)?.close()
  }

  override fun close() = swap(null)
}
