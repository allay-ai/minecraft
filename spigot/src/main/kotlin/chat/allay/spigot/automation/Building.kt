package chat.allay.spigot.automation

import chat.allay.core.Permissions
import chat.allay.core.Protocol
import chat.allay.core.building.Blueprint
import chat.allay.core.building.Blueprints
import chat.allay.core.building.BuildSave
import chat.allay.core.building.Construction
import chat.allay.core.building.Families
import chat.allay.core.building.Materials
import chat.allay.core.building.Placement
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
import chat.allay.spigot.of
import chat.allay.spigot.tools.Previews
import chat.allay.spigot.tools.id
import chat.allay.spigot.tools.Tool
import chat.allay.spigot.tools.ToolContext

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Sign
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.type.Leaves
import org.bukkit.block.sign.Side
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.UUID

/**
 * The builder (since 6.37): an agent of kind building that builds its player's preview from their own blocks, at its
 * tier's speed, as a player would: each block taken from the inventory, the ender chest when allowed, or the chests
 * around, and placed through a [BlockPlaceEvent], so claims refuse it and CoreProtect logs it. Short of something, it
 * waits and says what, and goes on by itself once it's there; the model wakes only when the player must decide. What
 * it placed stays when it stops. Its shape never changes: only where it stands, how it faces, and what it's made of.
 */
object Building {
  lateinit var plugin: Plugin

  private val SAVES = Json { ignoreUnknownKeys = true; encodeDefaults = true }

  /** How often a waiting builder looks again for what it lacks, in ticks. */
  private const val LOOK_TICKS = 40L

  /** How often it reads its sources again while it builds, in ticks: chests are found once in a while, not per block. */
  private const val SOURCES_TICKS = 40

  /** The most blocked cells a job remembers; past that it only counts them. */
  private const val BLOCKED = 4096

