package chat.allay.spigot.tools

import chat.allay.core.Locales
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
import chat.allay.spigot.of

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Tag
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.BlockState
import org.bukkit.block.data.BlockData
import org.bukkit.block.BlockSupport
import org.bukkit.block.data.type.Leaves
import org.bukkit.block.structure.Mirror
import org.bukkit.block.structure.StructureRotation
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.UUID

/**
 * Previews of builds from Allay's catalog: the build as blocks only its player sees, full size and as it will stand,
 * until they build it or clear it. The server tells that client a block stands where the world has air, grass, or
 * whatever the builder would clear, chunk by chunk as the client has the chunks ([Showing]); the world itself never
 * changes, and the real blocks come back when the preview goes. A spot where something of the world stands in the way
 * shows as a red box instead, and a frame of particles marks the whole box. The client takes the blocks for solid: the
 * player walks into the preview's walls as into real ones. Server thread only; the preview lives in memory and goes on
 * `/allay cancel`, when its player leaves, or on a restart.
 */
object Previews : Listener {
  /** The most red boxes one preview shows: a client draws this many display entities without a stutter. */
  private const val BOXES = 256

  /** Chunks sent a tick at most, nearest first, so a big preview fills in over a second instead of one heavy tick. */
  private const val CHUNKS = 4

