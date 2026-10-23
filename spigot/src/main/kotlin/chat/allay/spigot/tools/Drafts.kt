package chat.allay.spigot.tools

import chat.allay.spigot.of
import chat.allay.spigot.code
import chat.allay.spigot.audience

import chat.allay.core.Lang
import chat.allay.core.tools.DraftBlock
import chat.allay.core.tools.DraftCell
import chat.allay.core.tools.Drafting
import chat.allay.core.tools.ToolError
import chat.allay.core.Locales
import chat.allay.spigot.send

import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.Tag
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.BlockData
import org.bukkit.block.structure.StructureRotation
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.UUID

/**
 * Drafts: a build of up to [WIDTH]×[HEIGHT]×[DEPTH] blocks drawn as glowing ghosts that only its player sees, one
 * layer at a time from the bottom. Every half second the plugin compares the layer with the world: a block in place
 * goes out, a wrong one gets a red box saying what belongs there, and once the layer is done the next one lights up.
 * A boss bar counts. The draft lives in memory: it goes on `/allay cancel`, when the player leaves, or on a restart.
 *
 * Cells are in the draft's own frame: `right` from the player's left to right, `up`, and `forward` away from them;
 * block states are written as if the player faced north (north is forward, east is right) and turn with the draft.
 */
object Drafts : Listener {
  const val WIDTH = Drafting.WIDTH
  const val HEIGHT = Drafting.HEIGHT
  const val DEPTH = Drafting.DEPTH
  const val MAX_SHOWN = Drafting.MAX_SHOWN

  class Draft(val owner: UUID, val title: String, val world: World, var corner: Triple<Int, Int, Int>, var facing: BlockFace, val cells: MutableMap<DraftCell, DraftBlock<BlockData>>) {
    /** The layer shown, or -1 for all of them at once. */
    var layer = 0
    val layers get() = (cells.keys.maxOfOrNull { it.up } ?: 0) + 1
    val width get() = (cells.keys.maxOfOrNull { it.right } ?: 0) + 1
    val depth get() = (cells.keys.maxOfOrNull { it.forward } ?: 0) + 1
    internal val drawn = HashMap<DraftCell, Pair<State, List<Entity>>>()
    internal var bar: BossBar? = null
  }

  enum class State { DONE, GHOST, WRONG }

  // Touched on the server thread only.
  private val drafts = HashMap<UUID, Draft>()
  private var ticker: BukkitTask? = null
  private lateinit var plugin: Plugin
  private lateinit var locales: Locales

  fun start(plugin: Plugin, locales: Locales) {
    this.plugin = plugin
    this.locales = locales
    ticker = plugin.server.scheduler.runTaskTimer(plugin, Runnable { drafts.values.toList().forEach(::tick) }, 10L, 10L)
  }

  fun stop() {
    ticker?.cancel()
    drafts.values.toList().forEach { clear(it.owner) }
  }

  fun of(player: Player): Draft? = drafts[player.uniqueId]

  fun begin(player: Player, draft: Draft) {
    clear(player.uniqueId)
    drafts[player.uniqueId] = draft
    draft.layer = firstOpen(draft, 0)
    tick(draft)
  }

  /** Removes the player's draft; false when they had none. */
  fun clear(owner: UUID): Boolean {
    val draft = drafts.remove(owner) ?: return false
    undraw(draft)
    draft.bar?.removeAll()
    return true
  }

  fun clear(player: Player) = clear(player.uniqueId)

  /** After the draft moved, turned, or changed layer: everything is drawn again. */
  fun redraw(draft: Draft) {
    undraw(draft)
    tick(draft)
  }

  @EventHandler fun onQuit(event: PlayerQuitEvent) {
    clear(event.player)
  }

  /** The world block of a cell. */
  fun block(draft: Draft, cell: DraftCell): Block {
    val (fx, fz) = step(draft.facing)
    val (rx, rz) = step(right(draft.facing))
    val (x, y, z) = draft.corner
    return draft.world.getBlockAt(x + rx * cell.right + fx * cell.forward, y + cell.up, z + rz * cell.right + fz * cell.forward)
  }

  /** The block a cell asks for, turned the way the draft faces. */
  fun turned(draft: Draft, planned: DraftBlock<BlockData>): BlockData? = planned.data?.clone()?.also { it.rotate(rotation(draft.facing)) }

  /** Whether the world already holds what the cell asks for: the same block, with every state the draft spelled out. */
  fun state(draft: Draft, cell: DraftCell, planned: DraftBlock<BlockData>): State {
    val block = block(draft, cell)
    val expected = turned(draft, planned)
    if (expected == null) return if (open(block)) State.DONE else State.WRONG
    if (block.type != expected.material) return if (open(block)) State.GHOST else State.WRONG
    if (planned.keys.isEmpty()) return State.DONE
    val actual = states(block.blockData)
    val wanted = states(expected)
    return if (planned.keys.all { actual[it] == wanted[it] }) State.DONE else State.WRONG
  }

  /** What's wrong in a cell, for the label over its red box and for `check`. */
  fun wanted(draft: Draft, planned: DraftBlock<BlockData>): String {
    val expected = turned(draft, planned) ?: return "air"
    val shown = states(expected).filterKeys { it in planned.keys }
    return expected.material.key.key + if (shown.isEmpty()) "" else shown.entries.joinToString(",", "[", "]") { "${it.key}=${it.value}" }
  }

  /** Cells of a layer, or of all of them for -1. */
  fun cellsOf(draft: Draft, layer: Int) = draft.cells.filterKeys { layer < 0 || it.up == layer }

