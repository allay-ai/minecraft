package chat.allay.modcore.tools

import chat.allay.core.Legacy
import chat.allay.core.Locales
import chat.allay.core.Scheduler
import chat.allay.core.building.Blueprint
import chat.allay.core.building.Blueprints
import chat.allay.core.building.Construction
import chat.allay.core.building.Families
import chat.allay.core.building.Materials
import chat.allay.core.building.Placement
import chat.allay.core.building.Renames
import chat.allay.core.building.Shapes
import chat.allay.core.building.Shell
import chat.allay.core.building.Showing
import chat.allay.core.tools.DraftSide
import chat.allay.modcore.Hooks
import chat.allay.modcore.Texts
import chat.allay.modcore.automation.Blocks
import chat.allay.modcore.of

import com.mojang.brigadier.exceptions.CommandSyntaxException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import net.minecraft.commands.arguments.blocks.BlockStateParser
import it.unimi.dsi.fastutil.shorts.ShortArraySet
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.SectionPos
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.BlockTags
import net.minecraft.world.BossEvent
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.EmptyBlockGetter
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.chunk.LevelChunkSection
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.Property
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * Previews of builds from Allay's catalog: the build as blocks only its player sees, full size and as it will stand,
 * until they build it or clear it. The server tells that client a block stands where the world has air, grass, or
 * whatever the builder would clear, section by section as the client has the chunks ([Showing]); the world itself
 * never changes, and the real blocks come back when the preview goes. A spot where something of the world stands in
 * the way shows as a red box instead ([Ghosts]), and a frame of particles marks the whole box. The client takes the
 * blocks for solid: the player walks into the preview's walls as into real ones. Server thread only; the preview
 * lives in memory and goes on `/allay cancel`, when its player leaves, or on a restart.
 */
object Previews {
  /** The most red boxes one preview shows: a client draws this many display entities without a stutter. */
  private const val BOXES = 256

  /** Chunks sent a tick at most, nearest first, so a big preview fills in over a second instead of one heavy tick. */
  private const val CHUNKS = 4

