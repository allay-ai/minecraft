package chat.allay.spigot

import chat.allay.core.*
import chat.allay.core.tools.*
import chat.allay.spigot.tools.BrewingRecipes
import chat.allay.spigot.tools.ToolContext
import chat.allay.spigot.automation.Automation
import chat.allay.spigot.automation.Jobs

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.Material
import org.bukkit.entity.Player
import chat.allay.spigot.menus.LanguageMenu
import chat.allay.spigot.menus.MenuListener
import chat.allay.spigot.menus.SettingsMenu
import chat.allay.core.tools.Books
import chat.allay.core.tools.ButtonStore
import chat.allay.spigot.tools.Deaths
import chat.allay.spigot.tools.Drafts
import chat.allay.spigot.tools.Marks
import chat.allay.spigot.tools.Features
import chat.allay.spigot.tools.Goals
import chat.allay.spigot.tools.Reminders
import chat.allay.core.tools.TimeZones
import chat.allay.core.tools.ToolRegistry
import chat.allay.spigot.tools.Tools
import chat.allay.spigot.tools.WorldTools
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/** What the backend takes as a server's name (backend 6.7): letters of any script, digits, spaces, and . _ ' - . */
private val SERVER_NAME = Regex("[\\p{L}\\p{N} ._'-]{2,32}")

/** How long a shutdown waits to send players' changes before sealing the rest into the spool. */
private const val SHUTDOWN_MS = 3_000L

