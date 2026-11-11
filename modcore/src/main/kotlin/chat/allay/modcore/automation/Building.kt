package chat.allay.modcore.automation

import chat.allay.core.Legacy
import chat.allay.core.Permissions
import chat.allay.core.Protocol
import chat.allay.core.building.Blueprint
import chat.allay.core.building.Blueprints
import chat.allay.core.building.BuildSave
import chat.allay.core.building.Construction
import chat.allay.core.building.Families
import chat.allay.core.building.Materials
import chat.allay.core.building.Placement
import chat.allay.core.building.Shapes
import chat.allay.core.building.Shell
import chat.allay.core.tasks.Agents
import chat.allay.core.tasks.Kind
import chat.allay.core.tasks.Task
import chat.allay.core.tasks.TaskLimits
import chat.allay.core.tools.Dimensions
import chat.allay.core.tools.DraftSide
import chat.allay.core.tools.ToolError
import chat.allay.core.tools.ToolOutput
import chat.allay.core.tools.string
import chat.allay.modcore.Claims
import chat.allay.modcore.Perms
import chat.allay.modcore.Texts
import chat.allay.modcore.tools.BlockAt
import chat.allay.modcore.tools.OperatorTools
import chat.allay.modcore.tools.Previews
import chat.allay.modcore.tools.Tool
import chat.allay.modcore.tools.ToolContext
import chat.allay.modcore.tools.Worlds
import chat.allay.modcore.tools.at
import chat.allay.modcore.tools.id

import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.BossEvent
import net.minecraft.world.Container
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.block.Blocks as GameBlocks
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.level.block.entity.SignTextSlot
import net.minecraft.world.level.block.state.BlockState
import java.util.UUID
import java.util.concurrent.CompletableFuture

/**
 * The builder (since 6.37): an agent of kind building that builds its player's preview from their own blocks, at its
 * tier's speed, as a player would: each block taken from the inventory, the ender chest when allowed, or the chests
 * around, and placed only where the player may build ([Claims.mayBuild]). Short of something, it waits and says what,
 * and goes on by itself once it's there; the model wakes only when the player must decide. What it placed stays when
 * it stops. Its shape never changes: only where it stands, how it faces, and what it's made of.
 */
object Building {
  private val SAVES = Json { ignoreUnknownKeys = true; encodeDefaults = true }

  /** How often a waiting builder looks again for what it lacks, in ticks. */
  private const val LOOK_TICKS = 40L

  /** How often it reads its sources again while it builds, in ticks: chests are found once in a while, not per block. */
  private const val SOURCES_TICKS = 40

  /** The most blocked cells a job remembers; past that it only counts them. */
  private const val BLOCKED = 4096

  /** How many cells of its box it looks at a tick while it clears them: looking is cheap, breaking costs the budget. */
  private const val CHECKS = 4096

  /** How many cells it looks past while it waits, for the next one that needs something: past that it just goes on. */
  private const val UPCOMING = 4096

  /** The tools a block may need for its drops, besides a hand. */
  private val TOOLS = listOf("pickaxe", "shovel", "axe", "hoe")

  /** The most blocks of one tree it fells, and how far from the log it started at. */
  private const val TREE = 512
  private const val TREE_REACH = 12

  /** Places a block without telling its neighbors, as the schematic already holds their shapes. */
  private const val FLAGS = Block.UPDATE_CLIENTS or Block.UPDATE_KNOWN_SHAPE

