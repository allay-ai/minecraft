package chat.allay.fabric.automation

import chat.allay.core.*
import chat.allay.core.tasks.Agents
import chat.allay.core.tasks.Kind
import chat.allay.core.tasks.Task
import chat.allay.core.tools.Button
import chat.allay.fabric.Hooks
import chat.allay.fabric.audience
import chat.allay.fabric.of
import chat.allay.fabric.player

import kotlinx.coroutines.CoroutineScope
import net.minecraft.server.level.ServerPlayer

/**
 * Automation agents on Fabric: the core's [Agents] run their wakes, and this adds what they do in the world. [picking]
 * the blocks a player picks for them, [undo] the last changes to chests and the inventory, and [Jobs] the furnace and
 * brewing jobs the game drives.
 */
class Automation(
  private val platform: Platform,
  private val locales: Locales,
  private val config: () -> Config,
  service: () -> Service<*>,
  clicks: () -> Clicks,
  quota: () -> QuotaGate,
  scope: CoroutineScope,
) {
  /** Something an agent left running in the world: a furnace load, a brew. */
  interface Job : Agents.Job

  val picking: Picking = Picking(platform.scheduler, locales, this)
  val undo = Undo()

  /** The core's side of the agents: their wakes, limits, and lifecycle. */
  val agents = Agents(platform, locales, config, service, clicks, quota, scope, object : Agents.World {
    override fun stopPicking(player: PlayerAudience, task: Task?) = picking.stop(player.player, task)
    override fun tick(now: Long) = picking.tick(now)
    override fun active(job: Agents.Job) = Jobs.active(job)
  })

  val book get() = agents.book
  val reservations get() = agents.reservations

  fun agent(task: Task): Agents.Agent = agents.agent(task)

  /** The label players read: “Automation agent: chests”, in their language. */
  fun title(player: ServerPlayer, task: Task) = task.title(locales.of(player).text("automation.title"))

  fun settings() = config().automation

  fun start() {
    agents.start()
    Jobs.start(platform.scheduler)
    Hooks.left += { agents.quit(it.audience) }
  }

  fun stop() = agents.stop()

  fun require(player: ServerPlayer, task: Task, kind: Kind) = agents.require(player.audience, task, kind)

  fun allowed(player: ServerPlayer, kind: Kind): String? = agents.allowed(player.audience, kind)

  fun launch(player: ServerPlayer, kind: Kind, name: String, brief: String, parent: Task?): Task = agents.launch(player.audience, kind, name, brief, parent)

  fun post(task: Task, message: String) = agents.post(task, message)

  fun reply(player: ServerPlayer, id: String, label: String, reply: String) = agents.reply(player.audience, id, label, reply)

  fun end(task: Task, reason: String?, byParent: Boolean = false) = agents.end(task, reason, byParent)

  fun interfered(task: Task, reason: String) = agents.interfered(task, reason)

  fun finish(player: ServerPlayer, task: Task, summary: String) = agents.finish(player.audience, task, summary)

  fun say(player: ServerPlayer, task: Task, text: String, buttons: List<Button> = emptyList()) = agents.say(player.audience, task, text, buttons)

  fun cancel(player: ServerPlayer): Boolean = agents.cancel(player.audience)

  fun later(block: () -> Unit) = agents.later(block)

  fun text(task: Task, key: String, vararg values: Pair<String, Any?>): String = agents.text(task, key, *values)
}
