package chat.allay.modcore.tools

import chat.allay.core.Legacy
import chat.allay.core.Locales
import chat.allay.core.Scheduler
import chat.allay.core.building.Blueprint
import chat.allay.core.building.Blueprints
import chat.allay.core.building.Construction
import chat.allay.core.building.Families
import chat.allay.core.building.Placement
import chat.allay.core.building.Renames
import chat.allay.core.building.Shell
import chat.allay.core.tools.DraftSide
import chat.allay.modcore.Hooks
import chat.allay.modcore.Texts
import chat.allay.modcore.automation.Blocks
import chat.allay.modcore.of

import com.mojang.brigadier.exceptions.CommandSyntaxException
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.BlockTags
import net.minecraft.world.BossEvent
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.Property
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * Previews of builds from Allay's catalog: a build drawn as glowing ghosts only its player sees ([Ghosts]), where it
 * would stand, until they build it or clear it. A big build shows only its outside ([Shell]), and of that only the
 * [BUDGET] cells nearest the player, drawn again as they walk; a frame of particles marks the whole box. A cell where
 * something of the world stands in the way shows as a red box instead. Server thread only; the preview lives in
 * memory and goes on `/allay cancel`, when its player leaves, or on a restart.
 */
object Previews {
  /** The most ghosts one preview shows at once: a client handles this many display entities without a stutter. */
  const val BUDGET = 1500

  /** Ghosts sent per tick at most, so a big preview fills in over a second instead of one heavy tick. */
  private const val PER_TICK = 150

  /** Farther than this from its box, a preview shows only its frame. */
  private const val WATCHED = 96.0

  private val DUST = DustParticleOptions(0x55FFFF, 1.0f)

  class Preview(
    val owner: UUID,
    val source: Blueprints.Source,
    val title: String,
    val blueprint: Blueprint,
    val level: ServerLevel,
    var placement: Placement,
  ) {
    /** The palette as it'll be built: renamed for this game, swapped as the player asked. */
    var states: List<String> = chat.allay.core.building.Materials.placed(blueprint.palette, ::known)
    /** Swaps so far, in order: from, to. */
    val swaps = mutableListOf<Pair<String, String>>()
    internal var datas: Array<BlockState?> = emptyArray()
    internal var shell: IntArray = IntArray(0)
    internal val drawn = HashMap<Int, Ghosts.Ghost>()
    internal var wanted: IntArray = IntArray(0)
    internal var lastAt: Vec3? = null
    internal var lastLevel: ServerLevel? = null
    internal var dirty = true
    internal var bar: ServerBossEvent? = null
  }

  // Touched on the server thread only.
  private val previews = HashMap<UUID, Preview>()
  private var ticker: AutoCloseable? = null
  private var frames: AutoCloseable? = null
  private lateinit var locales: Locales

  /** Builds downloaded for previews and builds, kept in memory while in use. */
  lateinit var blueprints: Blueprints

  fun start(scheduler: Scheduler, locales: Locales, blueprints: Blueprints) {
    this.locales = locales
    this.blueprints = blueprints
    ticker = scheduler.every(2L, 2L) { previews.values.toList().forEach(::tick) }
    frames = scheduler.every(20L, 20L) { previews.values.toList().forEach(::frame) }
    Hooks.left += { clear(it.uuid) }
    Hooks.arrived += { player -> previews[player.uuid]?.let { Ghosts.resend(player, it.drawn.values) } }
  }

  fun stop() {
    ticker?.close()
    frames?.close()
    previews.keys.toList().forEach(::clear)
  }

  fun of(player: ServerPlayer): Preview? = previews[player.uuid]

  /** Shows [preview] to [player], replacing what they had. */
  fun show(player: ServerPlayer, preview: Preview) {
    clear(player.uuid)
    previews[player.uuid] = preview
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
    preview.bar?.removeAllPlayers()
    return true
  }

  fun clear(player: ServerPlayer) = clear(player.uuid)

  /** Swaps [from] for [to] across the build; returns the shapes [to] lacks, which stay as they were. */
  fun swap(preview: Preview, from: String, to: String): Set<String> {
    val (states, missing) = Families.apply(preview.states, from, to) { known(it) }
    preview.states = states
    preview.swaps += from to to
    changed(preview)
    return missing
  }

  /** Whether this game has a block by that name. */
  fun known(block: String): Boolean {
    val id = Identifier.tryParse(if (':' in block) block else "minecraft:$block") ?: return false
    return BuiltInRegistries.BLOCK.containsKey(id)
  }

  /**
   * The palette as block states this game reads, turned and mirrored as the preview stands: an old name renamed, a
   * state the block doesn't have dropped, and a block nobody knows left out (null).
   */
  private fun resolve(preview: Preview) {
    preview.datas = datas(preview.blueprint, preview.states, preview.placement)
    val opaque = BooleanArray(preview.datas.size) { preview.datas[it]?.let(Blocks::occluding) == true }
    preview.shell = Shell.of(preview.blueprint, opaque)
  }