  class Job(
    override val task: Task,
    private val owner: UUID,
    val source: Blueprints.Source,
    val title: String,
    val level: ServerLevel,
    var placement: Placement,
    val swaps: MutableList<Pair<String, String>>,
    val skips: MutableSet<String>,
    /** Blocks a second, from the player's tier; 0 is as fast as each tick allows. */
    var blocksPerSecond: Int,
  ) : Jobs.Lasting {
    /** Null while it downloads again after a restart. */
    var blueprint: Blueprint? = null
      private set
    var states: List<String> = emptyList()
      private set
    private var datas: Array<BlockState?> = emptyArray()
    /** Cells whose block the game would settle otherwise (fences joined, stairs turned, doors whole), as placed. */
    private var shaped: Map<Int, BlockState> = emptyMap()
    /** The lot's surface layer: there and below, air of the build leaves the world's ground alone, and its ground stands on the world's. */
    var surface = -1
      private set
    /** The air the build closes in under its lot's surface: a basement, a pit. It's dug out like the air above. */
    private var interior: Set<Int> = emptySet()
    /** How far it cleared the box, top down, as cells of [clearing]. */
    var cleared = 0
    /** What it broke this tick, for the player. */
    private val loot = mutableListOf<ItemStack>()
    /** What found no room, dropped at the player's feet in a few stacks once in a while instead of one by one. */
    private val spill = mutableListOf<ItemStack>()
    private var spillAge = 0
    private var order = IntArray(0)
    private var signs: Map<Int, List<String>> = emptyMap()
    /** Player heads' skins by cell, as links to the game's skin server. */
    private var skins: Map<Int, String> = emptyMap()
    /** The next of the build's entities, once the blocks are up. */
    var entityNext = 0

    var next = 0
    var placed = 0
    val blocked = mutableListOf<Int>()
    var blockedMore = 0
    var started = false
    var paused = false
    /** What it waits for now, as the player reads it, and since when. */
    var starved: String? = null
    var starvedSince = 0L
    private var reminded = 0L
    private var budget = 0.0
    private var sources: Sources? = null
    private var sourcesAge = 0
    private var ticker: AutoCloseable? = null
    private var bar: ServerBossEvent? = null
    private var ended = false
    private var looked = 0L

    override val blocks: Collection<BlockAt> get() = emptyList()

    val total get() = order.size + (blueprint?.entities?.size ?: 0)
    val done get() = blueprint != null && next >= order.size && again.isEmpty() && entityNext >= blueprint!!.entities.size

    /** Every block it could place is up: what's left are spots in the way, for a retry once the player cleared them. */
    override val finished get() = done

    /** Blocks in its order. */
    val cells get() = order.size

    /** How far it got, entities included. */
    val progress get() = next + entityNext

    /** Takes [blueprint] on: the palette as swapped, the order, and the signs' text. Server thread. */
    fun ready(blueprint: Blueprint) {
      this.blueprint = blueprint
      var states = Materials.placed(blueprint.palette, blueprint.dataVersion, Previews::known)
      for ((from, to) in swaps) states = Families.apply(states, from, to, Previews::known).first
      this.states = states
      surface = Construction.surface(blueprint)
      interior = Construction.interior(blueprint, surface)
      resolve()
      signs = blueprint.blockEntities.filter { it.text.isNotEmpty() }.associate { Shell.pack(it.x, it.y, it.z) to it.text }
      skins = blueprint.blockEntities.mapNotNull { entity -> entity.texture?.let(Construction::skin)?.let { Shell.pack(entity.x, entity.y, entity.z) to it } }.toMap()
      run()
    }

    /** Ticks it, unless it already does or it ended. */
    private fun run() {
      if (ticker == null && !ended && blueprint != null) ticker = Jobs.every(1L) { tick() }
    }

    private fun resolve() {
      val blueprint = blueprint ?: return
      datas = Previews.datas(blueprint, states, placement)
      shape()
      val stage = IntArray(states.size) { index ->
        val data = datas[index]
        when {
          data == null -> Construction.SOLID
          data.block == GameBlocks.WATER || data.block == GameBlocks.LAVA -> Construction.LIQUID
          @Suppress("DEPRECATION") data.isSolid -> Construction.SOLID
          else -> Construction.ATTACHED
        }
      }
      order = Construction.order(blueprint, stage)
    }

    /** Swaps [from] for [to] in what's still to build; returns the shapes [to] lacks. */
    fun swap(from: String, to: String): Set<String> {
      val (swapped, missing) = Families.apply(states, from, to, Previews::known)
      states = swapped
      swaps += from to to
      recheck()
      val blueprint = blueprint ?: return missing
      datas = Previews.datas(blueprint, states, placement)
      shape()
      return missing
    }

    /** Leaves every block of [kind] out from here on. */
    fun skip(kind: String) {
      skips += kind
      recheck()
    }

    /** A waiting builder looks again on its next tick: what it waits for may be swapped or skipped now. */
    private fun recheck() {
      if (starved != null) looked = LOOK_TICKS - 1
    }

    private fun shape() {
      shaped = Previews.shaped(blueprint ?: return, states, placement)
    }

    /** What goes at [cell] of palette entry [index], as placed. */
    private fun data(cell: Int, index: Int) = shaped[cell] ?: datas[index]

    /** Cells left to build of each kind of block, for the agent. */
    fun remaining(): Map<String, Int> {
      val blueprint = blueprint ?: return emptyMap()
      val counts = HashMap<String, Int>()
      for (i in next until order.size) {
        val cell = order[i]
        val kind = Construction.kind(states[blueprint.at(Shell.x(cell), Shell.y(cell), Shell.z(cell))])
        if (kind !in skips) counts.merge(kind, 1, Int::plus)
      }
      return counts
    }

    /** What's left and nobody can get, by kind, unless swapped or skipped. */
    fun unobtainable(): Map<String, Int> = remaining().filterKeys { kind ->
      Materials.of("minecraft:$kind").unobtainable || !Previews.known(kind)
    }

    private fun player(): ServerPlayer? = level.server.playerList.getPlayer(owner)?.takeUnless { it.hasDisconnected() }

    private fun tick() {
      if (ended || blueprint == null) return
      val player = player() ?: return
      Previews.blueprints.touch(source.sha256)
      val automation = Jobs.automation
      val limits = task.limits
      val (low, high) = placement.bounds(blueprint!!)
      val far = player.level() != level || distance(player, low, high) > (limits?.pause_distance ?: automation.settings().pauseDistance)
      bar(player, far)
      if (!started || paused || far) return
      if (starved != null) return starve(player)
      val perTick = if (blocksPerSecond == 0) FAST else blocksPerSecond / 20.0
      budget = minOf(budget + perTick, maxOf(1.0, perTick, blocksPerSecond.toDouble()))
      val creative = player.isCreative
      if (!creative && (sources == null || ++sourcesAge >= SOURCES_TICKS)) {
        sources = Sources.around(player, automation.settings().radius, task.scope)
        sourcesAge = 0
      }
      try {
        if (!clear(player, creative)) return
        build(player, creative)
      } finally {
        hand(player)
      }
    }

    /** The box's volume: every cell it walks while it clears. */
    private val volume get() = blueprint?.let { it.width * it.height * it.length } ?: 0

    val clearing get() = cleared < volume

    /**
     * Clears the box of what the world grew there where the build has air, top down, so nothing falls into a hole:
     * ground above the lot, trees, plants, and a basement's or a pit's ground below it. What a player built stays.
     * True once it's through.
     */
    private fun clear(player: ServerPlayer, creative: Boolean): Boolean {
      val bp = blueprint ?: return false
      val layer = bp.width * bp.length
      var checks = 0
      while (cleared < volume && budget >= 1.0 && checks++ < CHECKS) {
        val i = cleared
        val y = bp.height - 1 - i / layer
        val z = (i % layer) / bp.width
        val x = i % bp.width
        // Under the lot's surface the world's ground stays, but for the air the build closes in: a basement, a pit.
        if (!bp.filled(x, y, z) && (y > surface || Shell.pack(x, y, z) in interior)) {
          val (wx, wy, wz) = placement.world(bp, x, y, z)
          if (level.chunkSource.getChunkNow(wx shr 4, wz shr 4) == null) return false
          val block = level.at(wx, wy, wz)
          if (!block.state.isAir && !block.liquid) budget -= dig(player, block, creative)
        }
        cleared++
      }
      return cleared >= volume
    }

    /**
     * Puts what it broke this tick into the inventory, then its chests, in full stacks; what finds no room falls at the
     * player's feet every couple of seconds, in as few stacks as it makes.
     */
    private fun hand(player: ServerPlayer) {
      if (loot.isNotEmpty()) {
        val chests = sources?.list.orEmpty().filter { !it.place.contains('#') }
        spill += stacks(loot).mapNotNull { item ->
          var rest: ItemStack? = Slots.add(player, item)
          for (chest in chests) rest = rest?.let(chest::add) ?: break
          rest
        }
        loot.clear()
      }
      if (spill.isNotEmpty() && ++spillAge >= SOURCES_TICKS) drop(player)
    }

    /** Drops what found no room, merged, where the player stands, or at the build when they're gone. */
    private fun drop(player: ServerPlayer?) {
      spillAge = 0
      if (spill.isEmpty()) return
      val here = player?.takeIf { present(it) && it.level() == level }
      val at = if (here != null) null else blueprint?.let { bp -> placement.world(bp, 0, 0, 0).let { (x, y, z) -> level.at(x, y, z) } }
      if (here == null && at == null) return
      stacks(spill).forEach { if (here != null) Slots.drop(here, it) else Slots.drop(at!!, it) }
      spill.clear()
    }

    /** [items] merged into as few full stacks as they make. */
    private fun stacks(items: List<ItemStack>): List<ItemStack> {
      val merged = mutableListOf<ItemStack>()
      for (item in items) {
        if (item.isEmpty) continue
        var left = item.count
        for (stack in merged) {
          if (left == 0) break
          if (!Slots.similar(stack, item) || stack.count >= stack.maxStackSize) continue
          val moved = minOf(left, stack.maxStackSize - stack.count)
          stack.grow(moved)
          left -= moved
        }
        while (left > 0) {
          val moved = minOf(left, item.maxStackSize.coerceAtLeast(1))
          merged += item.copyWithCount(moved)
          left -= moved
        }
      }
      return merged
    }

    /**
     * Breaks [block] for the build if the world grew it: ground, stone, plants, and whole trees, where [player] may
     * build; what drops goes to the player. How many blocks it broke, 0 when it stays: each one costs the budget, as
     * placing does.
     */
    private fun dig(player: ServerPlayer, block: BlockAt, creative: Boolean): Int {
      val key = block.key
      val state = block.state
      return when {
        Construction.log(key) -> fell(player, block.pos, creative)
        state.`is`(BlockTags.LEAVES) -> if (Previews.decaying(state) && crack(player, block.pos, creative)) 1 else 0
        Construction.natural(key) -> if (crack(player, block.pos, creative)) 1 else 0
        else -> 0
      }
    }

    /**
     * Breaks the block at [pos] as [player] would. It drops what a hand gets, or, for a block that needs a tool, what
     * the worst fitting tool of the sources gets, which wears for it; without one, nothing, as by hand. A container's
     * contents go to the player whatever the mode; in creative nothing else drops, as in the game.
     */
    private fun crack(player: ServerPlayer, pos: BlockPos, creative: Boolean): Boolean {
      if (!Claims.mayBuild(player, level, pos)) return false
      val state = level.getBlockState(pos)
      val entity = level.getBlockEntity(pos)
      if (!creative) loot += drops(player, pos, state, entity)
      (entity as? Container)?.let { container ->
        loot += Slots.read(container).filterNotNull()
        container.clearContent()
      }
      level.setBlock(pos, GameBlocks.AIR.defaultBlockState(), FLAGS)
      return true
    }

    /** What the block at [pos] drops as the builder breaks it: see [crack]. */
    private fun drops(player: ServerPlayer, pos: BlockPos, state: BlockState, entity: BlockEntity?): List<ItemStack> {
      if (!state.requiresCorrectToolForDrops()) return Block.getDrops(state, level, pos, entity, player, ItemStack.EMPTY)
      val (source, stack) = tool(TOOLS) { ItemStack(it.item).isCorrectToolForDrops(state) } ?: return emptyList()
      // A plain tool of its kind: its enchantments stay the player's, so silk touch takes no grass and fortune doubles nothing.
      val drops = Block.getDrops(state, level, pos, entity, player, ItemStack(stack.item))
      wear(source, stack)
      return drops
    }

    private fun leafy(state: BlockState) = state.`is`(BlockTags.LEAVES) && state.hasProperty(LeavesBlock.PERSISTENT) && !state.getValue(LeavesBlock.PERSISTENT) &&
      state.getValue(LeavesBlock.DISTANCE) <= 4

    /**
     * Fells the tree [log] belongs to: its logs, and the leaves around them that the game lets decay. A log without
     * such leaves is a player's and stays; so does anything of the build already standing.
     */
    private fun fell(player: ServerPlayer, log: BlockPos, creative: Boolean): Int {
      val logs = linkedSetOf(log)
      val leaves = linkedSetOf<BlockPos>()
      val queue = ArrayDeque(listOf(log))
      while (queue.isNotEmpty() && logs.size + leaves.size < TREE) {
        val at = queue.removeFirst()
        val fromLog = Construction.log(Worlds.key(BuiltInRegistries.BLOCK.getKey(level.getBlockState(at).block)))
        for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
          val next = at.offset(dx, dy, dz)
          if (next in logs || next in leaves || abs(next.x - log.x) > TREE_REACH || abs(next.z - log.z) > TREE_REACH) continue
          if (built(next)) continue
          val state = level.getBlockState(next)
          when {
            fromLog && Construction.log(Worlds.key(BuiltInRegistries.BLOCK.getKey(state.block))) -> { logs += next; queue += next }
            // Leaves only as far as the logs reach, so a neighbour's crown stays.
            leafy(state) -> { leaves += next; queue += next }
          }
        }
      }
      if (leaves.isEmpty()) return 0
      if (!crack(player, log, creative)) return 0
      // Top down, so nothing of it is left hanging.
      return 1 + (logs - log + leaves).sortedByDescending { it.y }.count { crack(player, it, creative) }
    }

