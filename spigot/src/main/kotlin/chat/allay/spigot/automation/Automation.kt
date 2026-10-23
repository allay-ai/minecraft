package chat.allay.spigot.automation

import chat.allay.spigot.audience
import chat.allay.spigot.of

import chat.allay.core.*
import chat.allay.core.tasks.Agents
import chat.allay.core.tasks.Kind
import chat.allay.core.tasks.Task
import chat.allay.core.tools.Button
import chat.allay.spigot.player

import kotlinx.coroutines.CoroutineScope
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin

/**
 * Automation agents on Bukkit: the core's [Agents] run their wakes, and this adds what they do in the world. [picking]
 * the blocks a player picks for them, [undo] the last changes to chests and the inventory, and [Jobs] the furnace and
 * brewing jobs the game drives.
 */
class Automation(
  private val plugin: JavaPlugin,
  platform: Platform,
  private val locales: Locales,
  private val config: () -> Config,
  service: () -> Service<*>,
  clicks: () -> Clicks,
  quota: () -> QuotaGate,
  scope: CoroutineScope,
) : Listener {
  /** Something an agent left running in the world: a furnace load, a brew. */
  interface Job : Agents.Job

  val picking: Picking = Picking(plugin, locales, this)
  val undo = Undo()

  private val agents = Agents(platform, locales, config, service, clicks, quota, scope, object : Agents.World {
    override fun stopPicking(player: PlayerAudience, task: Task?) = picking.stop(player.player, task)
    override fun tick(now: Long) = picking.tick(now)
    override fun active(job: Agents.Job) = Jobs.active(job)
  })

  val book get() = agents.book
  val reservations get() = agents.reservations

  fun agent(task: Task): Agents.Agent = agents.agent(task)

  /** The label players read: “Automation agent: chests”, in their language. */
  fun title(player: Player, task: Task) = task.title(locales.of(player).text("automation.title"))

  fun settings() = config().automation

  fun start() {
    agents.start()
    Jobs.start(plugin)
  }

  fun stop() = agents.stop()

  fun require(player: Player, task: Task, kind: Kind) = agents.require(player.audience, task, kind)

  fun allowed(player: Player, kind: Kind): String? = agents.allowed(player.audience, kind)

  fun launch(player: Player, kind: Kind, name: String, brief: String, parent: Task?): Task = agents.launch(player.audience, kind, name, brief, parent)

  fun post(task: Task, message: String) = agents.post(task, message)

  fun reply(player: Player, id: String, label: String, reply: String) = agents.reply(player.audience, id, label, reply)

  fun end(task: Task, reason: String?, byParent: Boolean = false) = agents.end(task, reason, byParent)

  fun interfered(task: Task, reason: String) = agents.interfered(task, reason)

  fun finish(player: Player, task: Task, summary: String) = agents.finish(player.audience, task, summary)

  fun say(player: Player, task: Task, text: String, buttons: List<Button> = emptyList()) = agents.say(player.audience, task, text, buttons)

  fun cancel(player: Player): Boolean = agents.cancel(player.audience)

  @EventHandler fun onQuit(event: PlayerQuitEvent) = agents.quit(event.player.audience)

  fun later(block: () -> Unit) = agents.later(block)

  fun text(task: Task, key: String, vararg values: Pair<String, Any?>): String = agents.text(task, key, *values)
}
