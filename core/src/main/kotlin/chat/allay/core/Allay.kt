package chat.allay.core

import chat.allay.core.tasks.TaskBook
import chat.allay.core.tools.Books
import chat.allay.core.tools.ButtonStore
import chat.allay.core.tools.ClientTool
import chat.allay.core.tools.Reminders
import chat.allay.core.tools.TimeZones
import chat.allay.core.tools.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.UUID

/** What the backend takes as a server's name (backend 6.7): letters of any script, digits, spaces, and . _ ' - . */
private val SERVER_NAME = Regex("[\\p{L}\\p{N} ._'-]{2,32}")

/** How long a shutdown waits to send players' changes before sealing the rest into the spool. */
private const val SHUTDOWN_MS = 3_000L

/**
 * Allay on any platform: everything around the questions, built in the same order everywhere. A platform hands in its
 * [Platform] and, once the shared pieces exist, its [Game]: the tools, the buttons that reach into the world, its
 * windows, and what it keeps about players in memory. It then forwards its events and commands here.
 */
class Allay<C>(val platform: Platform, private val build: (Allay<C>) -> Game<C>) {
  /** What a platform adds to Allay: the parts that touch the world. */
  interface Game<C> {
    /** Every client tool this platform has; `config.yml` switches some off. */
    fun tools(): List<ClientTool<C>>

    /** What the tools of [run] see: the asker and the platform's stores. */
    fun context(run: Run): C

    val buttons: Clicks.Game

    /** What `/allay cancel` hides when no question runs. */
    val shown: Handler.Shown

    fun effects(config: Config): Effects

    /** The server software and the game version, like `Paper` and `26.3`, and where it listens. */
    val software: String
    val gameVersion: String

    /** What Allay runs on and where, as [Host.loader] and [Host.side] say. */
    val loader: String
    val side: String get() = "server"
    val address: String? get() = null
    val port: Int? get() = null

    /** The automation agents, so a question tells the backend which of the asker's are running; null without agents. */
    val tasks: TaskBook? get() = null

    /** A player's state arrived from the backend: goals and the like start from it. */
    fun loaded(player: UUID, state: PlayerState) {}

    /** Drops what the platform holds about a player in memory, like their last death and their goals' boss bars. */
    fun forget(player: UUID) {}

    /** Stops the player's automation agents. */
    fun stopAgents(player: PlayerAudience): Boolean = false

    /** `/allay` with no arguments and `/allay language`: the settings and the languages to pick from. */
    fun settings(player: PlayerAudience)
    fun languages(player: PlayerAudience)

    /** After the shared pieces and before the first load; [stop] at shutdown. */
    fun start() {}
    fun stop() {}
  }

  val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
  val buttons = ButtonStore()
  val books = Books()
  val locales = Locales(platform)

  lateinit var game: Game<C>; private set
  lateinit var license: License; private set
  lateinit var announcements: Announcements; private set
  lateinit var consented: Consented; private set
  lateinit var ages: Ages; private set
  lateinit var data: PlayerData; private set
  lateinit var settings: PlayerSettings; private set
  lateinit var zones: TimeZones; private set
  lateinit var languages: Languages; private set
  lateinit var chat: ChatMode; private set
  lateinit var quota: QuotaGate; private set
  lateinit var reminders: Reminders; private set
  /** Names the server's shared thread of public questions on the backend. */
  lateinit var serverId: String; private set

  // Built again by `/allay reload`.
  @Volatile lateinit var config: Config; private set
  lateinit var client: OkHttpClient; private set
  lateinit var service: Service<C>; private set
  lateinit var effects: Effects; private set
  lateinit var animator: Animator; private set
  lateinit var executor: Executor; private set
  lateinit var consents: Consents; private set
  lateinit var handler: Handler; private set
  lateinit var clicks: Clicks; private set
  lateinit var memory: MemoryCommands; private set
  lateinit var privacy: PrivacyCommands; private set
  lateinit var billing: BillingCommands; private set
  val commands = Commands(this)
  var watch: BillingWatch? = null; private set

  private val log get() = platform.log

