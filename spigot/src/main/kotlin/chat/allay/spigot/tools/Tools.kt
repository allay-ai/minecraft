package chat.allay.spigot.tools

import chat.allay.core.*
import chat.allay.core.tools.*

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import chat.allay.core.Config
import chat.allay.core.Lang
import chat.allay.core.PlayerSettings
import chat.allay.core.tools.Books
import chat.allay.core.tools.Button
import chat.allay.core.tools.ClientTool
import chat.allay.core.tools.TimeZones
import chat.allay.spigot.ChatMode
import chat.allay.spigot.Locales

/** What tools keep between calls, built once and shared by every run. Limits are read on use, so `/allay reload` applies them. */
class Features(
  val plugin: Plugin,
  val locales: Locales,
  val reminders: Reminders,
  val goals: Goals,
  val books: Books,
  val deaths: Deaths,
  /** Each player's state and deaths on the backend. */
  val data: PlayerData,
  val zones: TimeZones,
  val settings: PlayerSettings,
  val chat: ChatMode,
  /** Names the server's shared thread of public questions on the backend. */
  val serverId: String,
  /** The settings now; `/allay reload` replaces them. */
  val config: () -> Config,
  val structureRadius: () -> Int,
  val biomeRadius: () -> Int,
  val villagerRadius: () -> Int,
  /** Automation agents, their picking, jobs, and undo. */
  val automation: chat.allay.spigot.automation.Automation,
)

/**
 * Who asked. Tools see only this sender: the owner decided the agent may learn about the asker alone, and about
 * other players only what the asker's locator bar shows ([Locator]).
 * [buttons] collects what goes under the answer; the handler shows them once the answer is out.
 */
class ToolContext(
  val sender: CommandSender,
  val features: Features,
  val buttons: MutableList<Button> = java.util.Collections.synchronizedList(mutableListOf()),
  /** The automation agent whose wake this is; null in the main chat. */
  val task: chat.allay.core.tasks.Task? = null,
) {
  /** The asker's language, for the summaries under tool lines. */
  val lang: Lang get() = features.locales.of(sender)

  val plugin: Plugin get() = features.plugin
  val reminders: Reminders get() = features.reminders
  val structureRadius: Int get() = features.structureRadius()
  val player: Player? get() = sender as? Player

  fun requirePlayer(): Player = player ?: throw chat.allay.core.tools.ToolError("there is no player: the question came from the console")

  /**
   * The automation agent of this wake, or a [chat.allay.core.tools.ToolError] for the main chat, and for an agent that was
   * stopped while its wake still ran: nothing more may move for it.
   */
  fun requireTask(): chat.allay.core.tasks.Task {
    val task = task ?: throw chat.allay.core.tools.ToolError("only an automation agent may use this; start one with start_task")
    if (task.done) throw chat.allay.core.tools.ToolError("this automation is over: it was stopped or finished; end the wake now")
    return task
  }

  /** Runs [block] on the server thread from an off-thread tool ([chat.allay.core.tools.ClientTool.offMain]) and waits for it. */
  fun <T> main(block: () -> T): T =
    if (plugin.server.isPrimaryThread) block() else plugin.server.scheduler.callSyncMethod(plugin) { block() }.get(10, java.util.concurrent.TimeUnit.SECONDS)
}

/** A game tool on Bukkit: it reads the world through the [ToolContext]. */
typealias Tool = ClientTool<ToolContext>

/** Every tool this plugin offers. */
object Tools {
  fun all(): List<Tool> = PlayerTools.all + LookTools.all + LocationTools.all + LocatorTools.all + WorldTools.all + KnowledgeTools.all + InspectTools.all + CareTools.all + BrewingTools.all +
    ActionTools.all + MarkTools.all + DraftTools.all + RedstoneTools.all + InteractTools.all + GoalTools.all + AllayTools.all + OperatorTools.all +
    chat.allay.spigot.automation.TaskTools.all + chat.allay.spigot.automation.Chests.all + chat.allay.spigot.automation.Jobs.all
}
