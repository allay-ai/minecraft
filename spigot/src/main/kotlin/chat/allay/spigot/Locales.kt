package chat.allay.spigot

import chat.allay.core.*
import chat.allay.core.tools.*

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerLocaleChangeEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Every player reads the plugin in their own language: `lang/<code>.yml`, the one they chose with `/allay language`,
 * or else picked by the country of their IP address, then by their game's language, then `locales.default`.
 * The console gets the default.
 */
class Locales(private val plugin: JavaPlugin) : Listener {
  private val countries = ConcurrentHashMap<UUID, String>()
  /** Each online player's language as of their join or their last change of the game's language, for other threads. */
  private val current = ConcurrentHashMap<UUID, String>()
  val geoIp = GeoIp(File(plugin.dataFolder, "geoip"), plugin.logger)
  private var geoIpTask: BukkitTask? = null

  @Volatile lateinit var texts: Texts; private set
  @Volatile private lateinit var resolver: LocaleResolver
  @Volatile private var sources: List<String> = emptyList()
  /** The language a player chose themself, from their settings; set once the settings are loaded. */
  @Volatile var choices: (UUID) -> String? = { null }

  /** What `/allay locale` shows: the language, what picked it (`ip`, `client`, or `default`), and what it was picked from. */
  class Choice(val code: String, val source: String, val country: String?, val client: String, val sources: List<String>)

  /** Reads the language files and starts the GeoIP refresh; `/allay reload` runs it again. */
  fun load(config: Config) {
    val folder = File(plugin.dataFolder, "lang")
    Texts.BUNDLED.forEach { code -> if (!File(folder, "$code.yml").exists()) plugin.saveResource("lang/$code.yml", false) }
    val languages = buildMap {
      folder.listFiles { file -> file.extension == "yml" }.orEmpty().sortedBy { it.name }.forEach { file ->
        val code = LocaleResolver.normalize(file.nameWithoutExtension)
        val texts = runCatching { Texts.read(file) }.onFailure { plugin.logger.warning("Can't read lang/${file.name}: ${it.message}") }.getOrNull() ?: return@forEach
        // Keys a release added come from the bundled file until the server's copy has them.
        put(code, bundled(code) + texts)
      }
      Texts.BUNDLED.forEach { code -> if (code !in this) put(code, bundled(code)) }
    }
    val default = LocaleResolver.normalize(config.locales.default)
    if (default !in languages) plugin.logger.warning("locales.default: there is no lang/$default.yml; using ${Texts.FALLBACK}")
    texts = Texts(languages, default)
    resolver = LocaleResolver(config.locales, languages.keys)
    sources = config.locales.sources
    plugin.logger.info("Languages: ${languages.keys.sorted().joinToString()}, default ${texts.default}")
    // The files or the rules may have changed under players already online.
    plugin.server.onlinePlayers.forEach { current[it.uniqueId] = code(it) }

    geoIpTask?.cancel()
    if (!config.geoIp.enabled || "ip" !in config.locales.sources) {
      geoIp.close()
      return
    }
    geoIp.open()
    // Players online during a reload have no lookup from their login.
    plugin.server.onlinePlayers.forEach { player -> geoIp.country(player.address?.address)?.let { countries[player.uniqueId] = it } }
    geoIpTask = plugin.server.scheduler.runTaskTimerAsynchronously(plugin, Runnable {
      if (geoIp.isStale(config.geoIp)) {
        geoIp.refresh(config.geoIp)
        plugin.server.onlinePlayers.forEach { player -> geoIp.country(player.address?.address)?.let { countries[player.uniqueId] = it } }
      }
    }, 0L, 20L * 60 * 60 * 12)
  }

  private fun bundled(code: String): Map<String, Any> =
    plugin.getResource("lang/$code.yml")?.let { stream -> InputStreamReader(stream, Charsets.UTF_8).use(Texts::read) }.orEmpty()

  /**
   * The language of whoever reads a message; the console and command blocks read the default. A player's
   * `{papi:…}` placeholders are their own.
   */
  fun of(sender: CommandSender?): Lang = texts.lang((sender as? Player)?.let { code(it) }).about(Placeholders.about(sender))

  fun code(player: Player): String = choice(player.uniqueId) ?: picked(player.uniqueId, player.locale)

  /** The languages there are, by code. */
  val codes: Set<String> get() = texts.codes

  /** The player's own choice, while there's still a file for it; null when Allay picks. */
  fun choice(player: UUID): String? = choices(player)?.let(LocaleResolver::normalize)?.takeIf { it in texts.codes }

  /** The language picked for the player when they chose none. */
  private fun picked(player: UUID, client: String): String = resolver.resolve(countries[player], client) ?: texts.default

  /** What the player reads once their choice changed: right away, also on other threads. */
  fun chose(player: Player) {
    current[player.uniqueId] = code(player)
  }

  /** An online player's language without asking Bukkit, so any thread can read it; null when they're offline. */
  fun known(player: UUID): String? = current[player]

  fun explain(player: Player): Choice {
    val country = countries[player.uniqueId]
    val (code, source) = choice(player.uniqueId)?.let { it to "chosen" } ?: resolver.explain(country, player.locale) ?: (texts.default to "default")
    return Choice(code, source, country, player.locale, sources)
  }

  fun stop() {
    geoIpTask?.cancel()
    geoIp.close()
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  fun onPreLogin(event: AsyncPlayerPreLoginEvent) {
    if (event.loginResult != AsyncPlayerPreLoginEvent.Result.ALLOWED) return
    geoIp.country(event.address)?.let { countries[event.uniqueId] = it }
  }

  @EventHandler
  fun onQuit(event: PlayerQuitEvent) {
    countries.remove(event.player.uniqueId)
    current.remove(event.player.uniqueId)
  }

  @EventHandler(priority = EventPriority.MONITOR)
  fun onJoin(event: PlayerJoinEvent) {
    current[event.player.uniqueId] = code(event.player)
  }

  @EventHandler(priority = EventPriority.MONITOR)
  fun onLocaleChange(event: PlayerLocaleChangeEvent) {
    current[event.player.uniqueId] = choice(event.player.uniqueId) ?: picked(event.player.uniqueId, event.locale)
  }
}
