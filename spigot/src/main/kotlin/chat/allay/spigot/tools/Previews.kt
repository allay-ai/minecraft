package chat.allay.spigot.tools

import chat.allay.core.Locales
import chat.allay.core.building.Blueprint
import chat.allay.core.building.Blueprints
import chat.allay.core.building.Families
import chat.allay.core.building.Placement
import chat.allay.core.building.Renames
import chat.allay.core.building.Shell
import chat.allay.core.tools.DraftSide
import chat.allay.spigot.of

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Tag
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.BlockData
import org.bukkit.block.structure.Mirror
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
 * Previews of builds from Allay's catalog: a build drawn as glowing ghosts only its player sees, where it would stand,
 * until they build it or clear it. A big build shows only its outside ([Shell]), and of that only the [BUDGET] cells
 * nearest the player, drawn again as they walk; a frame of particles marks the whole box. A cell where something of
 * the world stands in the way shows as a red box instead. Server thread only; the preview lives in memory and goes
 * on `/allay cancel`, when its player leaves, or on a restart.
 */
object Previews : Listener {
  /** The most ghosts one preview shows at once: a client handles this many display entities without a stutter. */
  const val BUDGET = 1500

  /** Ghosts spawned per tick at most, so a big preview fills in over a second instead of one heavy tick. */
  private const val PER_TICK = 150

  /** Farther than this from its box, a preview shows only its frame. */
  private const val WATCHED = 96.0

  class Preview(
    val owner: UUID,
    val source: Blueprints.Source,
    val title: String,
    val blueprint: Blueprint,
    val world: World,
    var placement: Placement,
  ) {
    /** The palette as it'll be built: renamed for this game, swapped as the player asked. */
    var states: List<String> = chat.allay.core.building.Materials.placed(blueprint.palette, ::known)
    /** Swaps so far, in order: from, to. */
    val swaps = mutableListOf<Pair<String, String>>()
    internal var datas: Array<BlockData?> = emptyArray()
    internal var shell: IntArray = IntArray(0)
    internal val drawn = HashMap<Int, Entity>()
    internal var wanted: IntArray = IntArray(0)
    internal var lastAt: Location? = null
    internal var dirty = true
    internal var bar: BossBar? = null
  }

  private val previews = HashMap<UUID, Preview>()
  private var ticker: BukkitTask? = null
  private var frames: BukkitTask? = null
  private lateinit var plugin: Plugin
  private lateinit var locales: Locales

  /** Builds downloaded for previews and builds, kept in memory while in use. */
  lateinit var blueprints: Blueprints

  fun start(plugin: Plugin, locales: Locales, blueprints: Blueprints) {
    this.plugin = plugin
    this.locales = locales
    this.blueprints = blueprints
    ticker = plugin.server.scheduler.runTaskTimer(plugin, Runnable { previews.values.toList().forEach(::tick) }, 2L, 2L)
    frames = plugin.server.scheduler.runTaskTimer(plugin, Runnable { previews.values.toList().forEach(::frame) }, 20L, 20L)
  }

  fun stop() {
    ticker?.cancel()
    frames?.cancel()
    previews.keys.toList().forEach(::clear)
  }

  fun of(player: Player): Preview? = previews[player.uniqueId]

  /** Shows [preview] to [player], replacing what they had. */
  fun show(player: Player, preview: Preview) {
    clear(player.uniqueId)
    previews[player.uniqueId] = preview
    resolve(preview)
    tick(preview)
  }

  /** Something about the preview changed: where it stands, how it faces, or its blocks. Everything is drawn again. */
  fun changed(preview: Preview) {
    resolve(preview)
    undraw(preview)
    preview.dirty = true
    tick(preview)
  }

  fun clear(owner: UUID): Boolean {
    val preview = previews.remove(owner) ?: return false
    undraw(preview)
    preview.bar?.removeAll()
    return true
  }

  fun clear(player: Player) = clear(player.uniqueId)

  @EventHandler fun onQuit(event: PlayerQuitEvent) {
    clear(event.player)
  }

  /** Swaps [from] for [to] across the build; returns the shapes [to] lacks, which stay as they were. */
  fun swap(preview: Preview, from: String, to: String): Set<String> {
    val (states, missing) = Families.apply(preview.states, from, to) { known(it) }
    preview.states = states
    preview.swaps += from to to
    changed(preview)
    return missing
  }