    /** Whether [pos] is part of the build already standing: the builder never fells its own. */
    private fun built(pos: BlockPos): Boolean {
      val bp = blueprint ?: return false
      val (x, y, z) = placement.local(bp, pos.x, pos.y, pos.z) ?: return false
      val index = bp.at(x, y, z)
      if (bp.empty[index]) return false
      return data(Shell.pack(x, y, z), index) == level.getBlockState(pos)
    }

    /**
     * Whether [cell]'s block stands there already: as the build has it, a mob head as Steve's, or the lot's ground on
     * the world's own ground, which stays as the world has it (dirt for stone), but only at the lot's surface and below.
     */
    private fun there(cell: Int, state: String, data: BlockState, block: BlockAt): Boolean {
      val now = block.state
      if (now == data) return true
      if (Shell.y(cell) <= surface && Construction.ground(Construction.kind(state)) && Previews.land(block)) return true
      return Materials.steve(state)?.let { Previews.turned(it, placement) } == now
    }

    /** Places the build's blocks, then its entities, as far as this tick's budget goes. */
    private fun build(player: ServerPlayer, creative: Boolean) {
      while (budget >= 1.0 && (again.isNotEmpty() || next < order.size)) {
        val retrying = again.isNotEmpty()
        val cell = if (retrying) again.first() else order[next]
        // Moves past this cell, whichever queue it came from.
        val step = { if (retrying) again.removeFirst() else next++ }
        val bp = blueprint!!
        val index = bp.at(Shell.x(cell), Shell.y(cell), Shell.z(cell))
        val state = states[index]
        val data = data(cell, index)
        if (data == null || Construction.kind(state) in skips) {
          step()
          continue
        }
        val (x, y, z) = placement.world(bp, Shell.x(cell), Shell.y(cell), Shell.z(cell))
        // Past the world's height nothing stands.
        if (y < level.minY || y > level.maxY) {
          block(cell)
          step()
          continue
        }
        // An unloaded chunk waits for someone to come near, as the game does.
        if (level.chunkSource.getChunkNow(x shr 4, z shr 4) == null) break
        val block = level.at(x, y, z)
        val steve = Materials.steve(state)?.let { Previews.turned(it, placement) }
        // The lot's tufts and flowers never contend with what stands there: that spot is passed by.
        if (Construction.decor(Construction.kind(state)) && !Previews.open(block)) {
          step()
          continue
        }
        if (there(cell, state, data, block)) {
          step()
          placed++
          continue
        }
        // The world's own ground, plants, and trees make way; what a player built stays in the way.
        if (!Previews.open(block)) {
          val broken = dig(player, block, creative)
          if (broken == 0) {
            block(cell)
            step()
            continue
          }
          budget -= broken
        }
        var cost = Materials.of(state)
        if (cost.unobtainable) {
          step()
          continue
        }
        var placing = data
        // No such mob head at hand: Steve's goes up for free instead.
        if (!creative && steve != null && lacking(player, cost) != null) {
          placing = steve
          cost = Materials.Cost(free = true)
        }
        if (!creative) {
          val lacking = lacking(player, cost)
          if (lacking != null) return starve(player, lacking)
        }
        if (place(player, block.pos, placing, cell)) {
          if (!creative) pay(player, cost)
          placed++
        } else {
          block(cell)
        }
        step()
        budget -= 1.0
      }
      if (next < order.size || again.isNotEmpty()) return
      if (!entities(player, creative)) return
      finish()
    }