  class Job(
    override val task: Task,
    private val owner: UUID,
    val source: Blueprints.Source,
    val title: String,
    val world: World,
    var placement: Placement,
    val swaps: MutableList<Pair<String, String>>,
    val skips: MutableSet<String>,
    private val blocksPerSecond: Int,
  ) : Jobs.Lasting {
    /** Null while it downloads again after a restart. */
    var blueprint: Blueprint? = null
      private set
    var states: List<String> = emptyList()
      private set
    private var datas: Array<BlockData?> = emptyArray()
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
    private var ticker: BukkitTask? = null
    private var bar: BossBar? = null
    private var ended = false
    private var looked = 0L

    override val blocks: Collection<Block> get() = emptyList()

    val total get() = order.size + (blueprint?.entities?.size ?: 0)
    val done get() = blueprint != null && next >= order.size && again.isEmpty() && entityNext >= blueprint!!.entities.size

    /** Blocks in its order. */
    val cells get() = order.size

    /** How far it got, entities included. */
    val progress get() = next + entityNext

    /** Takes [blueprint] on: the palette as swapped, the order, and the signs' text. Server thread. */
    fun ready(blueprint: Blueprint) {
      this.blueprint = blueprint
      var states = Materials.placed(blueprint.palette, Previews::known)
      for ((from, to) in swaps) states = Families.apply(states, from, to, Previews::known).first
      this.states = states
      resolve()
      signs = blueprint.blockEntities.filter { it.text.isNotEmpty() }.associate { Shell.pack(it.x, it.y, it.z) to it.text }
      skins = blueprint.blockEntities.mapNotNull { entity -> entity.texture?.let(Construction::skin)?.let { Shell.pack(entity.x, entity.y, entity.z) to it } }.toMap()
      run()
    }

    /** Ticks it, unless it already does or it ended. */
    private fun run() {
      if (ticker == null && !ended && blueprint != null) ticker = plugin.server.scheduler.runTaskTimer(plugin, Runnable { tick() }, 1L, 1L)
    }

    private fun resolve() {
      val blueprint = blueprint ?: return
      datas = Previews.datas(blueprint, states, placement)
      val stage = IntArray(states.size) { index ->
        val data = datas[index]
        when {
          data == null -> Construction.SOLID
          data.material == Material.WATER || data.material == Material.LAVA -> Construction.LIQUID
          data.material.isSolid -> Construction.SOLID
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
      val blueprint = blueprint ?: return missing
      datas = Previews.datas(blueprint, states, placement)
      return missing
    }

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

    private fun player(): Player? = Bukkit.getPlayer(owner)?.takeIf { it.isOnline }

    private fun tick() {
      if (ended || blueprint == null) return
      val player = player() ?: return
      Previews.blueprints.touch(source.sha256)
      val automation = Jobs.automation
      val limits = task.limits
      val (low, high) = placement.bounds(blueprint!!)
      val far = player.world != world || distance(player, low, high) > (limits?.pause_distance ?: automation.settings().pauseDistance)
      bar(player, far)
      if (!started || paused || far) return
      if (starved != null) return starve(player)
      // 0 is as fast as a tick allows: 50 blocks a tick, which a server takes without a stutter.
      val perSecond = if (blocksPerSecond == 0) 1000 else blocksPerSecond
      budget = minOf(budget + perSecond / 20.0, maxOf(1.0, perSecond / 20.0))
      val creative = player.gameMode == GameMode.CREATIVE
      if (!creative && (sources == null || ++sourcesAge >= SOURCES_TICKS)) {
        sources = Sources.around(player, automation.settings().radius, task.scope)
        sourcesAge = 0
      }
      while (budget >= 1.0 && (again.isNotEmpty() || next < order.size)) {
        val retrying = again.isNotEmpty()
        val cell = if (retrying) again.first() else order[next]
        // Moves past this cell, whichever queue it came from.
        val step = { if (retrying) again.removeFirst() else next++ }
        val bp = blueprint!!
        val index = bp.at(Shell.x(cell), Shell.y(cell), Shell.z(cell))
        val state = states[index]
        val data = datas[index]
        if (data == null || Construction.kind(state) in skips) {
          step()
          continue
        }
        val (x, y, z) = placement.world(bp, Shell.x(cell), Shell.y(cell), Shell.z(cell))
        // An unloaded chunk waits for someone to come near, as the game does.
        if (!world.isChunkLoaded(x shr 4, z shr 4)) break
        val block = world.getBlockAt(x, y, z)
        val steve = Materials.steve(state)?.let { Previews.turned(it, placement) }
        // The lot's ground on the world's own ground is there already: dirt for stone stays as the world has it.
        val grounded = Construction.ground(Construction.kind(state)) && Construction.ground(block.type.key.key)
        if (grounded || block.blockData.matches(data) || (steve != null && block.blockData.matches(steve))) {
          step()
          placed++
          continue
        }
        if (!Previews.open(block)) {
          block(cell)
          step()
          continue
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
        val item = cost.items.keys.firstOrNull()?.let { Material.matchMaterial(it) } ?: placing.material
        if (place(player, block, placing, item, cell)) {
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
      finish(player)
    }

    /**
     * The build's frames, paintings, and armor stands, once every block is up: empty, each for its item, through the
     * events claims listen to. True when they're all there.
     */
    private fun entities(player: Player, creative: Boolean): Boolean {
      val bp = blueprint ?: return false
      while (entityNext < bp.entities.size) {
        if (budget < 1.0) return false
        val entity = bp.entities[entityNext]
        val item = Materials.item(entity)
        if (item == null || item in skips) {
          entityNext++
          continue
        }
        val (x, y, z) = placement.world(bp, kotlin.math.floor(entity.x).toInt(), kotlin.math.floor(entity.y).toInt(), kotlin.math.floor(entity.z).toInt())
        if (!world.isChunkLoaded(x shr 4, z shr 4)) return false
        val cost = Materials.Cost(mapOf(item to 1))
        if (!creative) {
          val lacking = lacking(player, cost)
          if (lacking != null) {
            starve(player, lacking)
            return false
          }
        }
        when (Decorations.place(player, world.getBlockAt(x, y, z), entity, bp, placement)) {
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
    private fun starve(player: Player, lacking: String) {
      val automation = Jobs.automation
      starved = lacking
      starvedSince = System.currentTimeMillis()
      reminded = starvedSince
      automation.say(player, task, automation.text(task, "building.waiting", "items" to lacking))
      automation.changed(task)
    }

    /** What the next thing to build costs: the next cell's block, or once they're up, the next entity's item. */
    private fun upcoming(): Materials.Cost? {
      val bp = blueprint ?: return null
      val cell = again.firstOrNull() ?: order.getOrNull(next)
      if (cell != null) {
        val state = states[bp.at(Shell.x(cell), Shell.y(cell), Shell.z(cell))]
        // A mob head waits for nothing: Steve's stands in.
        return if (Materials.steve(state) != null) Materials.Cost.NOTHING else Materials.of(state)
      }
      return bp.entities.getOrNull(entityNext)?.let(Materials::item)?.let { Materials.Cost(mapOf(it to 1)) }
    }

    /** Waits for what it lacks: it looks again every two seconds, reminds now and then, and gives up after its hours. */
    private fun starve(player: Player) {
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
      val cost = upcoming() ?: return
      if (player.gameMode != GameMode.CREATIVE && lacking(player, cost) != null) return
      starved = null
      starvedSince = 0L
      automation.say(player, task, automation.text(task, "building.going_on"))
      automation.changed(task)
    }

    /** What the player lacks for [cost], as they read it; null when it's all there. */
    private fun lacking(player: Player, cost: Materials.Cost): String? {
      val sources = sources ?: return null
      val automation = Jobs.automation
      for ((item, count) in cost.items) {
        if (sources.count { Crafting.key(it) == item } < count) return automation.itemName(player, item) + " ×${rest(item)}"
      }
      if (cost.water && sources.count { it.type == Material.WATER_BUCKET } < 1) return automation.itemName(player, "water_bucket")
      cost.tool?.let { kind -> if (tool(kind) == null) return automation.text(task, "building.tool_$kind") }
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
    private fun pay(player: Player, cost: Materials.Cost) {
      val sources = sources ?: return
      for ((item, count) in cost.items) {
        var left = count
        for (source in sources.list) {
          if (left == 0) break
          left -= source.take({ Crafting.key(it) == item }, left).sumOf { it.amount }
        }
        if (item == "lava_bucket") give(player, listOf(ItemStack(Material.BUCKET)))
      }
      cost.tool?.let(::wear)
    }

    /** The hoe or shovel to wear next: the worst tier first, then the most worn. */
    private fun tool(kind: String): Pair<Sources.Source, ItemStack>? {
      val sources = sources ?: return null
      return sources.list.flatMap { source -> source.slots().filterNotNull().map { source to it } }
        .mapNotNull { (source, stack) -> Construction.wearOrder(stack.type.key.key, kind)?.let { Triple(source, stack, it) } }
        .sortedWith(compareBy<Triple<Sources.Source, ItemStack, Int>> { it.third }.thenByDescending { (it.second.itemMeta as? Damageable)?.damage ?: 0 })
        .firstOrNull()?.let { it.first to it.second }
    }

    private fun wear(kind: String) {
      val (source, stack) = tool(kind) ?: return
      val taken = source.take({ it.isSimilar(stack) }, 1).firstOrNull() ?: return
      val meta = taken.itemMeta as? Damageable
      if (meta != null) {
        meta.damage = meta.damage + 1
        taken.itemMeta = meta
        if (meta.damage >= taken.type.maxDurability) return
      }
      source.add(taken)?.let { give(player() ?: return, listOf(it)) }
    }

    /** Places [data] at [block] as [player] would, through the event claims and loggers listen to; false when refused. */
    private fun place(player: Player, block: Block, data: BlockData, item: Material, cell: Int): Boolean {
      val placing = data.clone()
      // Leaves of a build stay: the player placed them, as the game marks leaves a player places.
      if (placing is Leaves) placing.isPersistent = true
      val replaced = block.state
      block.setBlockData(placing, false)
      val event = BlockPlaceEvent(block, replaced, block.getRelative(BlockFace.DOWN), ItemStack(if (item.isItem) item else Material.STONE), player, true, EquipmentSlot.HAND)
      Bukkit.getPluginManager().callEvent(event)
      if (event.isCancelled || !event.canBuild()) {
        replaced.update(true, false)
        return false
      }
      skins[cell]?.let { skin -> Decorations.skin(block, skin) }
      signs[cell]?.let { text ->
        val sign = block.state as? Sign ?: return@let
        val front = sign.getSide(Side.FRONT)
        text.take(4).forEachIndexed { i, line -> front.setLine(i, line.take(90)) }
        sign.update(false, false)
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

    private fun finish(player: Player) {
      ticker?.cancel()
      ticker = null
      val automation = Jobs.automation
      val skipped = skips.joinToString().ifEmpty { "nothing" }
      val blockedNote = if (blocked.isEmpty()) "" else "; ${blocked.size + blockedMore} spots were in the way or a claim refused them, first at " +
        blockedSpots(5).joinToString("; ") { "${it.first} ${it.second} ${it.third}" } + " (construct retry builds them once the player cleared them)"
      automation.post(task, "[job] the build is done: $placed blocks placed, skipped kinds: $skipped$blockedNote")
      bar?.removeAll()
      bar = null
    }

    private fun bar(player: Player, far: Boolean) {
      val automation = Jobs.automation
      val bar = bar ?: Bukkit.createBossBar("", BarColor.GREEN, BarStyle.SEGMENTED_10).also { bar = it }
      if (player !in bar.players) bar.addPlayer(player)
      val total = this.total.coerceAtLeast(1)
      bar.progress = (progress.toDouble() / total).coerceIn(0.0, 1.0)
      val key = when {
        !started -> "building.bar_ready"
        paused -> "building.bar_paused"
        far -> "building.bar_far"
        starved != null -> "building.bar_waiting"
        else -> "building.bar"
      }
      bar.color = if (starved != null || far || paused) BarColor.YELLOW else BarColor.GREEN
      bar.setTitle(automation.text(task, key, "title" to title, "done" to progress, "total" to total, "items" to (starved ?: "")))
    }

    override fun cancel() {
      ended = true
      ticker?.cancel()
      ticker = null
      bar?.removeAll()
      bar = null
      Jobs.remove(this)
    }

    override fun snapshot(): JsonObject = SAVES.encodeToJsonElement(BuildSave.serializer(), BuildSave(
      id = source.id, url = source.url, sha256 = source.sha256, bytes = source.bytes, title = title, world = world.name,
      x = placement.corner.first, y = placement.corner.second, z = placement.corner.third, facing = placement.facing.name.lowercase(),
      mirrored = placement.mirrored, swaps = swaps.map { listOf(it.first, it.second) }, skips = skips.toList(), next = next, placed = placed, entity_next = entityNext,
      blocked = blocked.toList(), again = again.toList(), started = started, paused = paused, blocks_per_second = blocksPerSecond, starved_since = starvedSince,
    )).jsonObject

    override fun describe(): String {
      val state = when {
        blueprint == null -> "downloading the build again"
        !started -> "ready, waiting for construct start"
        paused -> "paused by construct pause"
        starved != null -> "waiting for $starved"
        else -> "building"
      }
      return "building «$title»: $next of ${order.size} cells done ($placed placed), $state" +
        (if (skips.isEmpty()) "" else "; skipping ${skips.joinToString()}") + (if (blocked.isEmpty()) "" else "; ${blocked.size + blockedMore} spots in the way")
    }
  }

  private fun distance(player: Player, low: Triple<Int, Int, Int>, high: Triple<Int, Int, Int>): Double {
    val at = player.location
    val dx = maxOf(low.first - at.x, 0.0, at.x - high.first - 1)
    val dy = maxOf(low.second - at.y, 0.0, at.y - high.second - 1)
    val dz = maxOf(low.third - at.z, 0.0, at.z - high.third - 1)
    return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
  }

  /** The builder's site for a new building agent: the player's preview becomes its job, waiting for the agent's go. */
  fun site(task: Task, player: Player): Agents.Site {
    val preview = Previews.of(player) ?: throw ToolError("the player has no build in preview: preview one with build first, then start the builder")
    val config = Jobs.automation.building()
    val limits = config.tiers.of(player.isOp) { player.hasPermission(Permissions.build(it)) }
    val job = Job(task, player.uniqueId, preview.source, preview.title, preview.world, preview.placement, preview.swaps.toMutableList(), mutableSetOf(), limits.blocksPerSecond)
    job.ready(preview.blueprint)
    Jobs.add(job)
    // The preview goes once the job holds the build: a failure above leaves it for the next try.
    Previews.clear(player)
    val (low, high) = preview.placement.bounds(preview.blueprint)
    val anchor = Protocol.Position((low.first + high.first) / 2, low.second, (low.third + high.third) / 2, Dimensions.id(preview.world), preview.world.name)
    return Agents.Site(anchor, job, TaskLimits(limits.pauseDistance, limits.parkHours))
  }

  /** A build job from its snapshot: it downloads the build again off the server thread, then goes on where it stood. */
  fun restore(task: Task, player: Player, saved: JsonObject): Jobs.Lasting? {
    val save = runCatching { SAVES.decodeFromJsonElement(BuildSave.serializer(), saved) }.getOrNull() ?: return null
    val world = Bukkit.getWorld(save.world) ?: return null
    val facing = DraftSide.entries.firstOrNull { it.name.equals(save.facing, ignoreCase = true) } ?: return null
    val source = Blueprints.Source(save.id, save.url, save.sha256, save.bytes)
    val job = Job(task, player.uniqueId, source, save.title, world, Placement(Triple(save.x, save.y, save.z), facing, save.mirrored),
      save.swaps.mapNotNull { if (it.size == 2) it[0] to it[1] else null }.toMutableList(), save.skips.toMutableSet(), save.blocks_per_second)
    job.next = save.next
    job.placed = save.placed
    job.entityNext = save.entity_next
    job.blocked += save.blocked
    job.again.addAll(save.again)
    job.started = save.started
    job.paused = save.paused
    job.starvedSince = save.starved_since
    if (save.starved_since > 0) job.starved = "…"
    Jobs.add(job)
    plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
      val blueprint = runCatching { Previews.blueprints.load(source) }
      plugin.server.scheduler.runTask(plugin, Runnable {
        blueprint.onSuccess(job::ready).onFailure { Jobs.automation.post(task, "[job] the build couldn't be downloaded again (${it.message}); tell the player and finish") }
      })
    })
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
          if (!Previews.known(to)) throw ToolError("$to isn't a block this server has; search_items finds its id")
          if (Materials.of("minecraft:$to").unobtainable) throw ToolError("no survival player gets $to either")
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
          job.skips += block
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

  private fun status(job: Job, player: Player) = buildJsonObject {
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
    val sources = if (player.gameMode == GameMode.CREATIVE) null else Sources.around(player, Jobs.automation.settings().radius, job.task.scope)
    val needs = HashMap<String, Int>()
    val bp = job.blueprint!!
    bp.entities.drop(job.entityNext).forEach { entity -> Materials.item(entity)?.takeIf { it !in job.skips }?.let { needs.merge(it, 1, Int::plus) } }
    for (i in job.next until job.cells) {
      // Counted per cell: a double slab takes two.
      val cell = job.cellAt(i)
      val state = job.states[bp.at(Shell.x(cell), Shell.y(cell), Shell.z(cell))]
      if (Construction.kind(state) in job.skips) continue
      if (Construction.ground(Construction.kind(state))) {
        val (x, y, z) = job.placement.world(bp, Shell.x(cell), Shell.y(cell), Shell.z(cell))
        // The lot's ground on the world's own costs nothing, as the builder sees it.
        if (Construction.ground(job.world.getBlockAt(x, y, z).type.key.key)) continue
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
