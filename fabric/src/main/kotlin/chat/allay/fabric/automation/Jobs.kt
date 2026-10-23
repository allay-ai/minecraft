package chat.allay.fabric.automation

import chat.allay.core.PlayerSettings
import chat.allay.core.Scheduler
import chat.allay.core.tasks.Kind
import chat.allay.core.tasks.Task
import chat.allay.core.tools.ToolError
import chat.allay.fabric.Hooks
import chat.allay.fabric.tools.BlockAt
import chat.allay.fabric.tools.Tool
import chat.allay.fabric.tools.Worlds
import chat.allay.fabric.tools.at

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.ItemTags
import net.minecraft.world.item.ItemStack

/**
 * The jobs agents leave running in furnaces and brewing stands, and the tools that start them. The game drives the
 * work; these listen for it (the mod's `SmeltMixin` and `BrewMixin`), refill what was reserved, and stop the agent when
 * someone gets in the way.
 */
object Jobs {
  lateinit var automation: Automation

  /** A job that works through blocks: the listener asks it about each of its blocks' events. */
  interface BlockJob : Automation.Job {
    val task: Task
    val blocks: Collection<BlockAt>
    fun smelted(block: BlockAt) {}
    fun brewed(block: BlockAt) {}
    /** Every second: does the world still look the way the job left it? */
    fun check() {}
  }

  private val running = mutableListOf<BlockJob>()

  /** Each running job's blocks as they were when it started, to name one a player broke. */
  private val kinds = HashMap<BlockAt, String>()

  fun add(job: BlockJob) {
    running += job
    job.blocks.forEach { kinds[it] = it.key }
  }

  fun remove(job: BlockJob) {
    running -= job
    job.blocks.forEach { block -> if (of(block) == null) kinds -= block }
  }

  private fun of(block: BlockAt) = running.firstOrNull { job -> block in job.blocks }

  fun busy(block: BlockAt) = of(block) != null

  /** Whether [job] still works; a finished one stays on its agent for collect. */
  fun active(job: chat.allay.core.tasks.Agents.Job) = job is BlockJob && job in running

  fun start(scheduler: Scheduler) {
    scheduler.every(20L, 20L) { running.toList().forEach(BlockJob::check) }
    Hooks.brokeBlock += { _, level, pos ->
      val block = level.at(pos)
      of(block)?.let { job ->
        automation.interfered(job.task, automation.text(job.task, "automation.reason.broken", "block" to (kinds[block] ?: block.key), "x" to block.x, "y" to block.y, "z" to block.z))
      }
    }
  }

  /** A furnace at [pos] finished an item. The job looks a tick later, as on Bukkit, once the furnace is done with it. */
  fun smelted(level: ServerLevel, pos: BlockPos) {
    if (running.isEmpty()) return
    val block = level.at(pos)
    val job = of(block) ?: return
    automation.later { job.smelted(block) }
  }

  /** A brewing stand at [pos] finished a step. */
  fun brewed(level: ServerLevel, pos: BlockPos) {
    if (running.isEmpty()) return
    val block = level.at(pos)
    val job = of(block) ?: return
    automation.later { job.brewed(block) }
  }

  /** Where output goes: `"inventory"`, `"auto"` (the chests around that hold the same, else the inventory), or chests by coordinates. */
  sealed interface Output {
    data object Inventory : Output
    data object Auto : Output
    data class Chests(val blocks: List<BlockAt>) : Output
  }

  fun output(player: ServerPlayer, value: JsonElement?): Output? = when {
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
  fun target(player: ServerPlayer, value: JsonElement?, kind: Kind, settings: PlayerSettings, remember: Boolean): Output? {
    val given = output(player, value)
    if (given != null && remember) settings.update(player.uuid) { it.copy(outputs = it.outputs + (kind.wire to encode(given))) }
    return given ?: settings.of(player.uuid).outputs[kind.wire]?.let { runCatching { decode(player, it) }.getOrNull() }
  }

  /** Where [to] puts things, for the model. */
  fun describe(to: Output) = when (to) {
    Output.Inventory -> "the inventory"
    Output.Auto -> "the chests that hold the same, the rest to the inventory"
    is Output.Chests -> to.blocks.joinToString("; ") { "the chest at ${it.x} ${it.y} ${it.z}" }
  }

  /** Items as the model reads them in a report: “12 × glass, 1 × bucket”. */
  fun counts(items: List<ItemStack>) = items.groupBy(Slots::id).entries.joinToString(", ") { (id, stacks) -> "${stacks.sumOf { it.count }} × $id" }

  /** The output the player chose for [kind], as a tool result shows it, or null. */
  fun saved(player: ServerPlayer, kind: Kind, settings: PlayerSettings): String? = settings.of(player.uuid).outputs[kind.wire]

  private fun encode(output: Output): String = when (output) {
    Output.Inventory -> "inventory"
    Output.Auto -> "auto"
    is Output.Chests -> "chests:" + output.blocks.joinToString(";") { "${Worlds.name(it.level)}:${it.x},${it.y},${it.z}" }
  }

  /** A saved output, or null when its chests are in another world now, gone, or out of reach. */
  private fun decode(player: ServerPlayer, text: String): Output? = when {
    text == "inventory" -> Output.Inventory
    text == "auto" -> Output.Auto
    text.startsWith("chests:") -> {
      val blocks = text.removePrefix("chests:").split(';').mapNotNull { spot ->
        val (world, place) = spot.split(':', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
        if (world != Worlds.name(player.level())) return@mapNotNull null
        val (x, y, z) = place.split(',').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 3 } ?: return@mapNotNull null
        player.level().at(x, y, z).takeIf { block ->
          block.center.distanceTo(player.position()) <= automation.settings().pauseDistance && Blocks.unsuitable(player, block, adding = true) == null
        }
      }
      if (blocks.isEmpty()) null else Output.Chests(blocks)
    }
    else -> null
  }

  /** Puts [items] where [to] says; what doesn't fit goes to the inventory, then at the player's feet. */
  fun deliver(player: ServerPlayer, items: List<ItemStack>, to: Output) {
    var left = items
    val chests = when (to) {
      is Output.Chests -> to.blocks
      // The chests around that already hold each item, nearest first, as a player would put it away.
      Output.Auto -> Blocks.near(player, automation.settings().radius) { Blocks.storage(it.state) }.map(Blocks::root).distinct()
        .filter { Blocks.unsuitable(player, it, adding = true) == null }
      Output.Inventory -> emptyList()
    }
    for (block in chests) {
      val inventory = Blocks.inventory(block) ?: continue
      val box = Blocks.box(block)
      left = left.flatMap { item ->
        val holds = to !is Output.Auto || (0 until inventory.containerSize).any { inventory.getItem(it).let { there -> !there.isEmpty && Slots.similar(there, item) } }
        if (!holds || (box && item.`is`(ItemTags.SHULKER_BOXES))) listOf(item) else listOfNotNull(Slots.add(inventory, item))
      }
    }
    give(player, left)
  }

  val all: List<Tool> = listOf(Furnaces.tool, Stands.tool, Crafting.tool, AnvilWork.tool)
}
