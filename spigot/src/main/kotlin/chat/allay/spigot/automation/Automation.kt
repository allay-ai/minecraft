package chat.allay.spigot.automation

import chat.allay.spigot.audience
import chat.allay.spigot.of

import chat.allay.core.*
import chat.allay.core.tasks.Agents
import chat.allay.core.tasks.Kind
import chat.allay.core.tasks.Task
import chat.allay.core.tasks.Need
import chat.allay.core.tools.Button
import chat.allay.core.tools.Catalog
import chat.allay.spigot.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonObject
import org.bukkit.Material
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

  /** What the server has, as the model names it; the game sets it once its catalog can be built. */
  @Volatile var catalog: () -> Catalog? = { null }

  /** The core's side of the agents: their wakes, limits, and lifecycle. */
  val agents = Agents(platform, locales, config, service, clicks, quota, scope, object : Agents.World {
    override fun stopPicking(player: PlayerAudience, task: Task?) = picking.stop(player.player, task)
    override fun tick(now: Long) = picking.tick(now)
    override fun active(job: Agents.Job) = Jobs.active(job)
    override fun snapshot(job: Agents.Job) = (job as? Jobs.Lasting)?.snapshot()
    override fun restore(task: Task, player: PlayerAudience, job: JsonObject): Agents.Job? = Jobs.restore(task, player.player, job)
    override fun detach(job: Agents.Job) = Jobs.detach(job)
    override fun describe(job: Agents.Job) = (job as? Jobs.Lasting)?.describe()

    override fun count(task: Task, player: PlayerAudience, needs: List<Need>): Map<String, Int> {
      val sources = Sources.around(player.player, settings().radius, task.scope)
      return needs.associate { need -> need.item to sources.count { Crafting.key(it) == need.item } }
    }

    override fun resolve(item: String): String? {
      val catalog = catalog() ?: return Material.matchMaterial(item)?.takeIf { it.isItem }?.key?.key
      return catalog.resolve(item, null, setOf(Catalog.Kind.ITEM))?.id
    }

    override fun itemName(player: PlayerAudience, item: String) = catalog()?.label(item, locales.names(player)) ?: item

    override fun picked(task: Task, player: PlayerAudience) = picking.picked(player.player, task).map(Blocks::place)
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