class Zygote : JavaPlugin(), TabCompleter {
  private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())

  private lateinit var client: OkHttpClient
  private lateinit var configuration: Config
  private lateinit var service: Service<ToolContext>
  private lateinit var animator: Animator
  private lateinit var executor: Executor
  private lateinit var handler: Handler
  private lateinit var memory: MemoryCommands
  private lateinit var privacy: PrivacyCommands
  private lateinit var reminders: Reminders
  private lateinit var data: PlayerData
  private lateinit var consents: Consents
  private lateinit var goals: Goals
  private lateinit var languages: Languages
  private lateinit var features: Features
  private lateinit var menus: MenuListener
  private lateinit var clicks: Clicks
  private lateinit var chat: ChatMode
  private lateinit var license: License
  private lateinit var announcements: Announcements
  private lateinit var billing: BillingCommands
  private lateinit var quota: QuotaGate
  private lateinit var automation: Automation
  private var watch: BillingWatch? = null
  private var placeholders: AutoCloseable? = null
  private var plan: AutoCloseable? = null
  private val buttons = ButtonStore()
  private val locales = Locales(this)

  override fun onEnable() {
    saveDefaultConfig()
    // Both outlive /allay reload: the license file, and what operators already heard about the subscription.
    license = License(dataFolder)
    announcements = Announcements(dataFolder, logger)
    // Outlives /allay reload, so reminders already set keep their schedule.
    reminders = Reminders(this, locales) { configuration.tools.maxReminders }
    server.pluginManager.registerEvents(reminders, this)
    // Outlives /allay reload: players' state in memory and what waits to be sent; the backend calls go to the service of the moment.
    data = PlayerData(
      backend = object : PlayerData.Backend {
        override suspend fun state(userId: String) = service.state(userId)
        override suspend fun saveState(userId: String, state: PlayerState) = service.saveState(userId, state)
        override suspend fun death(userId: String, death: Death) = service.death(userId, death)
      },
      scope = scope,
      spool = Spool(File(dataFolder, Spool.FILE), { configuration.server.token.ifEmpty { license.read() } }, logger),
      log = logger,
      agreed = { consents.known(it) || consents.check(it) == Consents.State.GIVEN },
      agreedNow = { consents.known(it) },
      online = { server.getPlayer(it) != null },
    )
    goals = Goals(this, locales, data) { configuration.tools.maxGoals }
    data.loaded = goals::loaded
    val deaths = Deaths(data)
    val settings = PlayerSettings(data)
    locales.choices = { settings.of(it).language }
    languages = Languages(locales, settings)
    chat = ChatMode(this, locales, settings) { handler }
    quota = QuotaGate(this, locales, { configuration }, QuotaBook(dataFolder, logger) { runCatching { java.time.ZoneId.of(configuration.time.zone) }.getOrDefault(java.time.ZoneOffset.UTC) })
    automation = Automation(this, locales, { configuration }, { service }, { clicks }, { quota }, scope)
    features = Features(
      plugin = this,
      locales = locales,
      reminders = reminders,
      goals = goals,
      books = Books(),
      deaths = deaths,
      data = data,
      zones = TimeZones(data) { configuration.time.zone },
      settings = settings,
      chat = chat,
      serverId = PlayerSettings.serverId(dataFolder),
      config = { configuration },
      structureRadius = { configuration.tools.structureRadius },
      biomeRadius = { configuration.tools.biomeRadius },
      villagerRadius = { configuration.tools.villagerRadius },
      automation = automation,
    )
    menus = MenuListener(this)
    listOf(goals, deaths, menus, locales, chat, Marks, Drafts, quota, automation, automation.picking, Jobs, Arrivals()).forEach { server.pluginManager.registerEvents(it, this) }
    Drafts.start(this, locales)
    Jobs.automation = automation
    automation.start()
    goals.start()
    initializeComponents()
    data.start()
    // After a reload of the plugin, players already online get their state too.
    server.onlinePlayers.forEach { data.join(it.uniqueId) }
    // Allay loads at STARTUP, before PlaceholderAPI and Plan; by the first tick every plugin is on.
    server.scheduler.runTask(this, Runnable {
      if (Placeholders.available) placeholders = Placeholders.register(this, settings, goals, locales) { handler }
      if (Plan.available) plan = Plan.register(this, Plan.Sources(settings, goals, reminders, locales, license, { configuration }) { watch?.status })
    })
    listOf("ask", "askx", "askall", "allay").map(::getCommand).forEach {
      it?.setExecutor(this)
      it?.tabCompleter = this
    }
    logger.info("Allay ${description.version} is on")
  }

  private fun initializeComponents() {
    configuration = Config(settingsFile(), description.version)
    locales.load(configuration)
    val credentials = Credentials(configuration, license)
    client = HttpClient(logger, configuration, credentials).client
    if (::handler.isInitialized) handler.cancelAll()
    if (::animator.isInitialized) animator.stopAllAnimations()
    animator = Animator(this, configuration)
    val tools = ToolRegistry(Tools.all(), configuration.tools::enabled)
    val licenses = Billing(client, configuration, license)
    val identity = Identity(licenses, configuration, { server.motd }, logger)
    service = Service(configuration, client, tools, features.zones, features.serverId, host, scope, credentials, automation.book, identity) { features.settings.of(it).full_privacy }
    quota.report()
    billing = BillingCommands(this, configuration, locales, licenses, AdminSetup(client, configuration), credentials, features.zones, scope)
    watch?.stop()
    watch = BillingWatch(this, locales, licenses, credentials, announcements, billing).also { it.start() }
    reportProvider()
    executor = Executor(this, configuration, locales, animator)
    consents = Consents(service, ::notice, { locales.country(it).equals("US", ignoreCase = true) })
    handler = Handler(this, configuration, locales, service, animator, executor, scope, { billing }, { clicks }, quota, automation, consents) { privacy }
    clicks = Clicks(this, configuration, features, buttons, { player, question, public -> handler.ask(player, question, Run.Mode.ASK, public) }) { memory }
    memory = MemoryCommands(this, locales, features, service, scope)
    privacy = PrivacyCommands(this, configuration, locales, service, consents, features.settings, data, features.deaths, features.goals, scope) { automation.cancel(it) }
    registerServer()
  }

  /**
   * Tells the backend this server's name, MOTD, and address for its admin panel (backend 6.7). A name another server
   * holds, or one the backend doesn't take, goes to the log as an error; the panel then shows the server by its id.
   */
  private fun registerServer() {
    val name = configuration.server.name
    if (name.isNotEmpty() && !SERVER_NAME.matches(name)) {
      logger.severe("server.name “$name” isn't a name the backend takes: 2–32 letters, digits, spaces, or . _ ' -. The admin panel shows this server by its id")
      return
    }
    @Suppress("DEPRECATION") val motd = Billing.serverName(server.motd).ifEmpty { null }
    scope.launch {
      try {
        service.register(motd)
        if (name.isNotEmpty()) logger.info("This server is “$name” in the backend's admin panel")
      } catch (error: ApiErrorException) {
        when {
          error.apiError.code == "NAME_TAKEN" ->
            logger.severe("server.name “$name” is taken: another server on this Allay backend has it. Pick another name in config.yml and run /allay reload; until then the admin panel shows this server by its id")
          // No license yet, or a backend before 6.7: the panel learns about the server from its questions.
          error.apiError.statusCode in setOf(401, 402, 403, 404, 405) -> Unit
          else -> logger.warning("Couldn't register this server with the backend: ${error.apiError.code}")
        }
      } catch (error: Exception) {
        logger.warning("Couldn't register this server with the backend: ${error.javaClass.simpleName}")
      }
    }
  }

  /** One line at start and on reload about who pays for the model; the key and the license show masked, if at all. */
  private fun reportProvider() {
    val provider = configuration.provider
    if (configuration.server.token.isNotEmpty()) logger.info("Signing in to the backend with server.token ${Secrets.mask(configuration.server.token)}")
    when {
      provider.byok && provider.apiKey.isEmpty() ->
        logger.warning("provider.mode is byok, but provider.byok.api_key is empty: questions fail until you put your key there and run /allay reload")
      provider.byok -> logger.info("Paying for the model with this server's key ${Secrets.mask(provider.apiKey)} at ${provider.baseUrl}")
      license.unreadable -> logger.warning("Can't read ${License.FILE}: make the user the server runs as its owner; Allay reads it again on the next question")
      license.broken -> logger.warning("${License.FILE} holds no license: fix it, or delete it and run /allay link")
      license.read() != null -> logger.info("Paying for the model with Allay's subscription, license ${license.id}; /allay status shows how it stands")
      provider.legacyKey != null -> logger.info("Paying for the model with the X-OpenRouter-Token key in http.custom_headers (${Secrets.mask(provider.legacyKey)})")
      else -> logger.info("No ${License.FILE} yet: if the backend asks for a subscription, run /allay link")
    }
  }

  /**
   * The notice a player agrees to, in English as the jar ships it, for the hash the backend keeps with the agreement: a
   * server's own wording of `lang/` doesn't count as a new notice.
   */
  private fun notice(): List<String> {
    val english = getResource("lang/en.yml")!!.reader(Charsets.UTF_8).use(Texts::read)
    val keys = listOf("consent.notice", if (configuration.server.token.isEmpty()) "consent.hosted" else "consent.self", "consent.keeps")
    return keys.map { english[it]?.toString().orEmpty() }
  }

  /** Players' state comes from the backend when they join and goes back when they leave, after every other listener. */
  private inner class Arrivals : org.bukkit.event.Listener {
    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    fun onJoin(event: org.bukkit.event.player.PlayerJoinEvent) {
      data.join(event.player.uniqueId)
    }

    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    fun onQuit(event: org.bukkit.event.player.PlayerQuitEvent) {
      data.quit(event.player.uniqueId)
    }
  }

  /** What the backend client needs from Bukkit. */
  private val host = object : Host<ToolContext> {
    override val log: Logger get() = logger
    override val pluginVersion: String get() = description.version
    override val software: String get() = server.name
    override val gameVersion: String get() = WorldTools.version(server.bukkitVersion)
    // server-ip is usually empty: the game listens everywhere, and only the owner knows the name players type.
    override val address: String? get() = server.ip.trim().takeIf { it.isNotEmpty() && it != "0.0.0.0" }
    override val port: Int get() = server.port
    override fun context(run: Run) = ToolContext(run.sender, features, run.buttons, run.task)
    override fun <T> onMain(seconds: Long, block: () -> T): T = server.scheduler.callSyncMethod(this@Zygote) { block() }.get(seconds, TimeUnit.SECONDS)
  }

  /** `config.yml` with the bundled file behind it, read as Bukkit reads it: a file that doesn't parse counts as empty. */
  private fun settingsFile(): YamlConfig {
    val defaults = getResource("config.yml")!!.reader(Charsets.UTF_8).use(YamlConfig::parse)
    val file = File(dataFolder, "config.yml")
    val values = runCatching { if (file.exists()) file.reader(Charsets.UTF_8).use(YamlConfig::parse) else emptyMap() }
      .getOrElse {
        logger.severe("Can't read config.yml: ${it.message}; using the defaults")
        emptyMap()
      }
    return YamlConfig(values, defaults)
  }

  override fun onDisable() {
    placeholders?.close()
    plan?.close()
    if (::handler.isInitialized) handler.cancelAll()
    if (::automation.isInitialized) automation.stop()
    watch?.stop()
    if (::reminders.isInitialized) reminders.cancelAll()
    if (::goals.isInitialized) {
      server.onlinePlayers.forEach { goals.save(it.uniqueId) }
      goals.stop()
    }
    // Before the scope goes: what changed is sent, or sealed into the spool for the next start.
    if (::data.isInitialized) data.stop(SHUTDOWN_MS)
    if (::menus.isInitialized) menus.stop()
    Marks.clearAll()
    Drafts.stop()
    if (::animator.isInitialized) animator.stopAllAnimations()
    locales.stop()
    scope.cancel()
    if (::client.isInitialized) client.dispatcher.executorService.shutdown()
  }

  override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
    val text = args.joinToString(" ")
    when (command.name.lowercase()) {
      "ask" -> handler.ask(sender, text, Run.Mode.ASK, tip = sender is Player && text.isNotBlank() && chat.counts(sender))
      "askall" -> handler.ask(sender, text, Run.Mode.ASK, public = true)
      "askx" -> handler.ask(sender, text, Run.Mode.ASKX)
      "allay" -> allay(sender, args)
      else -> return false
    }
    return true
  }

  /** `/allay`: everything around the questions. */
  private fun allay(sender: CommandSender, args: Array<out String>) {
    val lang = locales.of(sender)
    val word = args.firstOrNull()?.lowercase()
    val player = sender as? Player
    val asker = sender.hasPermission(Permissions.ASK)
    when {
      word == null && player != null -> settingsMenu(player)
      // Buttons under answers run this; a typed id works only if it was shown to this player.
      word == "do" && args.size == 2 && player != null -> clicks.press(player, args[1])
      word == "reload" && sender.hasPermission(Permissions.RELOAD) -> handleReloadCommand(sender)
      word == "language" || word == "lang" -> when {
        player == null -> lang.send(sender, "messages.players_only")
        args.size == 1 -> languageMenu(player)
        args.size == 2 -> if (!languages.choose(player, args[1])) lang.send(sender, "messages.language_unknown", "code" to args[1], "codes" to (languages.codes() + "auto").joinToString(", "))
        else -> usage(sender, lang)
      }
      word == "chat" || word == "hints" -> when {
        player == null -> lang.send(sender, "messages.players_only")
        !sender.hasPermission(Permissions.CHAT) -> lang.send(sender, "messages.no_permission")
        else -> {
          val settings = features.settings.of(player.uniqueId)
          val current = if (word == "chat") settings.chat_mode else settings.hints
          val wanted = when (args.getOrNull(1)?.lowercase()) {
            "on" -> true
            "off" -> false
            null -> !current
            else -> return usage(sender, lang)
          }
          if (word == "chat") chat.set(player, wanted) else chat.hints(player, wanted)
        }
      }
      word == "privacy" -> when {
        args.size == 1 -> privacy.info(sender)
        args.size == 2 && args[1].equals("agree", ignoreCase = true) -> privacy.agree(sender)
        args.size == 2 && args[1].equals("decline", ignoreCase = true) -> privacy.decline(sender)
        args.size == 3 && args[1].equals("age", ignoreCase = true) -> privacy.age(sender, args[2])
        args.size == 2 && args[1].equals("withdraw", ignoreCase = true) -> privacy.withdraw(sender)
        args.size in 2..3 && args[1].equals("full", ignoreCase = true) -> privacy.full(sender, args.getOrNull(2))
        args.size == 2 && args[1].equals("erase", ignoreCase = true) -> privacy.confirmSelf(sender)
        args.size == 2 && args[1].equals("export", ignoreCase = true) -> privacy.exportSelf(sender)
        args.size == 3 && args[1].equals("erase", ignoreCase = true) && args[2] == PrivacyCommands.CONFIRM -> privacy.eraseSelf(sender)
        args.size == 3 && args[1].lowercase() in setOf("export", "erase") ->
          if (!sender.hasPermission(Permissions.ADMIN)) lang.send(sender, "messages.no_permission")
          else if (args[1].equals("export", ignoreCase = true)) privacy.export(sender, args[2]) else privacy.erase(sender, args[2])
        else -> lang.send(sender, "privacy.usage")
      }
      word == "link" || word == "status" || word == "locale" || word == "init" -> when {
        !sender.hasPermission(Permissions.ADMIN) -> lang.send(sender, "messages.no_permission")
        word == "link" && args.size == 1 -> billing.link(sender)
        word == "init" && args.size == 1 -> billing.init(sender)
        word == "status" && args.size == 1 -> billing.status(sender)
        word == "locale" && args.size <= 2 -> billing.locale(sender, args.getOrNull(1))
        else -> usage(sender, lang)
      }
      !asker -> lang.send(sender, "messages.no_permission")
      word == "cancel" && args.size == 1 -> handler.cancel(sender)
      word == "new" && args.size == 1 -> memory.newSession(sender)
      word == "memory" && args.size == 1 -> memory.list(sender)
      word == "memory" -> memory.remember(sender, args.drop(1).joinToString(" "))
      word == "forget" && args.size <= 2 -> args.getOrNull(1)?.let { memory.forget(sender, it) } ?: memory.usage(sender)
      word == "recipe" && player != null ->
        if (args.size < 2) lang.send(sender, "messages.recipe_usage") else clicks.recipe(player, args.drop(1).joinToString("_"))
      word == "enchants" && args.size == 1 && player != null -> clicks.enchants(player)
      word == "villagers" && args.size == 1 && player != null -> clicks.villagers(player, null)
      word == "brewing" && player != null ->
        if (args.size < 2) lang.send(sender, "messages.brewing_usage") else clicks.brewing(player, args[1], args.getOrNull(2))
      word == "anvil" && args.size == 1 && player != null -> clicks.anvil(player, null)
      else -> usage(sender, lang)
    }
  }

  private fun settingsMenu(player: Player): Unit =
    SettingsMenu(player, locales.of(player), features, memory, privacy, description.version, languages) { languageMenu(player) }.open()

  private fun languageMenu(player: Player): Unit = LanguageMenu(player, locales, languages) { settingsMenu(player) }.open()

  private fun usage(sender: CommandSender, lang: Lang) {
    lang.send(sender, "messages.allay_usage")
    if (sender.hasPermission(Permissions.ADMIN)) lang.send(sender, "messages.allay_admin_usage")
  }

  override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
    if (!command.name.equals("allay", ignoreCase = true)) return emptyList()
    val options = when {
      args.size == 1 -> buildList {
        if (sender is Player) add("language")
        if (sender.hasPermission(Permissions.CHAT)) addAll(listOf("chat", "hints"))
        if (sender.hasPermission(Permissions.ASK)) addAll(listOf("cancel", "new", "memory", "forget", "privacy", "recipe", "enchants", "villagers", "brewing", "anvil"))
        if (sender.hasPermission(Permissions.RELOAD)) add("reload")
        if (sender.hasPermission(Permissions.ADMIN)) addAll(listOf("status", "link", "locale", "init"))
      }
      args.size == 2 && args[0].equals("locale", ignoreCase = true) && sender.hasPermission(Permissions.ADMIN) ->
        Vanish.visibleTo(sender, server.onlinePlayers).map { it.name }
      args.size == 2 && args[0].lowercase() in setOf("chat", "hints") -> listOf("on", "off")
      args.size == 2 && args[0].lowercase() in setOf("language", "lang") -> languages.codes() + "auto"
      args.size == 2 && args[0].equals("forget", ignoreCase = true) -> listOf("all")
      args.size == 2 && args[0].equals("privacy", ignoreCase = true) ->
        listOf("agree", "withdraw", "full", "export", "erase")
      args.size == 3 && args[0].equals("privacy", ignoreCase = true) && args[1].equals("full", ignoreCase = true) -> listOf("on", "off")
      args.size == 3 && args[0].equals("privacy", ignoreCase = true) && sender.hasPermission(Permissions.ADMIN) ->
        Vanish.visibleTo(sender, server.onlinePlayers).map { it.name }
      args.size == 2 && args[0].equals("recipe", ignoreCase = true) ->
        return Material.entries.asSequence().filter { it.isItem && !it.isAir && !it.isLegacy }.map { it.key.key }
          .filter { it.startsWith(args[1].lowercase()) }.take(50).toList()
      args.size == 2 && args[0].equals("brewing", ignoreCase = true) ->
        return Brewing.potions(BrewingRecipes.all(server)).filter { it.startsWith(args[1].lowercase()) }.take(50)
      args.size == 3 && args[0].equals("brewing", ignoreCase = true) -> Brewing.FORMS
      else -> return emptyList()
    }
    return options.filter { it.startsWith(args.last(), ignoreCase = true) }
  }

  private fun handleReloadCommand(sender: CommandSender) {
    runCatching {
      initializeComponents()
      locales.of(sender).send(sender, "messages.reloaded")
      logger.info("Settings and languages reloaded by ${sender.name}")
    }.onFailure {
      locales.of(sender).send(sender, "messages.reload_failed", "error" to (it.message ?: it.javaClass.simpleName))
      logger.warning("Reload failed: $it")
    }
  }
}
