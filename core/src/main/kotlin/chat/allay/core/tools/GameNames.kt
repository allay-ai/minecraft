package chat.allay.core.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * The names the game shows for its things, by translation key and language: what the platform has on hand (the game's
 * English, every mod's and plugin's own files), and the game's other languages, which a server never ships and the
 * players' clients download. Those come once per game version from Mojang's servers, as the launcher fetches them,
 * into `cache/lang/`. Reads never touch the network.
 */
class GameNames(
  private val folder: File,
  private val gameVersion: String,
  private val log: Logger,
  /** What the platform itself has for a language: the game's and mods' files on the class path. */
  private val local: (locale: String) -> Map<String, String>,
) {
  private val tables = ConcurrentHashMap<String, Map<String, String>>()
  private val fetching = ConcurrentHashMap.newKeySet<String>()
  private val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build()
  private val changes = java.util.concurrent.atomic.AtomicInteger()
  @Volatile private var downloaded: Set<String>? = null

  /** Goes up whenever names already read may have changed: a download, [forget]. A cached catalog builds again then. */
  val revision: Int get() = changes.get()

  /** Every name in [locale]: the downloaded game file under what the platform has, so a mod's own names win. */
  fun table(locale: String): Map<String, String> = tables.getOrPut(code(locale)) {
    val code = code(locale)
    val downloaded = file(code).takeIf { it.isFile }?.let { runCatching { parse(it.readText()) }.getOrNull() }.orEmpty()
    downloaded + runCatching { local(code) }.getOrDefault(emptyMap())
  }

  /** Every language Allay has names in: the downloaded ones and any read so far. The folder is listed once per change. */
  fun known(): Set<String> {
    val files = downloaded ?: folder.resolve("cache/lang/$gameVersion").listFiles { file -> file.name.endsWith(".json") }.orEmpty()
      .map { it.name.removeSuffix(".json") }.toSet().also { downloaded = it }
    return files + tables.keys
  }

  /** The game's own names in [locale] are on disk, or there's no file to fetch: English ships with every server. */
  fun has(locale: String): Boolean = code(locale) == ENGLISH || file(code(locale)).isFile

  /**
   * Downloads the game's [locales] that aren't on disk yet, blocking; run it off the server thread. A language Mojang
   * doesn't have, or a network that won't answer, leaves English to search by.
   */
  fun fetch(locales: Collection<String>) {
    val missing = locales.map(::code).filter { it != ENGLISH && !file(it).isFile && fetching.add(it) }
    if (missing.isEmpty()) return
    try {
      val index = assetIndex()
      val saved = mutableListOf<String>()
      for (locale in missing) {
        val hash = (index["minecraft/lang/$locale.json"] as? JsonObject)?.get("hash")?.jsonPrimitive?.content
        if (hash == null) {
          log.fine("The game has no language $locale")
          continue
        }
        runCatching { save(locale, get("$RESOURCES/${hash.take(2)}/$hash")) }
          .onSuccess { tables.remove(locale); downloaded = null; changes.incrementAndGet(); saved += locale }
          .onFailure { log.warning("Couldn't download the game's names in $locale: ${it.message}") }
      }
      if (saved.isNotEmpty()) log.info("The game's names in ${saved.joinToString()} are downloaded: players find things by them")
    } catch (error: Exception) {
      log.warning("Couldn't download the game's names in ${missing.joinToString()}: ${error.message}; searching by English names")
    } finally {
      fetching.removeAll(missing.toSet())
    }
  }

  /** Drops what's in memory, so the next search reads the platform's files again: a data pack or mod list changed. */
  fun forget() {
    tables.clear()
    downloaded = null
    changes.incrementAndGet()
  }

  private fun assetIndex(): JsonObject {
    val versions = Json.parseToJsonElement(get(MANIFEST)).jsonObject["versions"] as? kotlinx.serialization.json.JsonArray ?: throw IOException("no versions in the manifest")
    val version = versions.map { it.jsonObject }.firstOrNull { it["id"]?.jsonPrimitive?.content == gameVersion }
      ?: throw IOException("Mojang lists no version $gameVersion")
    val details = Json.parseToJsonElement(get(version["url"]!!.jsonPrimitive.content)).jsonObject
    val index = details["assetIndex"]?.jsonObject?.get("url")?.jsonPrimitive?.content ?: throw IOException("no asset index for $gameVersion")
    return Json.parseToJsonElement(get(index)).jsonObject["objects"]?.jsonObject ?: throw IOException("an empty asset index")
  }

  private fun get(url: String): String {
    val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMinutes(1)).header("User-Agent", "Allay").build()
    val response = http.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() != 200) throw IOException("HTTP ${response.statusCode()} from ${URI(url).host}")
    return response.body()
  }

  private fun save(locale: String, text: String) {
    parse(text).ifEmpty { throw IOException("an empty language file") }
    val target = file(locale)
    target.parentFile.mkdirs()
    val temp = File.createTempFile(locale, ".json.part", target.parentFile)
    try {
      temp.writeText(text)
      Files.move(temp.toPath(), target.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
    } finally {
      temp.delete()
    }
  }

  private fun file(locale: String) = folder.resolve("cache/lang/$gameVersion/$locale.json")

  companion object {
    const val ENGLISH = "en_us"
    private const val MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    private const val RESOURCES = "https://resources.download.minecraft.net"

    /** A game language code as the game writes it: `ru_ru`. */
    fun code(locale: String): String = locale.trim().lowercase().replace('-', '_')

    /**
     * Allay's [language] for a player (`ru`, `pt_br`) as the game language whose names they read: their game's own
     * [client] when it's that language (`es_mx` for Spanish), else the language's main one (`ru_ru`, `en_us`).
     */
    fun locale(language: String, client: String?): String {
      val wanted = code(language)
      val game = client?.let(::code)
      if (game != null && (game == wanted || game.substringBefore('_') == wanted)) return game
      if ('_' in wanted) return wanted
      return REGIONS[wanted] ?: "${wanted}_$wanted"
    }

    /** Languages whose main game language isn't `<code>_<code>`. */
    private val REGIONS = mapOf(
      "en" to "en_us", "pt" to "pt_br", "uk" to "uk_ua", "zh" to "zh_cn", "ja" to "ja_jp", "ko" to "ko_kr", "cs" to "cs_cz",
      "sv" to "sv_se", "da" to "da_dk", "el" to "el_gr", "he" to "he_il", "vi" to "vi_vn", "be" to "be_by", "kk" to "kk_kz",
      "ar" to "ar_sa", "fa" to "fa_ir", "et" to "et_ee", "ca" to "ca_es", "sl" to "sl_si", "nb" to "nb_no", "no" to "nb_no",
    )

    /** A language file's strings; anything that isn't a flat object of strings is skipped. */
    fun parse(text: String): Map<String, String> = runCatching {
      Json.parseToJsonElement(text).jsonObject.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.takeIf { it.isString }?.let { key to it.content } }.toMap()
    }.getOrDefault(emptyMap())
  }
}