  fun enable() {
    saveDefaultConfig()
    // Both outlive /allay reload: the license file, and what operators already heard about the subscription.
    license = License(platform.shared)
    announcements = Announcements(platform.shared, log)
    // Both outlive /allay reload. Who agreed here, sealed, so a join looks up only them.
    val secret = { config.server.token.ifEmpty { license.read() } }
    consented = Consented(SealedFile(File(platform.shared, Consented.FILE), secret, "allay consented v1", log), log)
    ages = Ages(SealedFile(File(platform.shared, Ages.FILE), secret, "allay ages v1", log), log)
    // Players' state in memory and what waits to be sent; the backend calls go to the service of the moment.
    data = PlayerData(
      backend = object : PlayerData.Backend {
        override suspend fun state(userId: String) = service.state(userId)
        override suspend fun saveState(userId: String, state: PlayerState) = service.saveState(userId, state)
        override suspend fun death(userId: String, death: chat.allay.core.tools.Death) = service.death(userId, death)
      },
      scope = scope,
      spool = Spool(File(platform.shared, Spool.FILE), secret, log),
      log = log,
      // Only players on the list ever reach the backend from here; anyone else does when they use Allay.
      agreed = { consents.listed(it) && (consents.known(it) || consents.check(it) == Consents.State.GIVEN) },
      agreedNow = { consents.listed(it) && consents.known(it) },
      online = { platform.player(it) != null },
    )
    settings = PlayerSettings(data)
    zones = TimeZones(data) { config.time.zone }
    serverId = PlayerSettings.serverId(platform.folder)
    locales.choices = { settings.of(it).language }
    languages = Languages(locales, settings)
    chat = ChatMode(platform, locales, settings) { handler }
    // Outlives /allay reload, so reminders already set keep their schedule.
    reminders = Reminders(platform, locales, { effects }) { config.tools.maxReminders }
    quota = QuotaGate(platform, locales, { config }, QuotaBook(platform.shared, log) { runCatching { java.time.ZoneId.of(config.time.zone) }.getOrDefault(java.time.ZoneOffset.UTC) })
    game = build(this)
    data.loaded = game::loaded
    game.start()
    load()
    data.start()
    // After a reload of the plugin, players already online get their state too.
    platform.players.forEach { data.join(it.uuid) }
    log.info("Allay ${platform.version} is on")
  }

  /** Reads `config.yml` and the languages, and builds everything that depends on them; `/allay reload` runs it again. */
  fun load() {
    config = Config(settingsFile(), platform.version)
    locales.load(config)
    val credentials = Credentials(config, license)
    client = HttpClient(log, config, credentials).client
    if (::handler.isInitialized) handler.cancelAll()
    if (::animator.isInitialized) animator.stopAll()
    effects = game.effects(config)
    animator = Animator(platform, config, effects)
    val tools = ToolRegistry(game.tools(), config.tools::enabled)
    val licenses = Billing(client, config, license, game.loader, game.side)
    val identity = Identity(licenses, config, { platform.motd }, log)
    service = Service(config, client, tools, zones, serverId, host, scope, credentials, game.tasks, identity) { settings.of(it).full_privacy }
    quota.report()
    billing = BillingCommands(platform, config, locales, licenses, AdminSetup(client, config), credentials, zones, scope)
    watch?.stop()
    watch = BillingWatch(platform, locales, licenses, credentials, announcements, billing).also { it.start() }
    reportProvider()
    executor = Executor(platform, config, locales, animator)
    consents = Consents(service, ::notice, consented, asksAge = { AgeGate.asks(locales.country(it)) })
    handler = Handler(platform, config, locales, service, animator, executor, scope, { billing }, { clicks }, quota, consents, { privacy }, game.shown)
    clicks = Clicks(platform, config, locales, books, chat, buttons, game.buttons, { player, question, public -> handler.ask(player, question, Run.Mode.ASK, public) }) { memory }
    memory = MemoryCommands(platform, locales, service, scope, buttons)
    privacy = PrivacyCommands(platform, config, locales, service, consents, settings, data, game::forget, ages, scope) { game.stopAgents(it) }
    registerServer()
  }

  /** `/allay reload`, and the button in the settings window. */
  fun reload(sender: Audience) {
    runCatching {
      load()
      locales.of(sender).send(sender, "messages.reloaded")
      log.info("Settings and languages reloaded by ${sender.name}")
    }.onFailure {
      locales.of(sender).send(sender, "messages.reload_failed", "error" to (it.message ?: it.javaClass.simpleName))
      log.warning("Reload failed: $it")
    }
  }

  fun disable() {
    if (::handler.isInitialized) handler.cancelAll()
    if (::game.isInitialized) game.stop()
    if (::reminders.isInitialized) reminders.cancelAll()
    watch?.stop()
    // Before the scope goes: what changed is sent, or sealed into the spool for the next start.
    if (::data.isInitialized) data.stop(SHUTDOWN_MS)
    if (::animator.isInitialized) animator.stopAll()
    locales.stop()
    scope.cancel()
    if (::client.isInitialized) client.dispatcher.executorService.shutdown()
  }

  /** Players' state comes from the backend when they join and goes back when they leave, after every other listener. */
  fun joined(player: PlayerAudience) {
    data.join(player.uuid)
  }

  fun quit(player: UUID) {
    reminders.quit(player)
    data.quit(player)
  }