    /**
     * The build's frames, paintings, and armor stands, once every block is up: empty, each for its item, only where the
     * player may build. True when they're all there.
     */
    private fun entities(player: ServerPlayer, creative: Boolean): Boolean {
      val bp = blueprint ?: return false
      while (entityNext < bp.entities.size) {
        if (budget < 1.0) return false
        val entity = bp.entities[entityNext]
        val item = Materials.item(entity)
        if (item == null || item in skips) {
          entityNext++
          continue
        }
        val (x, y, z) = Construction.hangs(entity, bp, placement)
        if (y < level.minY || y > level.maxY) {
          blockedMore++
          entityNext++
          continue
        }
        if (level.chunkSource.getChunkNow(x shr 4, z shr 4) == null) return false
        val cost = Materials.Cost(mapOf(item to 1))
        if (!creative) {
          val lacking = lacking(player, cost)
          if (lacking != null) {
            starve(player, lacking)
            return false
          }
        }
        when (Decorations.place(player, level, BlockPos(x, y, z), entity, bp, placement)) {
          Decorations.Result.PLACED -> {
            if (!creative) pay(player, cost)
            placed++
          }
          Decorations.Result.THERE -> placed++
          Decorations.Result.REFUSED -> blockedMore++
        }
        entityNext++
        budget -= 1.0
      }
      return true
    }

