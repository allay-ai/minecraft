package chat.allay.fabric.tools

import chat.allay.core.*
import chat.allay.core.tools.*
import chat.allay.fabric.FabricPlatform
import chat.allay.fabric.FabricPlayer
import chat.allay.fabric.Vanish
import chat.allay.fabric.automation.Automation

import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer

/** What tools keep between calls on Fabric: the core's, and the world's own stores. */
class Features(
  val fabric: FabricPlatform,
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
  val automation: Automation,
) : ToolServices(fabric, locales, reminders, books, data, consented, zones, settings, chat, serverId, config, automation.agents) {
  val server: MinecraftServer get() = fabric.server
  val scheduler: Scheduler get() = fabric.scheduler
}

/** Who asked, on Fabric: the core's [ToolScope] with the game's player and the world's stores. */
class ToolContext(
  asker: Audience,
  val features: Features,
  buttons: MutableList<Button> = java.util.Collections.synchronizedList(mutableListOf()),
  task: chat.allay.core.tasks.Task? = null,
  others: OtherPlayers = OtherPlayers(),
) : ToolScope(asker, features, buttons, task, others) {
  val server: MinecraftServer get() = features.server
  val scheduler: Scheduler get() = features.scheduler
  val reminders: Reminders get() = features.reminders
  val structureRadius: Int get() = features.structureRadius()

  /** The player who asked, as the game has them now; null for the console. */
  val player: ServerPlayer? get() = (asker as? FabricPlayer)?.player

  fun requirePlayer(): ServerPlayer = player ?: throw ToolError("there is no player: the question came from the console")

  /** How the model reads [other]: their name when they agreed here, else this run's “Player N”. */
  fun nameOf(other: ServerPlayer): String = nameOf(other.uuid, other.gameProfile.name)

  /** An online player by this run's label or by name, whom the asker may know about; on the bar or not. */
  fun playerNamed(name: String): ServerPlayer? {
    val viewer = requirePlayer()
    others.player(name)?.let { id -> server.playerList.getPlayer(id)?.takeIf { it.uuid != viewer.uuid && Vanish.visible(viewer, it) } }?.let { return it }
    return Locator.named(viewer, name)
  }
}

/** A game tool on Fabric: it reads the world through the [ToolContext]. */
typealias Tool = ClientTool<ToolContext>

/** Every tool this mod offers: the same names, arguments, and results as on Bukkit, so the model can't tell. */
object Tools {
  fun all(): List<Tool> = LocatorTools.all + MarkTools.all + InteractTools.all + AllayTools.all + chat.allay.core.tools.TaskTools.all +
    ActionTools.all + chat.allay.fabric.automation.PickTools.all + chat.allay.fabric.automation.Chests.all + chat.allay.fabric.automation.Jobs.all
}
