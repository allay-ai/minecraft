package chat.allay.spigot.automation

import chat.allay.core.tasks.Task
import chat.allay.core.tools.ToolError
import chat.allay.spigot.tools.Tool

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.bukkit.block.Block
import org.bukkit.block.Container
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.inventory.BrewEvent
import org.bukkit.event.inventory.FurnaceSmeltEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin

/**
 * The jobs agents leave running in furnaces and brewing stands, and the tools that start them. The game drives the
 * work; these listen for it, refill what was reserved, and stop the agent when someone gets in the way.
 */
object Jobs : Listener {
  lateinit var automation: Automation

  /** A job that works through blocks: the listener asks it about each of its blocks' events. */
  interface BlockJob : Automation.Job {
    val task: Task
    val blocks: Collection<Block>
    fun smelted(block: Block) {}
    fun brewed(block: Block) {}
    /** Every second: does the world still look the way the job left it? */
    fun check() {}
  }

  private val running = mutableListOf<BlockJob>()

  fun add(job: BlockJob) {
    running += job
  }

  fun remove(job: BlockJob) {
    running -= job
  }

  private fun of(block: Block) = running.firstOrNull { job -> block in job.blocks }

  fun busy(block: Block) = of(block) != null

  /** Whether [job] still works; a finished one stays on its agent for collect. */
  fun active(job: Automation.Job) = job in running

  fun start(plugin: Plugin) {
    plugin.server.scheduler.runTaskTimer(plugin, Runnable { running.toList().forEach(BlockJob::check) }, 20L, 20L)
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  fun onSmelt(event: FurnaceSmeltEvent) {
    val job = of(event.block) ?: return
    // The furnace takes the item after the event; the job looks a tick later.
    automation.later { job.smelted(event.block) }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  fun onBrew(event: BrewEvent) {
    val job = of(event.block) ?: return
    automation.later { job.brewed(event.block) }
  }

  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  fun onBreak(event: BlockBreakEvent) {
    val job = of(event.block) ?: return
    automation.interfered(job.task, automation.text(job.task, "automation.reason.broken", "block" to event.block.type.key.key, "x" to event.block.x, "y" to event.block.y, "z" to event.block.z))
  }

  /** Where output goes: `"inventory"`, or chests by coordinates. */
  sealed interface Output {
    data object Inventory : Output
    data class Chests(val blocks: List<Block>) : Output
  }

  fun output(player: Player, value: JsonElement?): Output? = when {
    value == null -> null
    value is JsonPrimitive && value.contentOrNull?.trim()?.lowercase() == "inventory" -> Output.Inventory
    value is JsonArray -> Output.Chests(Blocks.list(player, value, automation.settings().pauseDistance).map(Blocks::root).onEach { block ->
      Blocks.unsuitable(player, block)?.let { throw ToolError("the chest at ${block.x} ${block.y} ${block.z} can't be used: ${it.model}") }
    })
    else -> throw ToolError("to is \"inventory\" or a list of chests by x, y, z")
  }

  /** Puts [items] where [to] says; what doesn't fit goes to the inventory, then at the player's feet. */
  fun deliver(player: Player, items: List<ItemStack>, to: Output) {
    var left = items
    if (to is Output.Chests) {
      for (block in to.blocks) {
        val inventory = (block.state as? Container)?.inventory ?: continue
        left = left.flatMap { inventory.addItem(it).values }
      }
    }
    give(player, left)
  }

  val all: List<Tool> = listOf(Furnaces.tool, Stands.tool, Crafting.tool, AnvilWork.tool)
}