    /** Starts waiting for [lacking], as the player reads it. */
    private fun starve(player: ServerPlayer, lacking: String) {
      val automation = Jobs.automation
      starved = lacking
      starvedSince = System.currentTimeMillis()
      reminded = starvedSince
      automation.say(player, task, automation.text(task, "building.waiting", "items" to lacking))
      automation.changed(task)
    }

    /**
     * What the next thing to build costs: the next cell's block the builder would place, or once they're up, the next
     * entity's item. Skipped kinds, what nobody gets, and what stands there already are passed by, as the builder
     * passes them; null when nothing near needs anything, and it goes on.
     */
    private fun upcoming(): Materials.Cost? {
      val bp = blueprint ?: return null
      var looked = 0
      for (cell in again.asSequence() + (next until order.size).asSequence().map { order[it] }) {
        if (++looked > UPCOMING) return null
        val index = bp.at(Shell.x(cell), Shell.y(cell), Shell.z(cell))
        val state = states[index]
        val data = data(cell, index) ?: continue
        if (Construction.kind(state) in skips) continue
        val cost = Materials.of(state)
        if (cost.unobtainable) continue
        val (x, y, z) = placement.world(bp, Shell.x(cell), Shell.y(cell), Shell.z(cell))
        if (y >= level.minY && y <= level.maxY && level.chunkSource.getChunkNow(x shr 4, z shr 4) != null) {
          val block = level.at(x, y, z)
          if (there(cell, state, data, block) || Construction.decor(Construction.kind(state)) && !Previews.open(block)) continue
        }
        // A mob head waits for nothing: Steve's stands in.
        return if (Materials.steve(state) != null) Materials.Cost.NOTHING else cost
      }
      for (i in entityNext until bp.entities.size) {
        val item = Materials.item(bp.entities[i]) ?: continue
        if (item !in skips) return Materials.Cost(mapOf(item to 1))
      }
      return null
    }

    /** Waits for what it lacks: it looks again every two seconds, reminds now and then, and gives up after its hours. */
    private fun starve(player: ServerPlayer) {
      if (++looked % LOOK_TICKS != 0L) return
      val automation = Jobs.automation
      val now = System.currentTimeMillis()
      if (now - starvedSince > automation.agents.parkHours(task) * 3_600_000L) {
        return automation.end(task, automation.text(task, "automation.park_expired", "hours" to automation.agents.parkHours(task)))
      }
      if (now - reminded >= automation.settings().remindMinutes * 60_000L) {
        reminded = now
        automation.say(player, task, automation.text(task, "building.still_waiting", "items" to starved))
      }
      sources = Sources.around(player, automation.settings().radius, task.scope)
      sourcesAge = 0
      val cost = upcoming()
      if (cost != null && !player.isCreative && lacking(player, cost) != null) return
      starved = null
      starvedSince = 0L
      automation.say(player, task, automation.text(task, "building.going_on"))
      automation.changed(task)
    }

    /** What the player lacks for [cost], as they read it; null when it's all there. */
    private fun lacking(player: ServerPlayer, cost: Materials.Cost): String? {
      val sources = sources ?: return null
      val automation = Jobs.automation
      for ((item, count) in cost.items) {
        if (sources.count { Crafting.key(it) == item } < count) return automation.itemName(player, item) + " ×${rest(item)}"
      }
      if (cost.water && sources.count { it.`is`(Items.WATER_BUCKET) } < 1) return automation.itemName(player, "water_bucket")
      cost.tool?.let { kind -> if (tool(listOf(kind)) == null) return automation.text(task, "building.tool_$kind") }
      return null
    }

    /** How many of [item] the rest of the build takes, for the player's line. */
    private fun rest(item: String): Int {
      val bp = blueprint ?: return 0
      var count = 0
      for (i in next until order.size) {
        val cell = order[i]
        val state = states[bp.at(Shell.x(cell), Shell.y(cell), Shell.z(cell))]
        if (Construction.kind(state) in skips) continue
        count += Materials.of(state).items[item] ?: 0
      }
      return count
    }

    /** Takes what [cost] says from the sources: items, a tool's wear; a lava bucket's bucket comes back. */
    private fun pay(player: ServerPlayer, cost: Materials.Cost) {
      val sources = sources ?: return
      for ((item, count) in cost.items) {
        var left = count
        for (source in sources.list) {
          if (left == 0) break
          left -= source.take({ Crafting.key(it) == item }, left).sumOf { it.count }
        }
        if (item == "lava_bucket") give(player, listOf(ItemStack(Items.BUCKET)))
      }
      cost.tool?.let { kind -> tool(listOf(kind))?.let { (source, stack) -> wear(source, stack) } }
    }