  /** Farther than this from its box, a preview shows no frame. */
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
    var states: List<String> = Materials.placed(blueprint.palette, blueprint.dataVersion, ::known)
    /** The lot's surface layer ([Construction.surface]): there and below, the lot's ground may stand on the world's. */
    val surface = Construction.surface(blueprint)
    /** Swaps so far, in order: from, to. */
    val swaps = mutableListOf<Pair<String, String>>()
    internal var datas: Array<BlockState?> = emptyArray()
    internal var shaped: Map<Int, BlockState> = emptyMap()
    /** The build's blocks by world chunk ([Showing.chunks]). */
    internal var chunks: Map<Long, IntArray> = emptyMap()
    internal val schedule = Showing.Schedule()
    /**
     * World spots ([Showing.spot]) the client sees as the build's, by chunk: their real blocks go back to it when the
     * preview goes or moves. Spots, not cells: once the build moved, its cells name other spots.
     */
    internal val shown = HashMap<Long, LongArray>()
    /** Red boxes over spots in the way, by chunk and cell. */
    internal val boxes = HashMap<Long, HashMap<Int, Ghosts.Ghost>>()
    internal var bar: ServerBossEvent? = null
  }

  // Touched on the server thread only.
  private val previews = HashMap<UUID, Preview>()
  private var ticker: AutoCloseable? = null
  private var frames: AutoCloseable? = null
  private var now = 0L
  private lateinit var locales: Locales

  /** Builds downloaded for previews and builds, kept in memory while in use. */
  lateinit var blueprints: Blueprints

  fun start(scheduler: Scheduler, locales: Locales, blueprints: Blueprints) {
    this.locales = locales
    this.blueprints = blueprints
    ticker = scheduler.every(2L, 2L) {
      now += 2
      previews.values.toList().forEach(::tick)
    }
    frames = scheduler.every(20L, 20L) {
      previews.values.toList().forEach(::frame)
      // Builds nobody previews or builds any more leave memory after `building.keep_minutes`.
      blueprints.forget()
    }
    Hooks.left += { clear(it.uuid) }
    // A client in another world, or born again, starts with the world's blocks, and forgets the boxes.
    Hooks.arrived += { player ->
      previews[player.uuid]?.let { preview ->
        preview.schedule.forgetAll()
        preview.shown.clear()
        preview.boxes.clear()
      }
    }
    // A block the server sends the client takes the preview's place there: the chunk goes again.
    Hooks.placedBlock += { _, level, pos -> touched(level, pos) }
    Hooks.brokeBlock += { _, level, pos -> touched(level, pos) }
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

  /** Something about the preview changed: where it stands, how it faces, or its blocks. Everything is shown again. */
  fun changed(preview: Preview) {
    unshow(preview)
    resolve(preview)
    tick(preview)
  }

  fun clear(owner: UUID): Boolean {
    val preview = previews.remove(owner) ?: return false
    unshow(preview)
    preview.bar?.removeAllPlayers()
    return true
  }

  fun clear(player: ServerPlayer) = clear(player.uuid)

  private fun touched(level: ServerLevel, pos: BlockPos) {
    val key = Showing.chunk(pos.x shr 4, pos.z shr 4)
    for (preview in previews.values) if (preview.level === level && key in preview.chunks) preview.schedule.touch(key)
  }

  /** Swaps [from] for [to] across the build; returns the shapes [to] lacks, which stay as they were. */
  fun swap(preview: Preview, from: String, to: String): Set<String> {
    val (states, missing) = Families.apply(preview.states, from, to) { known(it) }
    preview.states = states
    preview.swaps += from to to
    changed(preview)
    return missing
  }

  /**
   * Why a build can't be made of [block] instead, or null when it can: a block this game has, that a player places
   * from an item they pay ([chat.allay.core.building.Materials.refusal]).
   */
  fun refusal(block: String): String? {
    if (!known(block)) return "$block isn't a block this server has; search_items finds its id"
    Materials.refusal(block)?.let { return it }
    val items = Materials.of("minecraft:$block").items.keys
    return items.firstOrNull { name -> Identifier.tryParse("minecraft:$name")?.let(BuiltInRegistries.ITEM::containsKey) != true }
      ?.let { "$block isn't placed from an item here; name another block" }
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
    preview.shaped = shaped(preview.blueprint, preview.states, preview.placement)
    preview.chunks = Showing.chunks(preview.blueprint, preview.placement)
  }

  /** One [text] state as this game's block state, turned and mirrored as [placement] stands; null for a block it lacks. */
  fun turned(text: String, placement: Placement): BlockState? {
    var state = data(text) ?: return null
    if (placement.mirrored) state = state.mirror(Mirror.FRONT_BACK)
    return state.rotate(rotation(placement.facing))
  }

  /**
   * The cells of [blueprint] the game would settle otherwise once it stands (see [Shapes]), as block states turned and
   * mirrored as [placement] stands.
   */
  fun shaped(blueprint: Blueprint, states: List<String>, placement: Placement): Map<Int, BlockState> {
    val sturdy = HashMap<String, Boolean>()
    val settled = Shapes.settle(blueprint, states) { text, face ->
      sturdy.getOrPut("$text $face") {
        val direction = Direction.entries.first { it.serializedName == face }
        data(text)?.isFaceSturdy(EmptyBlockGetter.INSTANCE, BlockPos.ZERO, direction) == true
      }
    }
    return settled.mapNotNull { (cell, text) -> turned(text, placement)?.let { cell to it } }.toMap()
  }

  /** Whether the builder clears [block] out of a build's way: the world's own ground and plants, and trees. */
  private fun clearable(block: BlockAt): Boolean = Construction.natural(block.key) || Construction.log(block.key) || decaying(block.state)

  /** Leaves the game lets decay: a tree's, not a player's. */
  fun decaying(state: BlockState) = state.`is`(BlockTags.LEAVES) && state.hasProperty(LeavesBlock.PERSISTENT) && !state.getValue(LeavesBlock.PERSISTENT)

  /** Whether [blueprint]'s box at [placement] lies within [level]'s height. */
  fun fits(level: ServerLevel, blueprint: Blueprint, placement: Placement): Boolean {
    val (low, high) = placement.bounds(blueprint)
    return low.second >= level.minY && high.second <= level.maxY
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
      if (!ground[index] || cy > preview.surface) return@forEach
      val (x, y, z) = preview.placement.world(preview.blueprint, cx, cy, cz)
      if (land(preview.level.at(x, y, z))) Materials.of(preview.states[index]).items.forEach { (item, n) -> covered.merge(item, n, Int::plus) }
    }
    return covered
  }

  /** Sends the chunks that are due, nearest first, once the client has them; forgets those it let go. */
  private fun tick(preview: Preview) {
    val player = preview.level.server.playerList.getPlayer(preview.owner)
    if (player == null || player.hasDisconnected()) return run { clear(preview.owner) }
    blueprints.touch(preview.source.sha256)
    bar(player, preview)
    if (player.level() !== preview.level) return
    // The client has a chunk once it's in its view and no longer waits to go.
    fun has(key: Long): Boolean {
      val (cx, cz) = Showing.chunkX(key) to Showing.chunkZ(key)
      return player.chunkTrackingView.contains(cx, cz) && !player.connection.chunkSender.isPending(ChunkPos.pack(cx, cz))
    }
    // A chunk the client let go took the preview's blocks and boxes in it along.
    for (key in preview.schedule.chunks.filterNot(::has)) {
      preview.schedule.forget(key)
      preview.shown.remove(key)
      preview.boxes.remove(key)?.values?.let { Ghosts.hide(player, it) }
    }
    val px = player.blockPosition().x shr 4
    val pz = player.blockPosition().z shr 4
    val due = preview.chunks.keys.filter { has(it) && preview.schedule.due(it, now) }
      .sortedBy { max(abs(Showing.chunkX(it) - px), abs(Showing.chunkZ(it) - pz)) }
    for (key in due.take(CHUNKS)) {
      send(player, preview, key)
      preview.schedule.sent(key, now)
    }
  }

  /**
   * Shows [player] the build's blocks in chunk [key]: where the world has air, water, or what the builder clears, the
   * build's block; where something else stands in the way, a red box; nothing where the block is there already, or the
   * lot's ground meets the world's. A cell shown before that the world filled since gets its real block back.
   */
  private fun send(player: ServerPlayer, preview: Preview, key: Long) {
    val bp = preview.blueprint
    val level = preview.level
    val fakes = HashMap<BlockPos, BlockState>()
    val spots = ArrayList<Long>()
    val boxes = preview.boxes.getOrPut(key) { HashMap() }
    val stay = HashSet<Int>()
    val made = ArrayList<Ghosts.Ghost>()
    for (cell in preview.chunks[key] ?: return) {
      val index = bp.at(Shell.x(cell), Shell.y(cell), Shell.z(cell))
      val data = preview.shaped[cell] ?: preview.datas[index] ?: continue
      val (x, y, z) = preview.placement.world(bp, Shell.x(cell), Shell.y(cell), Shell.z(cell))
      if (y < level.minY || y > level.maxY) continue
      val block = level.at(x, y, z)
      if (Shell.y(cell) <= preview.surface && Construction.ground(Construction.kind(preview.states[index])) && land(block)) continue
      if (block.state == data) continue
      // The lot's tufts and flowers never contend with what stands there.
      if (Construction.decor(Construction.kind(preview.states[index])) && !open(block)) continue
      if (open(block) || clearable(block)) {
        fakes[block.pos] = data
        spots += Showing.spot(x, y, z)
      } else if (cell in boxes || preview.boxes.values.sumOf { it.size } < BOXES) {
        stay += cell
        boxes.getOrPut(cell) { Marks.box(block, Marks.Hue.RED).also { made += it } }
      }
    }
    val gone = boxes.filterKeys { it !in stay }
    Ghosts.hide(player, gone.values)
    gone.keys.forEach(boxes::remove)
    Ghosts.show(player, made)
    val kept = spots.toHashSet()
    val back = preview.shown[key]?.filter { it !in kept }.orEmpty().associate { spot -> real(level, spot).let { it.pos to it.state } }
    sendBlocks(player, level, back)
    sendBlocks(player, level, fakes)
    preview.shown[key] = spots.toLongArray()
  }

  /** Tells [player]'s client [blocks] stand, one packet a section, as the game sends a section's changes. */
  private fun sendBlocks(player: ServerPlayer, level: ServerLevel, blocks: Map<BlockPos, BlockState>) {
    for ((section, inSection) in blocks.entries.groupBy { SectionPos.of(it.key) }) {
      val states = LevelChunkSection(level.palettedContainerFactory())
      val positions = ShortArraySet()
      for ((pos, state) in inSection) {
        states.setBlockState(pos.x and 15, pos.y and 15, pos.z and 15, state, false)
        positions.add(SectionPos.sectionRelativePos(pos))
      }
      player.connection.send(ClientboundSectionBlocksUpdatePacket(section, positions, states))
    }
  }

  private fun real(level: ServerLevel, spot: Long): BlockAt = level.at(Showing.spotX(spot), Showing.spotY(spot), Showing.spotZ(spot))

  /** Gives the player the world's real blocks back where the preview stood, and takes its boxes away. */
  private fun unshow(preview: Preview) {
    val player = preview.level.server.playerList.getPlayer(preview.owner)?.takeIf { !it.hasDisconnected() && it.level() === preview.level }
    if (player != null) {
      val back = preview.shown.values.flatMap { spots -> spots.map { real(preview.level, it) } }.associate { it.pos to it.state }
      sendBlocks(player, preview.level, back)
    }
    Ghosts.hide(player, preview.boxes.values.flatMap { it.values })
    preview.shown.clear()
    preview.boxes.clear()
    preview.schedule.forgetAll()
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
      val n = maxOf(abs(bx - ax), abs(by - ay), abs(bz - az))
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

  /**
   * Whether [block] is the world's own solid ground, which a lot's ground below its surface stands on instead of being
   * built: soil, stone, sand, sandstone, terracotta, ores, and the like, not water or a cave's air.
   */
  @Suppress("DEPRECATION")
  fun land(block: BlockAt) = block.state.isSolid && Construction.natural(block.key)

  /** A spot a block can go without breaking anything first: air, water, tall grass, snow layers, and the like. */
  fun open(block: BlockAt) = block.state.isAir || block.liquid || block.state.`is`(BlockTags.REPLACEABLE)

  private fun distanceToBox(at: Vec3, low: Triple<Int, Int, Int>, high: Triple<Int, Int, Int>): Double {
    val dx = maxOf(low.first - at.x, 0.0, at.x - high.first - 1)
    val dy = maxOf(low.second - at.y, 0.0, at.y - high.second - 1)
    val dz = maxOf(low.third - at.z, 0.0, at.z - high.third - 1)
    return sqrt(dx * dx + dy * dy + dz * dz)
  }
}
