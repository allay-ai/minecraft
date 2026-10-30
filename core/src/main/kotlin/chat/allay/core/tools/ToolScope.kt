package chat.allay.core.tools

import chat.allay.core.Audience
import chat.allay.core.ChatMode
import chat.allay.core.Clicks
import chat.allay.core.Config
import chat.allay.core.Lang
import chat.allay.core.Locales
import chat.allay.core.OtherPlayers
import chat.allay.core.Platform
import chat.allay.core.PlayerAudience
import chat.allay.core.PlayerData
import chat.allay.core.PlayerSettings
import chat.allay.core.tasks.Agents
import chat.allay.core.tasks.Task
import java.util.UUID

/**
 * What tools keep between calls on every platform, built once and shared by every run. Limits are read on use, so
 * `/allay reload` applies them. A platform extends it with its own stores.
 */
open class ToolServices(
  val platform: Platform,
  val locales: Locales,
  val reminders: Reminders,
  val books: Books,
  /** Each player's state and deaths on the backend. */
  val data: PlayerData,
  /** Whether a player agreed to the notice on this server: only they appear by name to the model. */
  val consented: (UUID) -> Boolean,
  val zones: TimeZones,
  val settings: PlayerSettings,
  val chat: ChatMode,
  /** Names the server's shared thread of public questions on the backend. */
  val serverId: String,
  /** The settings now; `/allay reload` replaces them. */
  val config: () -> Config,
  /** Automation agents, where the platform runs them. */
  val agents: Agents?,
  /** Other plugins' and mods' expansions: their items and recipes join the catalog. */
  val expansions: chat.allay.core.Expansions? = null,
  /** The game's names in every language, once Allay has started. */
  val names: () -> GameNames? = { null },
  /** Buttons outside an answer, such as the way on under a trail that went out; null before Allay has started. */
  val clicks: () -> Clicks? = { null },
)

/**
 * A run as every platform's tools see it, apart from the world. Tools see only the asker: the owner decided the agent
 * may learn about the asker alone, and about other players only what the asker's locator bar shows. [buttons] collects
 * what goes under the answer; the handler shows them once the answer is out.
 */
open class ToolScope(
  val asker: Audience,
  val services: ToolServices,
  val buttons: MutableList<Button> = java.util.Collections.synchronizedList(mutableListOf()),
  /** The automation agent whose wake this is; null in the main chat. */
  val task: Task? = null,
  /** Other players' labels for this run. */
  val others: OtherPlayers = OtherPlayers(),
) {
  /** The asker's language, for the summaries under tool lines. */
  val lang: Lang get() = services.locales.of(asker)

  /** The game language the asker reads things' names in ([Locales.names]); null for the console. */
  val nameLocale: String? get() = (asker as? PlayerAudience)?.let(services.locales::names)

  fun requireAsker(): PlayerAudience = asker as? PlayerAudience ?: throw ToolError("there is no player: the question came from the console")

  /** How the model reads another player: their name when they agreed here, else this run's “Player N”. */
  fun nameOf(other: UUID, name: String): String = others.label(other, name, services.consented(other))

  /**
   * The automation agent of this wake, or a [ToolError] for the main chat, and for an agent that was stopped while its
   * wake still ran: nothing more may move for it.
   */
  fun requireTask(): Task {
    val task = task ?: throw ToolError("only an automation agent may use this; start one with start_task")
    if (task.done) throw ToolError("this automation is over: it was stopped or finished; end the wake now")
    return task
  }

  /** The agents, or a [ToolError] where this platform runs none. */
  fun requireAgents(): Agents = services.agents ?: throw ToolError("automation agents don't run here")

  /** Runs [block] on the server thread from an off-thread tool ([ClientTool.offMain]) and waits for it. */
  fun <T> main(block: () -> T): T {
    val scheduler = services.platform.scheduler
    return if (scheduler.onMain) block() else scheduler.call(10, block)
  }
}
