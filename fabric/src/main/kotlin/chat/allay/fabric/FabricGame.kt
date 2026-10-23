package chat.allay.fabric

import chat.allay.core.*
import chat.allay.fabric.automation.Automation
import chat.allay.fabric.menus.Menus
import chat.allay.fabric.tools.Deaths
import chat.allay.fabric.tools.Drafts
import chat.allay.fabric.tools.Features
import chat.allay.fabric.tools.Goals
import chat.allay.fabric.tools.Marks
import chat.allay.fabric.tools.ToolContext
import chat.allay.fabric.tools.Tools
import chat.allay.fabric.tools.Trails

import net.minecraft.SharedConstants
import java.util.UUID

/** Allay's game side on Fabric: the tools, goals, reminders, deaths, automation, marks, and the windows. */
class FabricGame(private val platform: FabricPlatform, private val allay: Allay<ToolContext>) : Allay.Game<ToolContext> {
  private val server get() = platform.server
  private val locales = allay.locales
  private val goals = Goals(platform.scheduler, locales, allay.data) { allay.config.tools.maxGoals }
  private val deaths = Deaths(allay.data)
  private val automation = Automation(platform, locales, { allay.config }, { allay.service }, { allay.clicks }, { allay.quota }, allay.scope)
  private var placeholders: AutoCloseable? = null
  private var plan: AutoCloseable? = null

  val features = Features(
    fabric = platform,
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
  )

  override fun tools() = Tools.all()
  override fun context(run: Run) = ToolContext(run.audience, features, run.buttons, run.task, run.others)
  override val buttons = GameClicks(features)
  override fun effects(config: Config): Effects = FabricEffects(platform.log, config)
  override val software: String get() = "Fabric"
  override val gameVersion: String get() = SharedConstants.getCurrentVersion().name()
  override val loader = "fabric"
  // A singleplayer world, opened to LAN or not, runs on the player's own game.
  override val side: String get() = if (server.isDedicatedServer) "server" else "integrated"
  override val address: String? get() = server.localIp?.trim()?.takeIf { server.isDedicatedServer && it.isNotEmpty() && it != "0.0.0.0" }
  override val port: Int? get() = server.port.takeIf { server.isDedicatedServer && it > 0 }
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

  override fun settings(player: PlayerAudience): Unit = Menus.settings(player, allay, features) { languages(player) }

  override fun languages(player: PlayerAudience): Unit = Menus.languages(player, allay) { settings(player) }

  override fun start() {
    chat.allay.fabric.menus.Menu.start()
    Marks.start()
    Drafts.start(platform.scheduler, locales)
    deaths.start()
    automation.start()
    goals.start(server)
    Hooks.left += { Trails.stop(it) }
    if (Placeholders.available) placeholders = Placeholders.register(allay.settings, goals, locales) { allay.handler }
    // Plan starts with the server too; by the first tick it's on.
    if (Plan.available) platform.scheduler.later(1) {
      plan = Plan.register(Plan.Sources(allay.settings, goals, allay.reminders, locales, allay.license, { allay.config }) { allay.watch?.status })
    }
  }

  override fun stop() {
    placeholders?.close()
    plan?.close()
    automation.stop()
    server.playerList.players.forEach { goals.save(it.uuid) }
    goals.stop()
    Marks.clearAll()
    Drafts.stop()
    Trails.clearAll()
  }
}
