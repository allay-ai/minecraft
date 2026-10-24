package chat.allay.spigot

import chat.allay.core.*
import chat.allay.spigot.automation.Automation
import chat.allay.spigot.automation.Jobs
import chat.allay.spigot.menus.LanguageMenu
import chat.allay.spigot.menus.MenuListener
import chat.allay.spigot.menus.SettingsMenu
import chat.allay.spigot.tools.Deaths
import chat.allay.spigot.tools.Drafts
import chat.allay.spigot.tools.Features
import chat.allay.spigot.tools.Goals
import chat.allay.spigot.tools.Marks
import chat.allay.spigot.tools.ToolContext
import chat.allay.spigot.tools.Tools
import chat.allay.spigot.tools.Trails
import chat.allay.spigot.tools.WorldTools

import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

/** Allay's game side on Bukkit: the tools, goals, reminders, deaths, automation, marks, drafts, and the chest windows. */
class SpigotGame(private val plugin: JavaPlugin, private val allay: Allay<ToolContext>) : Allay.Game<ToolContext> {
  private val server get() = plugin.server
  private val locales = allay.locales
  private val goals = Goals(plugin, locales, allay.data) { allay.config.tools.maxGoals }
  private val deaths = Deaths(allay.data)
  private val automation = Automation(plugin, allay.platform, locales, { allay.config }, { allay.service }, { allay.clicks }, { allay.quota }, allay.scope)
  private val menus = MenuListener(plugin)
  private var placeholders: AutoCloseable? = null
  private var plan: AutoCloseable? = null

  private val features = Features(
    plugin = plugin,
    locales = locales,
    reminders = allay.reminders,
    goals = goals,
    books = allay.books,
    deaths = deaths,
    data = allay.data,
    consented = { allay.consented.has(it) },
    zones = allay.zones,
    settings = allay.settings,
    chat = allay.chat,
    serverId = allay.serverId,
    config = { allay.config },
    structureRadius = { allay.config.tools.structureRadius },
    biomeRadius = { allay.config.tools.biomeRadius },
    villagerRadius = { allay.config.tools.villagerRadius },
    automation = automation,
    platform = allay.platform,
    expansions = allay.expansions,
    names = { allay.namesOrNull },
  )

  override fun tools() = Tools.all()

  override fun names(locale: String) = chat.allay.spigot.tools.Catalogs.names(locale)
  override fun context(run: Run) = ToolContext(run.sender, features, run.buttons, run.task, run.others)
  override val buttons = GameClicks(plugin, features)
  override fun effects(config: Config): Effects = BukkitEffects(plugin, config)
  override val software: String get() = server.name
  override val gameVersion: String get() = WorldTools.version(server.bukkitVersion)
  override val loader = "spigot"
  // server-ip is usually empty: the game listens everywhere, and only the owner knows the name players type.
  override val address: String? get() = server.ip.trim().takeIf { it.isNotEmpty() && it != "0.0.0.0" }
  override val port: Int get() = server.port
  override val tasks get() = automation.book

  /** Several may be out at once; one `/allay cancel` hides them all. */
  override val shown = Handler.Shown { audience ->
    val player = audience.player
    val trail = Trails.stop(player)
    val marks = Marks.clear(player)
    val draft = Drafts.clear(player)
    when {
      // Agents say themselves that they stopped.
      automation.cancel(player) -> Handler.Hidden.AGENTS
      draft -> Handler.Hidden.DRAFT
      marks -> Handler.Hidden.MARKS
      trail -> Handler.Hidden.TRAIL
      else -> Handler.Hidden.NOTHING
    }
  }

  override fun loaded(player: UUID, state: PlayerState) = goals.loaded(player, state)

  /** On the server thread, where boss bars live. */
  override fun forget(player: UUID) {
    deaths.forget(player)
    goals.forget(player)
  }

  override fun stopAgents(player: PlayerAudience) = automation.cancel(player.player)

  override fun settings(player: PlayerAudience) {
    val bukkit = player.player
    SettingsMenu(bukkit, locales.of(bukkit), features, allay.memory, allay.privacy, allay.platform.version, allay.languages, SettingsMenu.Admin(
      reload = { allay.reload(player) },
      status = { allay.billing.status(player) },
      // Nothing to pay with a key of your own, a backend without billing, or a subscription that runs.
      pay = { !allay.config.provider.byok && allay.watch?.status?.let { it.billing && it.active } != true },
      link = { allay.billing.link(player) },
    )) { languages(player) }.open()
  }

  override fun languages(player: PlayerAudience): Unit = LanguageMenu(player.player, locales, allay.languages) { settings(player) }.open()

  override fun start() {
    chat.allay.spigot.tools.BrewingRecipes.bind(features)
    val expansions = Expansions(plugin, plugin.logger)
    listOf(goals, deaths, menus, Marks, Drafts, automation, automation.picking, Jobs, Events(allay), expansions, chat.allay.spigot.tools.Catalogs.Changes).forEach { server.pluginManager.registerEvents(it, plugin) }
    if (chat.allay.spigot.tools.Catalogs.paper) server.pluginManager.registerEvents(chat.allay.spigot.paper.PaperCatalog.reloads(chat.allay.spigot.tools.Catalogs::clear), plugin)
    Drafts.start(plugin, locales)
    Jobs.automation = automation
    automation.start()
    goals.start()
    // Allay loads at STARTUP, before PlaceholderAPI and Plan; by the first tick every plugin is on.
    server.scheduler.runTask(plugin, Runnable {
      if (Placeholders.available) placeholders = Placeholders.register(plugin, allay.settings, goals, locales) { allay.handler }
      if (Plan.available) plan = Plan.register(plugin, Plan.Sources(allay.settings, goals, allay.reminders, locales, allay.license, { allay.config }) { allay.watch?.status })
      expansions.scanAll()
    })
  }

  override fun stop() {
    chat.allay.spigot.tools.BrewingRecipes.bind(null)
    chat.allay.spigot.tools.Catalogs.clear()
    placeholders?.close()
    plan?.close()
    automation.stop()
    server.onlinePlayers.forEach { goals.save(it.uniqueId) }
    goals.stop()
    menus.stop()
    Marks.clearAll()
    Drafts.stop()
  }
}