    /** The tool of [kinds] that [fits] to wear next: the worst tier first, then the most worn. */
    private fun tool(kinds: List<String>, fits: (ItemStack) -> Boolean = { true }): Pair<Sources.Source, ItemStack>? {
      val sources = sources ?: return null
      return sources.list.flatMap { source -> source.slots().filterNotNull().filterNot { it.isEmpty }.map { source to it } }
        .mapNotNull { (source, stack) -> kinds.firstNotNullOfOrNull { Construction.wearOrder(Worlds.key(BuiltInRegistries.ITEM.getKey(stack.item)), it) }?.let { Triple(source, stack, it) } }
        .filter { fits(it.second) }
        .sortedWith(compareBy<Triple<Sources.Source, ItemStack, Int>> { it.third }.thenByDescending { it.second.damageValue })
        .firstOrNull()?.let { it.first to it.second }
    }

    /** One use of [stack] from [source], as the game wears a tool: Unbreaking spares some, an unbreakable one never wears, a worn-out one breaks. */
    private fun wear(source: Sources.Source, stack: ItemStack) {
      if (!stack.isDamageableItem) return
      val unbreaking = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
        .get(Enchantments.UNBREAKING).map { EnchantmentHelper.getItemEnchantmentLevel(it, stack) }.orElse(0)
      if (!Construction.wears(unbreaking)) return
      val taken = source.take({ ItemStack.isSameItemSameComponents(it, stack) }, 1).firstOrNull() ?: return
      taken.damageValue = taken.damageValue + 1
      if (taken.damageValue >= taken.maxDamage) return
      source.add(taken)?.let { give(player() ?: return, listOf(it)) }
    }

    /** Places [data] at [pos] where [player] may build, without telling the neighbors; false when a claim refuses it. */
    private fun place(player: ServerPlayer, pos: BlockPos, data: BlockState, cell: Int): Boolean {
      if (!Claims.mayBuild(player, level, pos)) return false
      // Leaves of a build stay: the player placed them, as the game marks leaves a player places.
      val placing = if (data.hasProperty(LeavesBlock.PERSISTENT)) data.setValue(LeavesBlock.PERSISTENT, true) else data
      if (!level.setBlock(pos, placing, FLAGS)) return false
      skins[cell]?.let { skin -> Decorations.skin(level, pos, skin) }
      signs[cell]?.let { lines ->
        val sign = level.getBlockEntity(pos) as? SignBlockEntity ?: return@let
        val text = sign.getText(SignTextSlot.FRONT).asMutable()
        lines.take(4).forEachIndexed { i, line -> text.setLine(i, Component.literal(line.take(90))) }
        sign.setText(text.asImmutable(), SignTextSlot.FRONT)
        level.sendBlockUpdated(pos, placing, placing, Block.UPDATE_CLIENTS)
      }
      return true
    }

    private fun block(cell: Int) {
      if (blocked.size < BLOCKED) blocked += cell else blockedMore++
    }

    /** Spots something stood in the way of, as the world has them. */
    fun blockedSpots(limit: Int): List<Triple<Int, Int, Int>> {
      val bp = blueprint ?: return emptyList()
      return blocked.take(limit).map { placement.world(bp, Shell.x(it), Shell.y(it), Shell.z(it)) }
    }

    /** Spots in the way to try again before it goes on from [next]: the player cleared them. */
    val again = ArrayDeque<Int>()

    /** Goes over the spots that were in the way again. */
    fun retry() {
      again.addAll(blocked)
      blocked.clear()
      blockedMore = 0
      run()
    }

    /** The cell at [i] of the order. */
    fun cellAt(i: Int) = order[i]

    private fun finish() {
      ticker?.close()
      ticker = null
      val skipped = skips.joinToString().ifEmpty { "nothing" }
      val blockedNote = if (blocked.isEmpty()) "" else "; ${blocked.size + blockedMore} spots were in the way or a claim refused them, first at " +
        blockedSpots(5).joinToString("; ") { "${it.first} ${it.second} ${it.third}" } + " (construct retry builds them once the player cleared them)"
      Jobs.automation.post(task, "[job] the build is done: $placed blocks placed, skipped kinds: $skipped$blockedNote")
      drop(player())
      bar?.removeAllPlayers()
      bar = null
    }

    private fun bar(player: ServerPlayer, far: Boolean) {
      val automation = Jobs.automation
      val bar = bar ?: ServerBossEvent(UUID.randomUUID(), Component.empty(), BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.NOTCHED_10).also { bar = it }
      // A respawn makes a new ServerPlayer: the bar follows the one that plays now.
      bar.players.filter { it !== player }.forEach(bar::removePlayer)
      if (player !in bar.players) bar.addPlayer(player)
      val total = this.total.coerceAtLeast(1)
      bar.progress = (progress.toFloat() / total).coerceIn(0f, 1f)
      val key = when {
        !started -> "building.bar_ready"
        paused -> "building.bar_paused"
        far -> "building.bar_far"
        starved != null -> "building.bar_waiting"
        clearing -> "building.bar_clearing"
        else -> "building.bar"
      }
      bar.color = if (starved != null || far || paused) BossEvent.BossBarColor.YELLOW else BossEvent.BossBarColor.GREEN
      bar.name = Texts.legacy(Legacy.color(automation.text(task, key, "title" to title, "done" to progress, "total" to total, "percent" to (progress * 100L / total).toInt(), "items" to (starved ?: ""))))
    }

    override fun cancel() {
      ended = true
      ticker?.close()
      ticker = null
      drop(player())
      bar?.removeAllPlayers()
      bar = null
      Jobs.remove(this)
    }

