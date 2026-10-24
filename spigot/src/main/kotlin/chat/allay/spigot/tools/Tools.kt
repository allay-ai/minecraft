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
import chat.allay.core.ChatMode
import chat.allay.core.Locales
import chat.allay.spigot.audience

/** What tools keep between calls on Bukkit: the core's, and the world's own stores. */
class Features(
  val plugin: Plugin,
  locales: Locales,
  reminders: Reminders,
  val goals: Goals,
  books: Books,
  val deaths: Deaths,
  data: PlayerData,
  consented: (java.util.UUID) -> Boolean,
  zones: TimeZones,
  settings: PlayerSettings,
  chat: ChatMode,
  serverId: String,
  config: () -> Config,
  val structureRadius: () -> Int,
  val biomeRadius: () -> Int,
  val villagerRadius: () -> Int,
  /** Automation agents, their picking, jobs, and undo. */
  val automation: chat.allay.spigot.automation.Automation,
  platform: Platform,
  expansions: Expansions? = null,
  names: () -> GameNames? = { null },
) : ToolServices(platform, locales, reminders, books, data, consented, zones, settings, chat, serverId, config, automation.agents, expansions, names)

/** Who asked, on Bukkit: the core's [ToolScope] with the Bukkit sender and the world's stores. */
class ToolContext(
  val sender: CommandSender,
  val features: Features,
  buttons: MutableList<Button> = java.util.Collections.synchronizedList(mutableListOf()),
  task: chat.allay.core.tasks.Task? = null,
  others: OtherPlayers = OtherPlayers(),
) : ToolScope(sender.audience, features, buttons, task, others) {
  val plugin: Plugin get() = features.plugin
  val reminders: Reminders get() = features.reminders
  val structureRadius: Int get() = features.structureRadius()
  val player: Player? get() = sender as? Player

  fun requirePlayer(): Player = player ?: throw ToolError("there is no player: the question came from the console")

  /** How the model reads [other]: their name when they agreed here, else this run's “Player N”. */
  fun nameOf(other: Player): String = nameOf(other.uniqueId, other.name)

  /** An online player by this run's label or by name, whom the asker may know about; on the bar or not. */
  fun playerNamed(name: String): Player? {
    val viewer = requirePlayer()
    others.player(name)?.let { id -> plugin.server.getPlayer(id)?.takeIf { it != viewer && chat.allay.spigot.Vanish.visible(viewer, it) } }?.let { return it }
    return Locator.named(viewer, name)
  }
}

/** A game tool on Bukkit: it reads the world through the [ToolContext]. */
typealias Tool = ClientTool<ToolContext>

/** Every tool this plugin offers. */
object Tools {
  fun all(): List<Tool> = PlayerTools.all + LookTools.all + LocationTools.all + LocatorTools.all + WorldTools.all + KnowledgeTools.all + CommandTools.all + InspectTools.all + CareTools.all + BrewingTools.all +
    ActionTools.all + MarkTools.all + DraftTools.all + RedstoneTools.all + InteractTools.all + GoalTools.all + AllayTools.all + OperatorTools.all +
    chat.allay.core.tools.TaskTools.all + chat.allay.spigot.automation.PickTools.all + chat.allay.spigot.automation.Chests.all + chat.allay.spigot.automation.Jobs.all
}
