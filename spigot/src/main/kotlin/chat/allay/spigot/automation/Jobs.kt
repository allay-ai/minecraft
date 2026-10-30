package chat.allay.spigot.automation

import chat.allay.core.PlayerSettings
import chat.allay.core.tasks.Kind
import chat.allay.core.tasks.Task
import chat.allay.core.tools.ToolError
import chat.allay.spigot.tools.Tool

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.bukkit.Tag
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

  /** Whether a job of another task than [task] works [block]. */
  fun busy(block: Block, task: Task) = of(block)?.let { it.task.id != task.id } == true

  /**
   * Whether [block]'s chunk is loaded. A job's furnaces and stands stand still in an unloaded chunk, as in the game, so
   * its checks skip them there instead of loading the chunk, and the job goes on once someone comes back.
   */
  fun loaded(block: Block) = block.world.isChunkLoaded(block.x shr 4, block.z shr 4)

  /** Whether [job] still works; a finished one stays on its agent for collect. */
  fun active(job: chat.allay.core.tasks.Agents.Job) = job is BlockJob && job in running

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

  /** Where output goes: `"inventory"`, `"auto"` (the chests around that hold the same, else the inventory), or chests by coordinates. */
  sealed interface Output {
    data object Inventory : Output
    data object Auto : Output
    data class Chests(val blocks: List<Block>) : Output
  }

  fun output(player: Player, value: JsonElement?): Output? = when {
    value == null -> null
    value is JsonPrimitive && value.contentOrNull?.trim()?.lowercase() == "inventory" -> Output.Inventory
    value is JsonPrimitive && value.contentOrNull?.trim()?.lowercase() == "auto" -> Output.Auto
    value is JsonArray -> Output.Chests(Blocks.list(player, value, automation.settings().pauseDistance).map(Blocks::root).onEach { block ->
      // Output only goes in, so a sorter's input chest is fine.
      Blocks.unsuitable(player, block, adding = true)?.let { throw ToolError("the chest at ${block.x} ${block.y} ${block.z} can't be used: ${it.model}") }
    })
    else -> throw ToolError("to is \"inventory\", \"auto\", or a list of chests by x, y, z")
  }

  /**
   * Where [kind]'s output goes for this call: [value] when given, else what the player chose before. With [remember]
   * the choice is kept in their settings for later tasks of the kind.
   */
  fun target(player: Player, value: JsonElement?, kind: Kind, settings: PlayerSettings, remember: Boolean): Output? {
    val given = output(player, value)
    if (given != null && remember) settings.update(player.uniqueId) { it.copy(outputs = it.outputs + (kind.wire to encode(given))) }
    return given ?: settings.of(player.uniqueId).outputs[kind.wire]?.let { runCatching { decode(player, it) }.getOrNull() }
  }

  /** Where [to] puts things, for the model. */
  fun describe(to: Output) = when (to) {
    Output.Inventory -> "the inventory"
    Output.Auto -> "the chests that hold the same, the rest to the inventory"
    is Output.Chests -> to.blocks.joinToString("; ") { "the chest at ${it.x} ${it.y} ${it.z}" }
  }

  /** Items as the model reads them in a report: “12 × glass, 1 × bucket”. */
  fun counts(items: List<ItemStack>) = items.groupBy { it.type.key.key }.entries.joinToString(", ") { (id, stacks) -> "${stacks.sumOf { it.amount }} × $id" }

  /** The output the player chose for [kind], as a tool result shows it, or null. */
  fun saved(player: Player, kind: Kind, settings: PlayerSettings): String? = settings.of(player.uniqueId).outputs[kind.wire]

  private fun encode(output: Output): String = when (output) {
    Output.Inventory -> "inventory"
    Output.Auto -> "auto"
    is Output.Chests -> "chests:" + output.blocks.joinToString(";") { "${it.world.name}:${it.x},${it.y},${it.z}" }
  }

  /** A saved output, or null when its chests are in another world now, gone, or out of reach. */
  private fun decode(player: Player, text: String): Output? = when {
    text == "inventory" -> Output.Inventory
    text == "auto" -> Output.Auto
    text.startsWith("chests:") -> {
      val blocks = text.removePrefix("chests:").split(';').mapNotNull { spot ->
        val (world, place) = spot.split(':', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
        if (world != player.world.name) return@mapNotNull null
        val (x, y, z) = place.split(',').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 3 } ?: return@mapNotNull null
        player.world.getBlockAt(x, y, z).takeIf { block ->
          block.location.add(0.5, 0.5, 0.5).distance(player.location) <= automation.settings().pauseDistance && Blocks.unsuitable(player, block, adding = true) == null
        }
      }
      if (blocks.isEmpty()) null else Output.Chests(blocks)
    }
    else -> null
  }

  /**
   * Puts [items] where [to] says; what doesn't fit goes to the inventory, then at the player's feet. Chests named at the
   * start are asked again, since a job delivers long after it: one that turned unfit (someone's claim, open, gone,
   * another world, farther than `automation.pause_distance`) is skipped. While the player is dead or gone, what's left
   * drops at [at] instead of into an inventory that loses it.
   */
  fun deliver(player: Player, items: List<ItemStack>, to: Output, at: Block? = null) {
    var left = items
    val settings = automation.settings()
    val chests = when (to) {
      is Output.Chests -> to.blocks.filter { Sources.fits(player, it, settings.pauseDistance, adding = true) }
      // The chests around that already hold each item, nearest first, as a player would put it away.
      Output.Auto -> if (!present(player)) emptyList() else Blocks.near(player.location, settings.radius) { Blocks.storage(it.type) }.map(Blocks::root).distinct()
        .filter { Blocks.unsuitable(player, it, adding = true) == null }
      Output.Inventory -> emptyList()
    }
    for (block in chests) {
      val inventory = (block.state as? Container)?.inventory ?: continue
      val box = Blocks.box(block)
      left = left.flatMap { item ->
        val holds = to !is Output.Auto || inventory.contents.any { it != null && it.isSimilar(item) }
        if (!holds || (box && Tag.SHULKER_BOXES.isTagged(item.type))) listOf(item) else inventory.addItem(item).values.toList()
      }
    }
    give(player, left, at = at)
  }

  val all: List<Tool> = listOf(Furnaces.tool, Stands.tool, Crafting.tool, AnvilWork.tool, GrindstoneWork.tool)
}