  /** Whether this game has a block by that name. */
  fun known(block: String): Boolean = Material.matchMaterial(block.removePrefix("minecraft:"))?.isBlock == true

  /**
   * The palette as block data this game reads, turned and mirrored as the preview stands: an old name renamed, a
   * state the block doesn't have dropped, and a block nobody knows left out (null).
   */
  private fun resolve(preview: Preview) {
    preview.datas = datas(preview.blueprint, preview.states, preview.placement)
    val opaque = BooleanArray(preview.datas.size) { preview.datas[it]?.material?.isOccluding == true }
    preview.shell = Shell.of(preview.blueprint, opaque)
  }

  /** One [state] as block data, turned and mirrored as [placement] stands; null for a block this game lacks. */
  fun turned(state: String, placement: Placement): BlockData? {
    val data = data(state) ?: return null
    if (placement.mirrored) data.mirror(Mirror.FRONT_BACK)
    data.rotate(rotation(placement.facing))
    return data
  }

  /** [states], the palette of [blueprint], as this game's block data turned and mirrored as [placement] stands. */
  fun datas(blueprint: Blueprint, states: List<String>, placement: Placement): Array<BlockData?> {
    val rotation = rotation(placement.facing)
    return Array(states.size) { index ->
      if (blueprint.empty[index]) return@Array null
      val data = data(states[index]) ?: return@Array null
      if (placement.mirrored) data.mirror(Mirror.FRONT_BACK)
      data.rotate(rotation)
      data
    }
  }

  /** A spot a block can go without breaking anything first: air, water, tall grass, snow layers, and the like. */
  fun open(block: Block) = block.isEmpty || block.isLiquid || Tag.REPLACEABLE.isTagged(block.type)

  /** [state] as this game's block data, with the states it doesn't know dropped; null for a block it doesn't have. */
  fun data(state: String): BlockData? {
    val fixed = Renames.fix(state, ::known) ?: return null
    return runCatching { Bukkit.createBlockData(fixed) }.getOrNull() ?: runCatching {
      val block = Blueprint.block(fixed)
      val base = Bukkit.createBlockData(block)
      val allowed = Blueprint.states(base.getAsString(false))
      val kept = Blueprint.states(fixed).filterKeys { it in allowed }
      Bukkit.createBlockData(block + if (kept.isEmpty()) "" else kept.entries.joinToString(",", "[", "]") { "${it.key}=${it.value}" })
    }.getOrNull()
  }

  /** How a build authored facing north turns to face [facing]. */
  fun rotation(facing: DraftSide) = when (facing) {
    DraftSide.EAST -> StructureRotation.CLOCKWISE_90
    DraftSide.SOUTH -> StructureRotation.CLOCKWISE_180
    DraftSide.WEST -> StructureRotation.COUNTERCLOCKWISE_90
    DraftSide.NORTH -> StructureRotation.NONE
  }

  fun side(face: BlockFace) = when (face) {
    BlockFace.EAST -> DraftSide.EAST
    BlockFace.SOUTH -> DraftSide.SOUTH
    BlockFace.WEST -> DraftSide.WEST
    else -> DraftSide.NORTH
  }

  private fun block(preview: Preview, packed: Int): Block {
    val (x, y, z) = preview.placement.world(preview.blueprint, Shell.x(packed), Shell.y(packed), Shell.z(packed))
    return preview.world.getBlockAt(x, y, z)
  }

  /** The cells to show now: the shell's nearest to the player, at most [BUDGET]. */
  private fun nearest(preview: Preview, at: Location): IntArray {
    val shell = preview.shell
    if (shell.size <= BUDGET) return shell
    val distances = LongArray(shell.size) { i ->
      val (x, y, z) = preview.placement.world(preview.blueprint, Shell.x(shell[i]), Shell.y(shell[i]), Shell.z(shell[i]))
      val dx = x - at.blockX
      val dy = y - at.blockY
      val dz = z - at.blockZ
      ((dx * dx + dy * dy + dz * dz).toLong() shl 32) or i.toLong()
    }
    distances.sort()
    return IntArray(BUDGET) { shell[(distances[it] and 0xFFFFFFFFL).toInt()] }
  }

