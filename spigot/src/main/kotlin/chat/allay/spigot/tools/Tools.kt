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
import chat.allay.core.tools.Notes
import chat.allay.core.tools.TimeZones
import chat.allay.spigot.ChatMode
import chat.allay.spigot.Locales

/** What tools keep between calls, built once and shared by every run. Limits are read on use, so `/allay reload` applies them. */
class Features(
  val plugin: Plugin,
  val locales: Locales,
  val reminders: Reminders,
  val notes: Notes,
  val goals: Goals,
  val books: Books,
  val deaths: Deaths,
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
)

/**
 * Who asked. Tools see only this sender: the owner decided the agent may learn about the asker alone, and about
 * other players only what the asker's locator bar shows ([Locator]).
 * [buttons] collects what goes under the answer; the handler shows them once the answer is out.
 */
class ToolContext(val sender: CommandSender, val features: Features, val buttons: MutableList<Button> = java.util.Collections.synchronizedList(mutableListOf())) {
  /** The asker's language, for the summaries under tool lines. */
  val lang: Lang get() = features.locales.of(sender)

  val plugin: Plugin get() = features.plugin
  val reminders: Reminders get() = features.reminders
  val notes: Notes get() = features.notes
  val structureRadius: Int get() = features.structureRadius()
  val player: Player? get() = sender as? Player

  fun requirePlayer(): Player = player ?: throw chat.allay.core.tools.ToolError("there is no player: the question came from the console")
}

/** A game tool on Bukkit: it reads the world through the [ToolContext]. */
typealias Tool = ClientTool<ToolContext>

/** Every tool this plugin offers. */
object Tools {
  fun all(): List<Tool> = PlayerTools.all + LookTools.all + LocationTools.all + LocatorTools.all + WorldTools.all + KnowledgeTools.all + InspectTools.all + CareTools.all + BrewingTools.all +
    NotebookTools.all + ActionTools.all + MarkTools.all + DraftTools.all + RedstoneTools.all + InteractTools.all + GoalTools.all + AllayTools.all + OperatorTools.all
}