  /** One [text] state as this game's block state, turned and mirrored as [placement] stands; null for a block it lacks. */
  fun turned(text: String, placement: Placement): BlockState? {
    var state = data(text) ?: return null
    if (placement.mirrored) state = state.mirror(Mirror.FRONT_BACK)
    return state.rotate(rotation(placement.facing))
  }

  /** [states], the palette of [blueprint], as this game's block states turned and mirrored as [placement] stands. */
  fun datas(blueprint: Blueprint, states: List<String>, placement: Placement): Array<BlockState?> {
    val rotation = rotation(placement.facing)
    return Array(states.size) { index ->
      if (blueprint.empty[index]) return@Array null
      var state = data(states[index]) ?: return@Array null
      if (placement.mirrored) state = state.mirror(Mirror.FRONT_BACK)
      state.rotate(rotation)
    }
  }

  /** [text] as this game's block state, with the states it doesn't know dropped; null for a block it doesn't have. */
  fun data(text: String): BlockState? {
    val fixed = Renames.fix(text, ::known) ?: return null
    try {
      return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, fixed, false).blockState()
    } catch (_: CommandSyntaxException) {
    } catch (_: IllegalArgumentException) {
    }
    val id = Identifier.tryParse(Blueprint.block(fixed)) ?: return null
    var state = BuiltInRegistries.BLOCK.getOptional(id).orElse(null)?.defaultBlockState() ?: return null
    for ((name, value) in Blueprint.states(fixed)) state = with(state, name, value)
    return state
  }

  private fun with(state: BlockState, name: String, value: String): BlockState {
    val property = state.block.stateDefinition.getProperty(name) ?: return state
    return set(state, property, value)
  }

  private fun <T : Comparable<T>> set(state: BlockState, property: Property<T>, value: String): BlockState =
    property.getValue(value).map { state.setValue(property, it) }.orElse(state)

  /** How a build authored facing north turns to face [facing]. */
  fun rotation(facing: DraftSide) = when (facing) {
    DraftSide.EAST -> Rotation.CLOCKWISE_90
    DraftSide.SOUTH -> Rotation.CLOCKWISE_180
    DraftSide.WEST -> Rotation.COUNTERCLOCKWISE_90
    DraftSide.NORTH -> Rotation.NONE
  }

  /** What the lot's ground costs where the world has ground already: the estimate leaves it out. */
  fun covered(preview: Preview): Map<String, Int> {
    val covered = HashMap<String, Int>()
    val ground = BooleanArray(preview.states.size) { Construction.ground(Construction.kind(preview.states[it])) }
    preview.blueprint.forEach { cx, cy, cz, index ->
      if (!ground[index]) return@forEach
      val (x, y, z) = preview.placement.world(preview.blueprint, cx, cy, cz)
      if (Construction.ground(preview.level.at(x, y, z).key)) chat.allay.core.building.Materials.of(preview.states[index]).items.forEach { (item, n) -> covered.merge(item, n, Int::plus) }
    }
    return covered
  }

  private fun block(preview: Preview, packed: Int): BlockAt {
    val (x, y, z) = preview.placement.world(preview.blueprint, Shell.x(packed), Shell.y(packed), Shell.z(packed))
    return preview.level.at(x, y, z)
  }

  /** The cells to show now: the shell's nearest to the player, at most [BUDGET]. */
  private fun nearest(preview: Preview, at: Vec3): IntArray {
    val shell = preview.shell
    if (shell.size <= BUDGET) return shell
    val px = kotlin.math.floor(at.x).toInt()
    val py = kotlin.math.floor(at.y).toInt()
    val pz = kotlin.math.floor(at.z).toInt()
    val distances = LongArray(shell.size) { i ->
      val (x, y, z) = preview.placement.world(preview.blueprint, Shell.x(shell[i]), Shell.y(shell[i]), Shell.z(shell[i]))
      val dx = x - px
      val dy = y - py
      val dz = z - pz
      ((dx * dx + dy * dy + dz * dz).toLong() shl 32) or i.toLong()
    }
    distances.sort()
    return IntArray(BUDGET) { shell[(distances[it] and 0xFFFFFFFFL).toInt()] }
  }

  private fun tick(preview: Preview) {
    val player = preview.level.server.playerList.getPlayer(preview.owner)
    if (player == null || player.hasDisconnected()) return run { clear(preview.owner) }
    blueprints.touch(preview.source.sha256)
    val (low, high) = preview.placement.bounds(preview.blueprint)
    val at = player.position()
    val far = player.level() != preview.level || distanceToBox(at, low, high) > WATCHED
    if (far) {
      if (preview.drawn.isNotEmpty()) undraw(preview)
      bar(player, preview)
      return
    }
    // Drawn again once the player has walked a few blocks: the nearest cells change as they go.
    val moved = preview.lastAt?.let { preview.lastLevel != player.level() || it.distanceToSqr(at) > 16.0 } ?: true
    if (preview.dirty || moved) {
      preview.wanted = nearest(preview, at)
      preview.lastAt = at
      preview.lastLevel = player.level()
      preview.dirty = false
      val keep = preview.wanted.toHashSet()
      val gone = preview.drawn.filterKeys { it !in keep }
      Ghosts.hide(player, gone.values)
      gone.keys.forEach(preview.drawn::remove)
    }
    var sent = 0
    val made = mutableListOf<Ghosts.Ghost>()
    for (cell in preview.wanted) {
      if (sent >= PER_TICK) break
      if (cell in preview.drawn) continue
      val block = block(preview, cell)
      val index = preview.blueprint.at(Shell.x(cell), Shell.y(cell), Shell.z(cell))
      val data = preview.datas[index] ?: continue
      // The lot's ground where the world has ground needs nothing: neither a ghost nor a red box.
      if (Construction.ground(Construction.kind(preview.states[index])) && Construction.ground(block.key)) continue
      val ghost = if (open(block) || block.state == data) Marks.ghost(block, data, Marks.Hue.AQUA) else Marks.box(block, Marks.Hue.RED)
      made += ghost
      preview.drawn[cell] = ghost
      sent++
    }
    if (made.isNotEmpty()) Ghosts.show(player, made)
    bar(player, preview)
  }

  /** The box's edges in particles, only for its player, once a second. */
  private fun frame(preview: Preview) {
    val player = preview.level.server.playerList.getPlayer(preview.owner) ?: return
    if (player.level() != preview.level) return
    val (low, high) = preview.placement.bounds(preview.blueprint)
    if (distanceToBox(player.position(), low, high) > WATCHED * 2) return
    val (x0, y0, z0) = low
    val (x1, y1, z1) = Triple(high.first + 1, high.second + 1, high.third + 1)
    val step = maxOf(1, maxOf(x1 - x0, y1 - y0, z1 - z0) / 48)
    fun line(ax: Int, ay: Int, az: Int, bx: Int, by: Int, bz: Int) {
      val n = maxOf(kotlin.math.abs(bx - ax), kotlin.math.abs(by - ay), kotlin.math.abs(bz - az))
      var i = 0
      while (i <= n) {
        val t = if (n == 0) 0.0 else i.toDouble() / n
        Trails.particles(player, DUST, Vec3(ax + (bx - ax) * t, ay + (by - ay) * t, az + (bz - az) * t), 1, 0.0, 0.0, 0.0, 0.0)
        i += step
      }
    }
    for ((ay, by) in listOf(y0 to y0, y1 to y1)) {
      line(x0, ay, z0, x1, by, z0); line(x0, ay, z1, x1, by, z1); line(x0, ay, z0, x0, by, z1); line(x1, ay, z0, x1, by, z1)
    }
    line(x0, y0, z0, x0, y1, z0); line(x1, y0, z0, x1, y1, z0); line(x0, y0, z1, x0, y1, z1); line(x1, y0, z1, x1, y1, z1)
  }

  private fun bar(player: ServerPlayer, preview: Preview) {
    val lang = locales.of(player)
    val bar = preview.bar ?: ServerBossEvent(UUID.randomUUID(), Component.empty(), BossEvent.BossBarColor.BLUE, BossEvent.BossBarOverlay.PROGRESS).also { preview.bar = it }
    // A respawn makes a new ServerPlayer: the bar follows the one that plays now.
    bar.players.filter { it !== player }.forEach(bar::removePlayer)
    if (player !in bar.players) bar.addPlayer(player)
    val b = preview.blueprint
    bar.name = Texts.legacy(Legacy.color(lang.text("previews.bar", "title" to preview.title, "size" to "${b.width}×${b.height}×${b.length}", "blocks" to b.blocks)))
    bar.progress = 1f
  }

  private fun undraw(preview: Preview) {
    val player = preview.level.server.playerList.getPlayer(preview.owner)
    Ghosts.hide(player, preview.drawn.values)
    preview.drawn.clear()
  }

  /** A spot a block can go without breaking anything first: air, water, tall grass, snow layers, and the like. */
  fun open(block: BlockAt) = block.state.isAir || block.liquid || block.state.`is`(BlockTags.REPLACEABLE)

  private fun distanceToBox(at: Vec3, low: Triple<Int, Int, Int>, high: Triple<Int, Int, Int>): Double {
    val dx = maxOf(low.first - at.x, 0.0, at.x - high.first - 1)
    val dy = maxOf(low.second - at.y, 0.0, at.y - high.second - 1)
    val dz = maxOf(low.third - at.z, 0.0, at.z - high.third - 1)
    return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
  }
}