  /** Farther than this from its box, a preview shows no frame. */
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
    var states: List<String> = Materials.placed(blueprint.palette, blueprint.dataVersion, ::known)
    /** The lot's surface layer ([Construction.surface]): there and below, the lot's ground may stand on the world's. */
    val surface = Construction.surface(blueprint)
    /** Swaps so far, in order: from, to. */
    val swaps = mutableListOf<Pair<String, String>>()
    internal var datas: Array<BlockData?> = emptyArray()
    internal var shaped: Map<Int, BlockData> = emptyMap()
    /** The build's blocks by world chunk ([Showing.chunks]). */
    internal var chunks: Map<Long, IntArray> = emptyMap()
    internal val schedule = Showing.Schedule()
    /**
     * World spots ([Showing.spot]) the client sees as the build's, by chunk: their real blocks go back to it when the
     * preview goes or moves. Spots, not cells: once the build moved, its cells name other spots.
     */
    internal val shown = HashMap<Long, LongArray>()
    /** Red boxes over spots in the way, by chunk and cell. */
    internal val boxes = HashMap<Long, HashMap<Int, Entity>>()
    internal var bar: BossBar? = null
  }

  private val previews = HashMap<UUID, Preview>()
  private var ticker: BukkitTask? = null
  private var frames: BukkitTask? = null
  private var now = 0L
  private lateinit var plugin: Plugin
  private lateinit var locales: Locales

  /** Builds downloaded for previews and builds, kept in memory while in use. */
  lateinit var blueprints: Blueprints

  fun start(plugin: Plugin, locales: Locales, blueprints: Blueprints) {
    this.plugin = plugin
    this.locales = locales
    this.blueprints = blueprints
    ticker = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
      now += 2
      previews.values.toList().forEach(::tick)
    }, 2L, 2L)
    frames = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
      previews.values.toList().forEach(::frame)
      // Builds nobody previews or builds any more leave memory after `building.keep_minutes`.
      blueprints.forget()
    }, 20L, 20L)
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

  /** Something about the preview changed: where it stands, how it faces, or its blocks. Everything is shown again. */
  fun changed(preview: Preview) {
    unshow(preview)
    resolve(preview)
    tick(preview)
  }

  fun clear(owner: UUID): Boolean {
    val preview = previews.remove(owner) ?: return false
    unshow(preview)
    preview.bar?.removeAll()
    return true
  }

  fun clear(player: Player) = clear(player.uniqueId)

  @EventHandler fun onQuit(event: PlayerQuitEvent) {
    clear(event.player)
  }

  // A block the server sends the client takes the preview's place there: the chunk goes again.
  @EventHandler(priority = EventPriority.MONITOR) fun onPlace(event: BlockPlaceEvent) = touched(event.block)
  @EventHandler(priority = EventPriority.MONITOR) fun onBreak(event: BlockBreakEvent) = touched(event.block)
  @EventHandler(priority = EventPriority.MONITOR) fun onInteract(event: PlayerInteractEvent) {
    val block = event.clickedBlock ?: return
    touched(block)
    touched(block.getRelative(event.blockFace))
  }

  // A client in another world, or born again, starts with the world's blocks.
  @EventHandler fun onWorld(event: PlayerChangedWorldEvent) = forgotten(event.player)
  @EventHandler fun onRespawn(event: PlayerRespawnEvent) = forgotten(event.player)

  /** Paper says the client just got a chunk: the preview's blocks in it go again. */
  fun chunkSent(player: Player, cx: Int, cz: Int) {
    val preview = previews[player.uniqueId]?.takeIf { it.world == player.world } ?: return
    preview.schedule.touch(Showing.chunk(cx, cz))
  }

  private fun touched(block: Block) {
    val key = Showing.chunk(block.x shr 4, block.z shr 4)
    for (preview in previews.values) if (preview.world == block.world && key in preview.chunks) preview.schedule.touch(key)
  }

  private fun forgotten(player: Player) {
    val preview = previews[player.uniqueId] ?: return
    preview.schedule.forgetAll()
    preview.shown.clear()
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
    return items.firstOrNull { Material.matchMaterial(it)?.isItem != true }?.let { "$block isn't placed from an item here; name another block" }
  }

  /** Whether this game has a block by that name. */
  fun known(block: String): Boolean = Material.matchMaterial(block.removePrefix("minecraft:"))?.isBlock == true

  /**
   * The palette as block data this game reads, turned and mirrored as the preview stands: an old name renamed, a
   * state the block doesn't have dropped, and a block nobody knows left out (null).
   */
  private fun resolve(preview: Preview) {
    preview.datas = datas(preview.blueprint, preview.states, preview.placement)
    preview.shaped = shaped(preview.blueprint, preview.states, preview.placement)
    preview.chunks = Showing.chunks(preview.blueprint, preview.placement)
  }

  /** One [state] as block data, turned and mirrored as [placement] stands; null for a block this game lacks. */
  fun turned(state: String, placement: Placement): BlockData? {
    val data = data(state) ?: return null
    if (placement.mirrored) data.mirror(Mirror.FRONT_BACK)
    data.rotate(rotation(placement.facing))
    return data
  }

  /**
   * The cells of [blueprint] the game would settle otherwise once it stands (see [Shapes]), as block data turned and
   * mirrored as [placement] stands.
   */
  fun shaped(blueprint: Blueprint, states: List<String>, placement: Placement): Map<Int, BlockData> {
    val sturdy = HashMap<String, Boolean>()
    val settled = Shapes.settle(blueprint, states) { state, face ->
      sturdy.getOrPut("$state $face") { data(state)?.isFaceSturdy(BlockFace.valueOf(face.uppercase()), BlockSupport.FULL) == true }
    }
    return settled.mapNotNull { (cell, state) -> turned(state, placement)?.let { cell to it } }.toMap()
  }

  /** Whether the builder clears [block] out of a build's way: the world's own ground and plants, and trees. */
  private fun clearable(block: Block): Boolean {
    val key = block.type.key.key
    return Construction.natural(key) || Construction.log(key) || (block.blockData as? Leaves)?.isPersistent == false
  }

  /** Whether [blueprint]'s box at [placement] lies within [world]'s height. */
  fun fits(world: World, blueprint: Blueprint, placement: Placement): Boolean {
    val (low, high) = placement.bounds(blueprint)
    return low.second >= world.minHeight && high.second < world.maxHeight
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

  /**
   * Whether [block] is the world's own solid ground, which a lot's ground below its surface stands on instead of being
   * built: soil, stone, sand, sandstone, terracotta, ores, and the like, not water or a cave's air.
   */
  fun land(block: Block) = block.type.isSolid && Construction.natural(block.type.key.key)

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

  /** What the lot's ground costs where the world has ground already: the estimate leaves it out. */
  fun covered(preview: Preview): Map<String, Int> {
    val covered = HashMap<String, Int>()
    val ground = BooleanArray(preview.states.size) { Construction.ground(Construction.kind(preview.states[it])) }
    preview.blueprint.forEach { cx, cy, cz, index ->
      if (!ground[index] || cy > preview.surface) return@forEach
      val (x, y, z) = preview.placement.world(preview.blueprint, cx, cy, cz)
      if (land(preview.world.getBlockAt(x, y, z))) Materials.of(preview.states[index]).items.forEach { (item, n) -> covered.merge(item, n, Int::plus) }
    }
    return covered
  }

  /** Sends the chunks that are due, nearest first; forgets those the client let go. */
  private fun tick(preview: Preview) {
    val player = Bukkit.getPlayer(preview.owner)
    if (player == null || !player.isOnline) return run { clear(preview.owner) }
    blueprints.touch(preview.source.sha256)
    bar(player, preview)
    if (player.world != preview.world) return
    val view = minOf(player.clientViewDistance, Bukkit.getViewDistance())
    val px = player.location.blockX shr 4
    val pz = player.location.blockZ shr 4
    fun near(key: Long) = max(abs(Showing.chunkX(key) - px), abs(Showing.chunkZ(key) - pz)) <= view
    // A chunk past the view is gone from the client, the preview's blocks in it too.
    for (key in preview.schedule.chunks.filterNot(::near)) {
      preview.schedule.forget(key)
      preview.shown.remove(key)
      preview.boxes.remove(key)?.values?.forEach(Entity::remove)
    }
    val due = preview.chunks.keys.filter { near(it) && preview.world.isChunkLoaded(Showing.chunkX(it), Showing.chunkZ(it)) && preview.schedule.due(it, now) }
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
  private fun send(player: Player, preview: Preview, key: Long) {
    val bp = preview.blueprint
    val world = preview.world
    val fakes = ArrayList<BlockState>()
    val spots = ArrayList<Long>()
    val boxes = preview.boxes.getOrPut(key) { HashMap() }
    val stay = HashSet<Int>()
    for (cell in preview.chunks[key] ?: return) {
      val index = bp.at(Shell.x(cell), Shell.y(cell), Shell.z(cell))
      val data = preview.shaped[cell] ?: preview.datas[index] ?: continue
      val (x, y, z) = preview.placement.world(bp, Shell.x(cell), Shell.y(cell), Shell.z(cell))
      if (y < world.minHeight || y >= world.maxHeight) continue
      val block = world.getBlockAt(x, y, z)
      if (Shell.y(cell) <= preview.surface && Construction.ground(Construction.kind(preview.states[index])) && land(block)) continue
      if (block.blockData.matches(data)) continue
      // The lot's tufts and flowers never contend with what stands there.
      if (Construction.decor(Construction.kind(preview.states[index])) && !open(block)) continue
      if (open(block) || clearable(block)) {
        fakes += block.state.also { it.blockData = data }
        spots += Showing.spot(x, y, z)
      } else if (cell in boxes || preview.boxes.values.sumOf { it.size } < BOXES) {
        stay += cell
        boxes.getOrPut(cell) { Marks.box(block, Marks.Hue.RED).also { Marks.reveal(plugin, player, listOf(it)) } }
      }
    }
    boxes.keys.filter { it !in stay }.forEach { boxes.remove(it)?.remove() }
    val kept = spots.toHashSet()
    val back = preview.shown[key]?.filter { it !in kept }.orEmpty()
    if (back.isNotEmpty()) player.sendBlockChanges(back.map { real(world, it).state })
    if (fakes.isNotEmpty()) player.sendBlockChanges(fakes)
    preview.shown[key] = spots.toLongArray()
  }

  private fun real(world: World, spot: Long): Block = world.getBlockAt(Showing.spotX(spot), Showing.spotY(spot), Showing.spotZ(spot))

  /** Gives the player the world's real blocks back where the preview stood, and takes its boxes away. */
  private fun unshow(preview: Preview) {
    val player = Bukkit.getPlayer(preview.owner)?.takeIf { it.isOnline && it.world == preview.world }
    if (player != null) {
      val back = preview.shown.values.flatMap { spots -> spots.map { real(preview.world, it).state } }
      if (back.isNotEmpty()) player.sendBlockChanges(back)
    }
    preview.shown.clear()
    preview.boxes.values.forEach { it.values.forEach(Entity::remove) }
    preview.boxes.clear()
    preview.schedule.forgetAll()
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
    val dust = Particle.DustOptions(Color.AQUA, 1.0f)
    fun line(ax: Int, ay: Int, az: Int, bx: Int, by: Int, bz: Int) {
      val n = maxOf(abs(bx - ax), abs(by - ay), abs(bz - az))
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

  private fun distanceToBox(at: Location, low: Triple<Int, Int, Int>, high: Triple<Int, Int, Int>): Double {
    val dx = maxOf(low.first - at.x, 0.0, at.x - high.first - 1)
    val dy = maxOf(low.second - at.y, 0.0, at.y - high.second - 1)
    val dz = maxOf(low.third - at.z, 0.0, at.z - high.third - 1)
    return sqrt(dx * dx + dy * dy + dz * dz)
  }
}
