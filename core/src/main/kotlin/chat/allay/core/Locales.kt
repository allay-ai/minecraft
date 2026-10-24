package chat.allay.core

import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Every player reads Allay in their own language: `lang/<code>.yml`, the one they chose with `/allay language`, or else
 * picked by the country of their IP address, then by their game's language, then `locales.default`. The console gets
 * the default. The platform reports logins, joins, quits, and a change of the game's language.
 */
class Locales(private val platform: Platform) {
  private val countries = ConcurrentHashMap<UUID, String>()
  /** Each online player's language as of their join or their last change of the game's language, for other threads. */
  private val current = ConcurrentHashMap<UUID, String>()
  val geoIp = GeoIp(File(platform.shared, "geoip"), platform.log)
  private var geoIpTask: AutoCloseable? = null

  @Volatile lateinit var texts: Texts; private set
  @Volatile private lateinit var resolver: LocaleResolver
  @Volatile private var sources: List<String> = emptyList()
  /** The language a player chose themself, from their settings; set once the settings are loaded. */
  @Volatile var choices: (UUID) -> String? = { null }

  /** What `/allay locale` shows: the language, what picked it (`ip`, `client`, or `default`), and what it was picked from. */
  class Choice(val code: String, val source: String, val country: String?, val client: String, val sources: List<String>)

  /** Reads the language files and starts the GeoIP refresh; `/allay reload` runs it again. */
  fun load(config: Config) {
    val folder = File(platform.shared, "lang")
    Texts.BUNDLED.forEach { code ->
      val file = File(folder, "$code.yml")
      if (!file.exists()) platform.resource("lang/$code.yml")?.use { stream -> folder.mkdirs(); file.outputStream().use(stream::copyTo) }
    }
    val languages = buildMap {
      folder.listFiles { file -> file.extension == "yml" }.orEmpty().sortedBy { it.name }.forEach { file ->
        val code = LocaleResolver.normalize(file.nameWithoutExtension)
        val texts = runCatching { Texts.read(file) }.onFailure { platform.log.warning("Can't read lang/${file.name}: ${it.message}") }.getOrNull() ?: return@forEach
        // Keys a release added come from the bundled file until the server's copy has them.
        put(code, bundled(code) + texts)
      }
      Texts.BUNDLED.forEach { code -> if (code !in this) put(code, bundled(code)) }
    }
    val default = LocaleResolver.normalize(config.locales.default)
    if (default !in languages) platform.log.warning("locales.default: there is no lang/$default.yml; using ${Texts.FALLBACK}")
    texts = Texts(languages, default)
    resolver = LocaleResolver(config.locales, languages.keys)
    sources = config.locales.sources
    platform.log.info("Languages: ${languages.keys.sorted().joinToString()}, default ${texts.default}")
    // The files or the rules may have changed under players already online.
    platform.players.forEach { current[it.uuid] = code(it) }

    geoIpTask?.close()
    // Open whenever countries are looked up, also without `ip` in the sources: the age question needs the country.
    if (!config.geoIp.lookups) {
      geoIp.close()
      return
    }
    geoIp.open()
    // Players online during a reload have no lookup from their login.
    platform.players.forEach { player -> geoIp.country(player.address)?.let { countries[player.uuid] = it } }
    geoIpTask = platform.scheduler.every(0L, 20L * 60 * 60 * 12, async = true) {
      if (geoIp.isStale(config.geoIp)) {
        geoIp.refresh(config.geoIp)
        platform.players.forEach { player -> geoIp.country(player.address)?.let { countries[player.uuid] = it } }
      }
    }
  }

  private fun bundled(code: String): Map<String, Any> =
    platform.resource("lang/$code.yml")?.let { stream -> InputStreamReader(stream, Charsets.UTF_8).use(Texts::read) }.orEmpty()

  /**
   * The language of whoever reads a message; the console and command blocks read the default. A player's `{papi:…}`
   * placeholders are their own.
   */
  fun of(sender: Audience?): Lang = texts.lang((sender as? PlayerAudience)?.let { code(it) }).about(platform.placeholders(sender))

  fun code(player: PlayerAudience): String = choice(player.uuid) ?: picked(player.uuid, player.locale)

  /**
   * The game language whose names [player] reads in Allay's answers, like `ru_ru`: their Allay language, which beats
   * their game's, so tools name things as the answer will.
   */
  fun names(player: PlayerAudience): String = chat.allay.core.tools.GameNames.locale(code(player), player.locale)

  /** The languages there are, by code. */
  val codes: Set<String> get() = texts.codes

  /** The player's own choice, while there's still a file for it; null when Allay picks. */
  fun choice(player: UUID): String? = choices(player)?.let(LocaleResolver::normalize)?.takeIf { it in texts.codes }

  /** The language picked for the player when they chose none. */
  private fun picked(player: UUID, client: String): String = resolver.resolve(countries[player], client) ?: texts.default

  /** What the player reads once their choice changed: right away, also on other threads. */
  fun chose(player: PlayerAudience) {
    current[player.uuid] = code(player)
  }

  /** An online player's language without asking the platform, so any thread can read it; null when they're offline. */
  fun known(player: UUID): String? = current[player]

  /** The country of the player's IP address, like `US`, while GeoIP is on; null when unknown. */
  fun country(player: UUID): String? = countries[player]

  fun explain(player: PlayerAudience): Choice {
    val country = countries[player.uuid]
    val (code, source) = choice(player.uuid)?.let { it to "chosen" } ?: resolver.explain(country, player.locale) ?: (texts.default to "default")
    return Choice(code, source, country, player.locale, sources)
  }

  fun stop() {
    geoIpTask?.close()
    geoIp.close()
  }

  /** A login the server let through, before the player is in the world: the country comes from its address. */
  fun login(player: UUID, address: InetAddress?) {
    geoIp.country(address)?.let { countries[player] = it }
  }

  fun joined(player: PlayerAudience) {
    current[player.uuid] = code(player)
  }

  fun quit(player: UUID) {
    countries.remove(player)
    current.remove(player)
  }

  /** The player switched their game's language to [locale]. */
  fun changed(player: UUID, locale: String) {
    current[player] = choice(player) ?: picked(player, locale)
  }
}