  /** The first layer from [from] up that still has work, or the last one. */
  fun firstOpen(draft: Draft, from: Int): Int =
    (from until draft.layers).firstOrNull { layer -> cellsOf(draft, layer).any { (cell, planned) -> state(draft, cell, planned) != State.DONE } } ?: (draft.layers - 1)

  private fun tick(draft: Draft) {
    val player = Bukkit.getPlayer(draft.owner)
    if (player == null || !player.isOnline) return run { clear(draft.owner) }
    val lang = locales.of(player)
    val cells = cellsOf(draft, draft.layer)
    var done = 0
    cells.forEach { (cell, planned) ->
      val now = state(draft, cell, planned)
      if (now == State.DONE) done++
      val before = draft.drawn[cell]
      if (before?.first == now) return@forEach
      before?.second?.forEach(Entity::remove)
      draft.drawn.remove(cell)
      val made = draw(draft, cell, planned, now, lang)
      if (made.isNotEmpty()) {
        Marks.reveal(plugin, player, made)
        draft.drawn[cell] = now to made
      } else {
        draft.drawn[cell] = now to emptyList()
      }
    }
    val bar = draft.bar ?: Bukkit.createBossBar("", BarColor.BLUE, BarStyle.SEGMENTED_10).also { draft.bar = it; it.addPlayer(player) }
    val total = cells.size.coerceAtLeast(1)
    bar.progress = (done.toDouble() / total).coerceIn(0.0, 1.0)
    bar.setTitle(lang.text(if (draft.layer < 0) "drafts.bar_all" else "drafts.bar", "title" to draft.title, "layer" to draft.layer + 1, "layers" to draft.layers, "done" to done, "total" to cells.size))
    if (done < cells.size || draft.layer < 0) return
    // The layer is built: on to the next one with work left, or the whole draft is done.
    val next = if (draft.layer + 1 < draft.layers) firstOpen(draft, draft.layer + 1) else draft.layers
    if (next >= draft.layers || cellsOf(draft, next).all { (cell, planned) -> state(draft, cell, planned) == State.DONE }) {
      lang.send(player, "drafts.done", "title" to draft.title)
      player.playSound(player.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.2f)
      clear(draft.owner)
      return
    }
    lang.send(player, "drafts.layer_done", "layer" to draft.layer + 1, "next" to next + 1)
    player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.4f)
    draft.layer = next
    undraw(draft)
  }

  private fun draw(draft: Draft, cell: DraftCell, planned: DraftBlock<BlockData>, state: State, lang: Lang): List<Entity> {
    val block = block(draft, cell)
    return when (state) {
      State.DONE -> emptyList()
      State.GHOST -> {
        val data = turned(draft, planned) ?: return emptyList()
        listOfNotNull(Marks.ghost(block, data, Marks.Hue.AQUA), planned.label?.let { Marks.label(block, it, Marks.Hue.AQUA, block.y + 1.2) })
      }
      State.WRONG -> listOf(
        Marks.box(block, Marks.Hue.RED),
        Marks.label(block, if (planned.data == null) lang.text("drafts.clear") else lang.text("drafts.needs", "block" to wanted(draft, planned)), Marks.Hue.RED, block.y + 1.3),
      )
    }
  }

  private fun undraw(draft: Draft) {
    draft.drawn.values.forEach { (_, entities) -> entities.forEach(Entity::remove) }
    draft.drawn.clear()
  }

  /** A spot a block can go without breaking anything first: air, water, tall grass, snow layers, and the like. */
  private fun open(block: Block) = block.isEmpty || block.isLiquid || Tag.REPLACEABLE.isTagged(block.type)

  /** Block states as `key=value`, from the game's own text for the block. */
  fun states(data: BlockData): Map<String, String> = Drafting.states(data.getAsString(false))

  /** How a draft written facing north turns to face [facing]. */
  fun rotation(facing: BlockFace) = when (facing) {
    BlockFace.EAST -> StructureRotation.CLOCKWISE_90
    BlockFace.SOUTH -> StructureRotation.CLOCKWISE_180
    BlockFace.WEST -> StructureRotation.COUNTERCLOCKWISE_90
    else -> StructureRotation.NONE
  }

  fun right(facing: BlockFace) = when (facing) {
    BlockFace.NORTH -> BlockFace.EAST
    BlockFace.EAST -> BlockFace.SOUTH
    BlockFace.SOUTH -> BlockFace.WEST
    else -> BlockFace.NORTH
  }

  fun step(face: BlockFace) = face.modX to face.modZ

  /** The way the player looks, to the nearest side of the world. */
  fun cardinal(player: Player): BlockFace {
    val yaw = ((player.location.yaw % 360) + 360) % 360
    return when {
      yaw >= 315 || yaw < 45 -> BlockFace.SOUTH
      yaw < 135 -> BlockFace.WEST
      yaw < 225 -> BlockFace.NORTH
      else -> BlockFace.EAST
    }
  }

  /** Reads one palette entry: a block id with states, like `repeater[facing=north,delay=2]`, or `air` for a cell that must stay empty. */
  fun parse(text: String, label: String?): DraftBlock<BlockData> {
    val clean = Drafting.clean(text)
    if (clean == "air") return DraftBlock<BlockData>(null, label, emptySet())
    val data = try {
      Bukkit.createBlockData("minecraft:$clean")
    } catch (_: IllegalArgumentException) {
      throw ToolError("$text is not a block the game knows; write ids with states like repeater[facing=north,delay=2]")
    }
    if (data.material.isAir) return DraftBlock<BlockData>(null, label, emptySet())
    // Dust's connections and power follow what's around it; only the states the draft spells out are checked.
    return DraftBlock(data, label, Drafting.spelled(clean))
  }

}
