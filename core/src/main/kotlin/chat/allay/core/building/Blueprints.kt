package chat.allay.core.building

import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Builds from Allay's catalog, downloaded only from allay-ai/schematics at a commit the backend named, checked against
 * the SHA-256 its index gives, and kept in memory while a player previews or builds them, then a while longer
 * ([keepMillis], read on use, so `/allay reload` applies it). Nothing goes on disk, and no other source exists: a server can't bring builds of its own.
 * Never on the server thread: [load] waits for the network.
 */
class Blueprints(private val keepMillis: () -> Long = { 30 * 60_000L }, private val download: (String) -> ByteArray = ::fetch) {
  /** A build as the backend names it, from its catalog. */
  data class Source(val id: String, val url: String, val sha256: String, val bytes: Int)

  class Refused(message: String) : Exception(message)

  private class Kept(val blueprint: Blueprint, @Volatile var used: Long)

  private val kept = ConcurrentHashMap<String, Kept>()

  /** The build [source] names, from memory when it's there, else downloaded and checked. */
  fun load(source: Source): Blueprint {
    // The source is checked every time, kept or not: a build in memory never answers for a link from elsewhere.
    if (!URL.matches(source.url)) throw Refused("builds come only from Allay's catalog")
    if (source.bytes !in 1..MAX_BYTES) throw Refused("the build's file is too big")
    forget()
    kept[source.sha256]?.let { it.used = System.currentTimeMillis(); return it.blueprint }
    val data = download(source.url)
    if (data.size != source.bytes) throw Refused("the build's file isn't the one the catalog names")
    val digest = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    if (!digest.equals(source.sha256, ignoreCase = true)) throw Refused("the build's file isn't the one the catalog names")
    val blueprint = try {
      Schem.read(data)
    } catch (error: Schem.Malformed) {
      throw Refused("the build's file can't be read: ${error.message}")
    }
    kept[source.sha256] = Kept(blueprint, System.currentTimeMillis())
    return blueprint
  }

  /** Keeps a build in use from going: a preview or a build that still runs. */
  fun touch(sha256: String) {
    kept[sha256]?.used = System.currentTimeMillis()
  }

  /** Drops what nobody used for [keepMillis]. */
  fun forget(now: Long = System.currentTimeMillis()) {
    val keep = keepMillis()
    kept.entries.removeIf { now - it.value.used > keep }
  }

  companion object {
    /** A file of Allay's catalog at one commit: nothing else ever downloads. */
    val URL = Regex("^https://raw\\.githubusercontent\\.com/allay-ai/schematics/[0-9a-f]{40}/catalog/[a-z0-9-]+(/[a-z0-9-]+)+/build\\.schem$")

    const val MAX_BYTES = 32 * 1024 * 1024

    /** A client of its own: the backend's credentials never travel to GitHub. */
    private val client by lazy {
      OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).followRedirects(false).build()
    }

    private fun fetch(url: String): ByteArray {
      client.newCall(Request.Builder().url(url).header("User-Agent", "Allay").build()).execute().use { response ->
        if (!response.isSuccessful) throw Refused("the catalog answered ${response.code}")
        val body = response.body
        if (body.contentLength() > MAX_BYTES) throw Refused("the build's file is too big")
        val source = body.source()
        source.request(MAX_BYTES.toLong() + 1)
        if (source.buffer.size > MAX_BYTES) throw Refused("the build's file is too big")
        return source.readByteArray()
      }
    }
  }
}