  /**
   * Tells the backend this server's name, MOTD, and address for its admin panel (backend 6.7). A name another server
   * holds, or one the backend doesn't take, goes to the log as an error; the panel then shows the server by its id.
   */
  private fun registerServer() {
    val name = config.server.name
    if (name.isNotEmpty() && !SERVER_NAME.matches(name)) {
      log.severe("server.name “$name” isn't a name the backend takes: 2–32 letters, digits, spaces, or . _ ' -. The admin panel shows this server by its id")
      return
    }
    val motd = Billing.motd(platform.motd).ifEmpty { null }
    scope.launch {
      try {
        service.register(motd)
        if (name.isNotEmpty()) log.info("This server is “$name” in the backend's admin panel")
      } catch (error: ApiErrorException) {
        when {
          error.apiError.code == "NAME_TAKEN" ->
            log.severe("server.name “$name” is taken: another server on this Allay backend has it. Pick another name in config.yml and run /allay reload; until then the admin panel shows this server by its id")
          // No license yet, or a backend before 6.7: the panel learns about the server from its questions.
          error.apiError.statusCode in setOf(401, 402, 403, 404, 405) -> Unit
          else -> log.warning("Couldn't register this server with the backend: ${error.apiError.code}")
        }
      } catch (error: kotlinx.coroutines.CancellationException) {
        // Allay stopped before the backend answered: nothing went wrong.
        throw error
      } catch (error: Exception) {
        log.warning("Couldn't register this server with the backend: ${error.javaClass.simpleName}")
      }
    }
  }

  /** One line at start and on reload about who pays for the model; the key and the license show masked, if at all. */
  private fun reportProvider() {
    val provider = config.provider
    // allay.chat answers only players who agreed, at an age the law allows: these switches are for a backend of your own.
    if (config.server.token.isEmpty() && !config.privacy.consent) log.warning("privacy.consent: false works only with a backend of your own (server.token); allay.chat's players still get the notice")
    if (config.server.token.isEmpty() && !config.geoIp.enabled) log.warning("geoip.enabled: false works only with a backend of your own (server.token); for allay.chat the country still sets the age a player must be to agree")
    if (config.server.token.isNotEmpty()) log.info("Signing in to the backend with server.token ${Secrets.mask(config.server.token)}")
    when {
      provider.byok && provider.apiKey.isEmpty() ->
        log.warning("provider.mode is byok, but provider.byok.api_key is empty: questions fail until you put your key there and run /allay reload")
      provider.byok -> log.info("Paying for the model with this server's key ${Secrets.mask(provider.apiKey)} at ${provider.baseUrl}")
      license.unreadable -> log.warning("Can't read ${License.FILE}: make the user the server runs as its owner; Allay reads it again on the next question")
      license.broken -> log.warning("${License.FILE} holds no license: fix it, or delete it and run /allay link")
      license.read() != null -> log.info("Paying for the model with Allay's subscription, license ${license.id}; /allay status shows how it stands")
      provider.legacyKey != null -> log.info("Paying for the model with the X-OpenRouter-Token key in http.custom_headers (${Secrets.mask(provider.legacyKey)})")
      else -> log.info("No ${License.FILE} yet: if the backend asks for a subscription, run /allay link")
    }
  }

  /**
   * The notice a player agrees to, in English as the jar ships it, for the hash the backend keeps with the agreement: a
   * server's own wording of `lang/` doesn't count as a new notice.
   */
  private fun notice(): List<String> {
    val english = platform.resource("lang/en.yml")!!.reader(Charsets.UTF_8).use(Texts::read)
    val keys = listOf("consent.notice", if (config.server.token.isEmpty()) "consent.hosted" else "consent.self", "consent.keeps")
    return keys.map { english[it]?.toString().orEmpty() }
  }

  /** What the backend client needs from the platform. */
  private val host = object : Host<C> {
    override val log get() = platform.log
    override val pluginVersion get() = platform.version
    override val software get() = game.software
    override val gameVersion get() = game.gameVersion
    override val loader get() = game.loader
    override val side get() = game.side
    override val address get() = game.address
    override val port get() = game.port
    override fun context(run: Run) = game.context(run)
    override fun <T> onMain(seconds: Long, block: () -> T): T = platform.scheduler.call(seconds, block)
  }

  /** The bundled `config.yml` in Allay's folder, unless the server has one. */
  private fun saveDefaultConfig() {
    val file = File(platform.shared, "config.yml")
    if (file.exists()) return
    platform.shared.mkdirs()
    platform.resource("config.yml")?.use { stream -> file.outputStream().use(stream::copyTo) }
  }

  /** `config.yml` with the bundled file behind it, read as Bukkit reads it: a file that doesn't parse counts as empty. */
  private fun settingsFile(): YamlConfig {
    val defaults = platform.resource("config.yml")!!.reader(Charsets.UTF_8).use(YamlConfig::parse)
    val file = File(platform.shared, "config.yml")
    val values = runCatching { if (file.exists()) file.reader(Charsets.UTF_8).use(YamlConfig::parse) else emptyMap() }
      .getOrElse {
        log.severe("Can't read config.yml: ${it.message}; using the defaults")
        emptyMap()
      }
    return YamlConfig(values, defaults)
  }
}