  private fun tick(preview: Preview) {
    val player = Bukkit.getPlayer(preview.owner)
    if (player == null || !player.isOnline) return run { clear(preview.owner) }
    blueprints.touch(preview.source.sha256)
    val (low, high) = preview.placement.bounds(preview.blueprint)
    val at = player.location
    val far = player.world != preview.world || distanceToBox(at, low, high) > WATCHED
    if (far) {
      if (preview.drawn.isNotEmpty()) undraw(preview)
      bar(player, preview)
      return
    }
    // Drawn again once the player has walked a few blocks: the nearest cells change as they go.
    val moved = preview.lastAt?.let { it.world != at.world || it.distanceSquared(at) > 16.0 } ?: true
    if (preview.dirty || moved) {
      preview.wanted = nearest(preview, at)
      preview.lastAt = at.clone()
      preview.dirty = false
      val keep = preview.wanted.toHashSet()
      preview.drawn.entries.removeIf { (cell, entity) -> (cell !in keep).also { if (it) entity.remove() } }
    }
    var spawned = 0
    for (cell in preview.wanted) {
      if (spawned >= PER_TICK) break
      if (cell in preview.drawn) continue
      val block = block(preview, cell)
      val data = preview.datas[preview.blueprint.at(Shell.x(cell), Shell.y(cell), Shell.z(cell))] ?: continue
      val ghost = if (open(block) || block.blockData.matches(data)) Marks.ghost(block, data, Marks.Hue.AQUA) else Marks.box(block, Marks.Hue.RED)
      Marks.reveal(plugin, player, listOf(ghost))
      preview.drawn[cell] = ghost
      spawned++
    }
    bar(player, preview)
  }

  /** The box's edges in particles, only for its player, once a second. */
  private fun frame(preview: Preview) {
    val player = Bukkit.getPlayer(preview.owner) ?: return
    if (player.world != preview.world) return
    val (low, high) = preview.placement.bounds(preview.blueprint)
    if (distanceToBox(player.location, low, high) > WATCHED * 2) return
    val (x0, y0, z0) = low
    val (x1, y1, z1) = Triple(high.first + 1, high.second + 1, high.third + 1)
    val step = maxOf(1, maxOf(x1 - x0, y1 - y0, z1 - z0) / 48)
    val dust = Particle.DustOptions(org.bukkit.Color.AQUA, 1.0f)
    fun line(ax: Int, ay: Int, az: Int, bx: Int, by: Int, bz: Int) {
      val n = maxOf(kotlin.math.abs(bx - ax), kotlin.math.abs(by - ay), kotlin.math.abs(bz - az))
      var i = 0
      while (i <= n) {
        val t = if (n == 0) 0.0 else i.toDouble() / n
        player.spawnParticle(Particle.DUST, ax + (bx - ax) * t, ay + (by - ay) * t, az + (bz - az) * t, 1, dust)
        i += step
      }
    }
    for ((ay, by) in listOf(y0 to y0, y1 to y1)) {
      line(x0, ay, z0, x1, by, z0); line(x0, ay, z1, x1, by, z1); line(x0, ay, z0, x0, by, z1); line(x1, ay, z0, x1, by, z1)
    }
    line(x0, y0, z0, x0, y1, z0); line(x1, y0, z0, x1, y1, z0); line(x0, y0, z1, x0, y1, z1); line(x1, y0, z1, x1, y1, z1)
  }

  private fun bar(player: Player, preview: Preview) {
    val lang = locales.of(player)
    val bar = preview.bar ?: Bukkit.createBossBar("", BarColor.BLUE, BarStyle.SOLID).also { preview.bar = it; it.addPlayer(player) }
    val b = preview.blueprint
    bar.setTitle(lang.text("previews.bar", "title" to preview.title, "size" to "${b.width}×${b.height}×${b.length}", "blocks" to b.blocks))
    bar.progress = 1.0
  }

  private fun undraw(preview: Preview) {
    preview.drawn.values.forEach(Entity::remove)
    preview.drawn.clear()
  }


  private fun distanceToBox(at: Location, low: Triple<Int, Int, Int>, high: Triple<Int, Int, Int>): Double {
    val dx = maxOf(low.first - at.x, 0.0, at.x - high.first - 1)
    val dy = maxOf(low.second - at.y, 0.0, at.y - high.second - 1)
    val dz = maxOf(low.third - at.z, 0.0, at.z - high.third - 1)
    return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
  }
}