    override fun snapshot(): JsonObject = SAVES.encodeToJsonElement(BuildSave.serializer(), BuildSave(
      id = source.id, url = source.url, sha256 = source.sha256, bytes = source.bytes, title = title, world = Worlds.name(level),
      x = placement.corner.first, y = placement.corner.second, z = placement.corner.third, facing = placement.facing.name.lowercase(),
      mirrored = placement.mirrored, swaps = swaps.map { listOf(it.first, it.second) }, skips = skips.toList(), next = next, placed = placed, entity_next = entityNext, cleared = cleared,
      blocked = blocked.toList(), blocked_more = blockedMore, again = again.toList(), started = started, paused = paused, blocks_per_second = blocksPerSecond,
      starved_since = starvedSince, waiting = starved,
    )).jsonObject

    override fun describe(): String {
      val state = when {
        blueprint == null -> "downloading the build again"
        done -> "done, every block it could place is up" + if (blocked.isEmpty()) "" else "; ${blocked.size + blockedMore} spots in the way wait for construct retry once the player cleared them, and a new build ends this one"
        !started -> "ready, waiting for construct start"
        paused -> "paused by construct pause"
        starved != null -> "waiting for $starved"
        clearing -> "clearing the site of ground, plants, and trees"
        else -> "building"
      }
      return "building «$title»: $next of ${order.size} cells done ($placed placed), $state" +
        (if (skips.isEmpty()) "" else "; skipping ${skips.joinToString()}") + (if (blocked.isEmpty()) "" else "; ${blocked.size + blockedMore} spots in the way")
    }
  }

  /** Blocks a tick at speed 0, as fast as the server takes them without a stutter. */
  private const val FAST = 50.0

  private fun distance(player: ServerPlayer, low: Triple<Int, Int, Int>, high: Triple<Int, Int, Int>): Double {
    val at = player.position()
    val dx = maxOf(low.first - at.x, 0.0, at.x - high.first - 1)
    val dy = maxOf(low.second - at.y, 0.0, at.y - high.second - 1)
    val dz = maxOf(low.third - at.z, 0.0, at.z - high.third - 1)
    return sqrt(dx * dx + dy * dy + dz * dz)
  }

  /** The builder's site for a new building agent: the player's preview becomes its job, waiting for the agent's go. */
  fun site(task: Task, player: ServerPlayer): Agents.Site {
    val preview = Previews.of(player) ?: throw ToolError("the player has no build in preview: preview one with build first, then start the builder")
    val config = Jobs.automation.building()
    val limits = config.tiers.of(OperatorTools.operator(player)) { Perms.check(player, Permissions.build(it)) }
    val job = Job(task, player.uuid, preview.source, preview.title, preview.level, preview.placement, preview.swaps.toMutableList(), mutableSetOf(), limits.blocksPerSecond)
    job.ready(preview.blueprint)
    Jobs.add(job)
    // The preview goes once the job holds the build: a failure above leaves it for the next try.
    Previews.clear(player)
    val (low, high) = preview.placement.bounds(preview.blueprint)
    val anchor = Protocol.Position((low.first + high.first) / 2, low.second, (low.third + high.third) / 2, Dimensions.id(preview.level), Worlds.name(preview.level))
    return Agents.Site(anchor, job, TaskLimits(limits.pauseDistance, limits.parkHours))
  }

  /** A build job from its snapshot: it downloads the build again off the server thread, then goes on where it stood. */
  fun restore(task: Task, player: ServerPlayer, saved: JsonObject): Jobs.Lasting? {
    val save = runCatching { SAVES.decodeFromJsonElement(BuildSave.serializer(), saved) }.getOrNull() ?: return null
    val server = player.level().server
    val level = server.allLevels.firstOrNull { Worlds.name(it) == save.world } ?: return null
    val facing = DraftSide.entries.firstOrNull { it.name.equals(save.facing, ignoreCase = true) } ?: return null
    val source = Blueprints.Source(save.id, save.url, save.sha256, save.bytes)
    val job = Job(task, player.uuid, source, save.title, level, Placement(Triple(save.x, save.y, save.z), facing, save.mirrored),
      save.swaps.mapNotNull { if (it.size == 2) it[0] to it[1] else null }.toMutableList(), save.skips.toMutableSet(), save.blocks_per_second)
    job.next = save.next
    job.cleared = save.cleared
    job.placed = save.placed
    job.entityNext = save.entity_next
    job.blocked += save.blocked
    job.blockedMore = save.blocked_more
    job.again.addAll(save.again)
    job.started = save.started
    job.paused = save.paused
    job.starvedSince = save.starved_since
    if (save.starved_since > 0) job.starved = save.waiting ?: "…"
    Jobs.add(job)
    CompletableFuture.supplyAsync { runCatching { Previews.blueprints.load(source) } }.thenAccept { blueprint ->
      server.execute {
        blueprint.onSuccess(job::ready).onFailure { Jobs.automation.post(task, "[job] the build couldn't be downloaded again (${it.message}); tell the player and finish") }
      }
    }
    return job
  }

  /** The builder's own tool: what's left, go, pause, swap or skip a kind of block, and the spots in the way again. */
  val tool = object : Tool {
    override val name = "construct"

    override fun run(context: ToolContext, args: JsonObject): ToolOutput {
      val player = context.requirePlayer()
      val task = context.requireTask()
      val automation = context.features.automation
      automation.require(player, task, Kind.BUILDING)
      val job = automation.agent(task).job as? Job ?: throw ToolError("this builder has no build; finish and tell the player")
      if (job.blueprint == null) throw ToolError("the build is still downloading; wait a few seconds")
      val lang = context.lang
      return when (val action = args.string("action")?.lowercase()) {
        "status" -> ToolOutput(status(job, player), lang.text("summary.construct_status", "done" to job.progress, "total" to job.total))
        "start" -> {
          val unobtainable = job.unobtainable()
          if (unobtainable.isNotEmpty()) throw ToolError("no survival player gets ${unobtainable.entries.joinToString { "${it.key} (${it.value})" }}: ask the player what to build them with (swap) or to leave them out (skip), then start")
          job.started = true
          job.paused = false
          automation.changed(task)
          ToolOutput(buildJsonObject { put("started", true); put("note", "it builds by itself now and says itself what it waits for; you wake when it's done or the player writes. End this wake with wait, without seconds") }, lang.text("summary.construct_started"))
        }
        "pause" -> {
          job.paused = true
          automation.changed(task)
          ToolOutput(buildJsonObject { put("paused", true) }, lang.text("summary.construct_paused"))
        }
        "resume" -> {
          job.paused = false
          job.started = true
          automation.changed(task)
          ToolOutput(buildJsonObject { put("resumed", true) }, lang.text("summary.construct_resumed"))
        }
        "swap" -> {
          val from = args.string("from")?.lowercase()?.removePrefix("minecraft:") ?: throw ToolError("swap needs from: the block in the build, like cobblestone")
          val to = args.string("to")?.lowercase()?.removePrefix("minecraft:") ?: throw ToolError("swap needs to: the block to build with instead")
          Previews.refusal(to)?.let { throw ToolError(it) }
          if (!Families.has(job.states, from)) throw ToolError("the build has no $from; status names what's left")
          val missing = job.swap(from, to)
          automation.changed(task)
          ToolOutput(buildJsonObject {
            put("swapped", "$from → $to")
            if (missing.isNotEmpty()) put("missing", buildJsonArray { missing.forEach { add(it) } })
          }, lang.text("summary.build_swapped", "from" to from, "to" to to))
        }
        "skip" -> {
          val block = args.string("block")?.lowercase()?.removePrefix("minecraft:") ?: throw ToolError("skip needs block: the kind to leave out, like glass_pane")
          val entities = job.blueprint!!.entities.drop(job.entityNext).any { Materials.item(it) == block }
          if (block !in job.remaining() && !entities) throw ToolError("nothing of $block is left to build")
          job.skip(block)
          automation.changed(task)
          ToolOutput(buildJsonObject { put("skipped", block) }, lang.text("summary.construct_skipped", "block" to block))
        }
        "retry" -> {
          val count = job.blocked.size + job.blockedMore
          job.retry()
          automation.changed(task)
          ToolOutput(buildJsonObject { put("again", count) }, lang.text("summary.construct_retry", "count" to count))
        }
        else -> throw ToolError("action is status, start, pause, resume, swap, skip, or retry, not $action")
      }
    }
  }

  private fun status(job: Job, player: ServerPlayer) = buildJsonObject {
    put("title", job.title)
    put("done", job.progress)
    put("total", job.total)
    put("placed", job.placed)
    put("state", when {
      !job.started -> "ready"
      job.paused -> "paused"
      job.starved != null -> "waiting"
      job.done -> "done"
      else -> "building"
    })
    job.starved?.let { put("waiting_for", it) }
    val unobtainable = job.unobtainable()
    if (unobtainable.isNotEmpty()) put("unobtainable", buildJsonObject { unobtainable.forEach { (k, v) -> put(k, v) } })
    // What's left against what the sources hold, the biggest first: the player brings what's short.
    val sources = if (player.isCreative) null else Sources.around(player, Jobs.automation.settings().radius, job.task.scope)
    val needs = HashMap<String, Int>()
    val bp = job.blueprint!!
    bp.entities.drop(job.entityNext).forEach { entity -> Materials.item(entity)?.takeIf { it !in job.skips }?.let { needs.merge(it, 1, Int::plus) } }
    for (i in job.next until job.cells) {
      // Counted per cell: a double slab takes two.
      val cell = job.cellAt(i)
      val state = job.states[bp.at(Shell.x(cell), Shell.y(cell), Shell.z(cell))]
      if (Construction.kind(state) in job.skips) continue
      val kind = Construction.kind(state)
      if (Shell.y(cell) <= job.surface && Construction.ground(kind) || Construction.decor(kind)) {
        val (x, y, z) = job.placement.world(bp, Shell.x(cell), Shell.y(cell), Shell.z(cell))
        val there = job.level.at(x, y, z)
        // The lot's ground on the world's own costs nothing, as the builder sees it; nor does its decor where something stands.
        if (Construction.decor(kind) && !Previews.open(there)) continue
        if (Shell.y(cell) <= job.surface && Construction.ground(kind) && Previews.land(there)) continue
      }
      Materials.of(state).items.forEach { (item, n) -> needs.merge(item, n, Int::plus) }
    }
    if (sources == null) put("creative", "nothing is taken")
    else put("short", buildJsonArray {
      needs.entries.sortedByDescending { it.value }.forEach { (item, need) ->
        val have = sources.count { Crafting.key(it) == item }
        if (have < need) add(buildJsonObject { put("item", item); put("need", need); put("have", have) })
      }
    })
    if (job.skips.isNotEmpty()) put("skipped", buildJsonArray { job.skips.forEach { add(it) } })
    if (job.blocked.isNotEmpty()) {
      put("in_the_way", job.blocked.size + job.blockedMore)
      put("first_in_the_way", buildJsonArray { job.blockedSpots(5).forEach { (x, y, z) -> add("$x $y $z") } })
    }
  }
}
