package chat.allay.spigot.automation

import chat.allay.core.tasks.Kind
import chat.allay.core.tasks.Task
import chat.allay.core.tools.*
import chat.allay.spigot.tools.BrewingRecipes
import chat.allay.spigot.tools.Marks
import chat.allay.spigot.tools.Tool
import chat.allay.spigot.tools.ToolContext

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BrewingStand
import org.bukkit.block.data.Levelled
import org.bukkit.block.data.Waterlogged
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.potion.PotionType

/**
 * `brew`: brews potions for the player in stands near them, step by step from the game's own recipes. A stand that
 * already holds a step of the way (an awkward potion) starts there, and the player's own potions of a step go in before
 * water bottles: long night vision from the night vision they carry. Bottles fill stands three at a time, so three
 * potions take one stand and one set of reagents; more than the stands hold brew in rounds, each round's potions going
 * to the output before the next goes in. Several potions brew at once, each in stands of its own. Missing water
 * bottles are filled from the player's glass bottles at water or a cauldron near. Everything it needs is reserved up
 * front; each step's end ([org.bukkit.event.inventory.BrewEvent]) puts in the next reagent.
 */
object Stands {
  /** Blaze powder gives a stand this many brews. */
  private const val BREWS_PER_POWDER = 20

  /** The most potions one start brews, in all. */
  private const val MAX_BOTTLES = 64

  /** How far water and cauldrons may be for filling bottles. */
  private const val WATER_REACH = 8

  private fun stand(block: Block) = block.state as? BrewingStand

  /** What a bottle in a stand is, as the recipes name bottles; null for an empty slot or something else. */
  fun bottle(item: ItemStack?): Brewing.Bottle? {
    if (item == null || item.type.isAir) return null
    val meta = item.itemMeta as? PotionMeta ?: return null
    if (meta.hasCustomEffects()) return null
    return Brewing.Bottle(item.type.key.key, meta.basePotionType?.key?.key ?: return null)
  }

  private fun bottles(block: Block) = (0..2).map { stand(block)!!.inventory.getItem(it) }

  private fun plain(type: Material): (ItemStack) -> Boolean = { it.type == type && Sources.plain(it) }

  /** A plain bottle of [kind]: no name, no lore, nothing of its own. */
  private fun potion(kind: Brewing.Bottle): (ItemStack) -> Boolean = { item ->
    val meta = item.itemMeta as? PotionMeta
    bottle(item) == kind && meta != null && !meta.hasCustomEffects() && !meta.hasDisplayName() && !meta.hasLore()
  }

  private val water = potion(Brewing.WATER)

  /** How reservations name a bottle of [kind]. */
  private fun key(kind: Brewing.Bottle) = if (kind == Brewing.WATER) "water_bottle" else "${kind.item}:${kind.potion}"

  /** One load of a stand: [potion]'s chain from [step] on, with [bottles] bottles; [standing] when they're in the stand already. */
  private class Work(val potion: Int, val step: Int, val bottles: Int, val standing: Boolean = false)

  /** A stand of a job: the load it brews now, the step it's at, and the loads waiting for their round. */
  private class Load(val block: Block, var work: Work, val rest: ArrayDeque<Work>) {
    var step = work.step
    var rounds = 1
  }

  private class Job(
    override val task: Task, val player: Player, val chains: List<List<Brewing.Brew>>, val loads: List<Load>, val sources: Sources, val to: Jobs.Output?,
  ) : Jobs.BlockJob {
    override val blocks get() = loads.map { it.block }
    var delivered = 0

    fun chain(load: Load) = chains[load.work.potion]

    fun done(load: Load) = load.rest.isEmpty() && load.step >= chain(load).size

    override fun brewed(block: Block) {
      val load = loads.firstOrNull { it.block == block } ?: return
      val chain = chain(load)
      if (load.step >= chain.size) return
      val brew = chain[load.step]
      val now = bottles(block).filterNotNull().map(::bottle)
      if (now.size != load.work.bottles || now.any { it != brew.to }) {
        return Jobs.automation.interfered(task, Jobs.automation.text(task, "automation.reason.changed", "block" to block.type.key.key, "x" to block.x, "y" to block.y, "z" to block.z))
      }
      load.step++
      if (load.step < chain.size) {
        if (!feed(load)) return
      } else if (load.rest.isNotEmpty()) {
        if (!next(load)) return
      }
      if (loads.all(::done)) {
        Jobs.remove(this)
        val made = summary()
        val where = loads.joinToString("; ") { "${it.block.x} ${it.block.y} ${it.block.z}" }
        if (to != null) {
          delivered += take(blocks).also { Jobs.deliver(player, it, to) }.sumOf { it.amount }
          Jobs.automation.post(task, "[job] brewing done: $made in ${loads.size} stands ($where); all $delivered went to the output")
        } else {
          val gone = if (delivered > 0) "; $delivered from earlier rounds went to the inventory" else ""
          Jobs.automation.post(task, "[job] brewing done: $made in ${loads.size} stands ($where)$gone; collect the rest")
        }
      }
    }

    /** Every potion of the job with how many, for the report. */
    val made = mutableMapOf<String, Int>()

    private fun summary() = made.entries.joinToString(", ") { (what, n) -> "$n × $what" }

    /** A round is done: its potions go out, and the stand's next load goes in. False when someone took what it needs. */
    private fun next(load: Load): Boolean {
      delivered += take(listOf(load.block)).also { Jobs.deliver(player, it, to ?: Jobs.Output.Inventory) }.sumOf { it.amount }
      val work = load.rest.removeFirst()
      val kind = chains[work.potion][work.step].from
      val (taken, short) = sources.takeReserved(potion(kind), key(kind), work.bottles, Jobs.automation.reservations, task.id)
      if (short != null || taken.sumOf { it.amount } < work.bottles) {
        give(player, taken)
        Jobs.automation.interfered(task, Jobs.automation.text(task, "automation.reason.taken", "item" to key(kind), "place" to (short?.block?.let { "${it.type.key.key} ${it.x} ${it.y} ${it.z}" } ?: "?")))
        return false
      }
      val inventory = stand(load.block)!!.inventory
      taken.flatMap { stack -> List(stack.amount) { stack.clone().apply { amount = 1 } } }.forEachIndexed { slot, bottle -> inventory.setItem(slot, bottle) }
      load.work = work
      load.step = work.step
      load.rounds++
      return feed(load)
    }

    /** Puts the reagent of the load's step into its stand, from what was reserved. False when someone took it. */
    fun feed(load: Load): Boolean {
      val reagent = Material.matchMaterial(chain(load)[load.step].reagent)!!
      val (taken, short) = sources.takeReserved(plain(reagent), reagent.key.key, 1, Jobs.automation.reservations, task.id)
      if (short != null || taken.isEmpty()) {
        Jobs.automation.interfered(task, Jobs.automation.text(task, "automation.reason.taken", "item" to reagent.key.key, "place" to (short?.block?.let { "${it.type.key.key} ${it.x} ${it.y} ${it.z}" } ?: "?")))
        return false
      }
      val inventory = stand(load.block)!!.inventory
      if (inventory.ingredient != null) {
        give(player, taken)
        Jobs.automation.interfered(task, Jobs.automation.text(task, "automation.reason.changed", "block" to load.block.type.key.key, "x" to load.block.x, "y" to load.block.y, "z" to load.block.z))
        return false
      }
      inventory.ingredient = taken.first().apply { amount = 1 }
      return true
    }

    /** Loads that looked wrong at the last check: a step's end and the next reagent are a tick apart, so it takes two looks. */
    private val suspect = mutableSetOf<Block>()

    override fun check() {
      for (load in loads) {
        if (done(load)) continue
        val state = stand(load.block)
          ?: return Jobs.automation.interfered(task, Jobs.automation.text(task, "automation.reason.broken", "block" to "brewing_stand", "x" to load.block.x, "y" to load.block.y, "z" to load.block.z))
        // Between steps the stand brews; an empty ingredient slot with nothing brewing means someone took the reagent.
        if (state.brewingTime > 0 || state.inventory.ingredient != null) {
          suspect -= load.block
        } else if (suspect.add(load.block).not()) {
          return Jobs.automation.interfered(task, Jobs.automation.text(task, "automation.reason.changed", "block" to load.block.type.key.key, "x" to load.block.x, "y" to load.block.y, "z" to load.block.z))
        }
      }
    }

    override fun cancel() {
      Jobs.remove(this)
    }
  }

  /** Takes every bottle out of [stands]. */
  private fun take(stands: List<Block>): List<ItemStack> = stands.flatMap { block ->
    val inventory = stand(block)?.inventory ?: return@flatMap emptyList()
    (0..2).mapNotNull { slot -> inventory.getItem(slot)?.also { inventory.setItem(slot, null) } }
  }

  /** Water the player could fill bottles at near them: null for none, [Int.MAX_VALUE] for water itself, else the cauldrons' levels. */
  private fun water(player: Player): Pair<Int, List<Block>>? {
    val origin = player.location.block
    val cauldrons = mutableListOf<Block>()
    var levels = 0
    for (dx in -WATER_REACH..WATER_REACH) for (dy in -WATER_REACH..WATER_REACH) for (dz in -WATER_REACH..WATER_REACH) {
      val block = origin.getRelative(dx, dy, dz)
      if (block.type == Material.WATER || (block.blockData as? Waterlogged)?.isWaterlogged == true) return Int.MAX_VALUE to emptyList()
      if (block.type == Material.WATER_CAULDRON) {
        cauldrons += block
        levels += (block.blockData as? Levelled)?.level ?: 0
      }
    }
    return if (levels > 0) levels to cauldrons else null
  }

  /**
   * Fills up to [count] of the player's glass bottles with water, as clicking water does: from water near (endless) or
   * cauldrons, a level a bottle. The water bottles go to the inventory. Returns how many.
   */
  private fun fill(player: Player, sources: Sources, count: Int, holds: chat.allay.core.tasks.Reservations, task: Task): Int {
    val (levels, cauldrons) = water(player) ?: return 0
    val n = minOf(count, levels, sources.free(plain(Material.GLASS_BOTTLE), "glass_bottle", holds, task.id))
    if (n <= 0) return 0
    var left = n
    for (source in sources.list) {
      if (left <= 0) break
      left -= source.take(plain(Material.GLASS_BOTTLE), left).sumOf { it.amount }
    }
    val filled = n - left
    var lower = if (levels == Int.MAX_VALUE) 0 else filled
    for (cauldron in cauldrons) {
      if (lower <= 0) break
      val data = cauldron.blockData as? Levelled ?: continue
      val take = minOf(lower, data.level)
      lower -= take
      if (data.level - take <= 0) cauldron.type = Material.CAULDRON else cauldron.blockData = data.apply { level -= take }
    }
    val bottle = ItemStack(Material.POTION).apply { itemMeta = (itemMeta as PotionMeta).apply { basePotionType = PotionType.WATER } }
    give(player, List(filled) { bottle.clone() })
    return filled
  }

  /** The potions a call asks for: `potions`, or `potion` with `form` and `bottles`. */
  private fun requests(player: Player, args: JsonObject): List<Triple<List<Brewing.Brew>, Brewing.Bottle, Int?>> {
    val entries = (args["potions"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.map { Triple(it.string("potion") ?: throw ToolError("each of potions needs a potion"), it.string("form"), it.int("bottles")) }
      ?: listOfNotNull(args.string("potion")?.let { Triple(it, args.string("form"), args.int("bottles")) })
    if (entries.isEmpty()) throw ToolError("start needs potion or potions")
    val list = entries.map { (name, form, bottles) ->
      val (brews, target) = BrewingRecipes.target(player.server, name, form)
      Triple(Brewing.chain(brews, target) ?: throw ToolError("the brewing stand can't make ${target.item} of ${target.potion}"), target, bottles)
    }
    if (list.map { it.second }.toSet().size != list.size) throw ToolError("a potion is named twice")
    return list
  }

  val tool = object : Tool {
    override val name = "brew"

    override fun run(context: ToolContext, args: JsonObject): ToolOutput {
      val player = context.requirePlayer()
      val task = context.requireTask()
      val automation = context.features.automation
      val settings = automation.settings()
      val lang = context.lang
      automation.require(player, task, Kind.BREWING)
      return when (val action = args.string("action")?.lowercase()) {
        "scan" -> {
          val chain = args.string("potion")?.let { potion ->
            val (brews, target) = BrewingRecipes.target(player.server, potion, args.string("form"))
            Brewing.chain(brews, target) ?: throw ToolError("the brewing stand can't make ${target.item} of ${target.potion}")
          }
          val marks = mutableListOf<Marks.Mark>()
          val rows = buildJsonArray {
            Blocks.near(player.location, settings.radius) { it.type == Material.BREWING_STAND }.forEach { block ->
              val state = stand(block) ?: return@forEach
              val unfit = Blocks.unusable(player, block, settings.radius, settings.lineOfSight)
              val busy = state.brewingTime > 0 || state.inventory.ingredient != null || Jobs.busy(block)
              val start = chain?.let { step(block, listOf(it)) }
              add(buildJsonObject {
                put("x", block.x); put("y", block.y); put("z", block.z)
                putJsonArray("bottles") { bottles(block).forEach { item -> add(bottle(item)?.let { "${it.item} ${it.potion}" } ?: item?.type?.key?.key ?: "empty") } }
                put("fuel", state.fuelLevel)
                when {
                  unfit != null -> put("unsuitable", unfit.model)
                  busy -> put("busy", true)
                  start != null && start.first < 0 -> put("unsuitable", "its bottles aren't a step toward this potion")
                  start != null -> put("starts_at_step", start.second + 1)
                }
              })
              marks += Marks.Mark(block, null, null, if (unfit == null && !busy && (start == null || start.first >= 0)) Marks.Hue.GREEN else Marks.Hue.RED)
            }
          }
          if (marks.isNotEmpty()) Marks.show(context.plugin, player, marks, context.features.config().tools.marksSeconds)
          val sources = Sources.around(player, settings.radius)
          val result = buildJsonObject {
            put("stands", rows)
            put("note", "green glow: usable; red: busy or not fit; the player sees it")
            putJsonObject("at_hand") {
              put("water_bottles", sources.count(water))
              put("glass_bottles", sources.count(plain(Material.GLASS_BOTTLE)))
              put("blaze_powder", sources.count(plain(Material.BLAZE_POWDER)))
              put("blaze_rod", sources.count(plain(Material.BLAZE_ROD)))
              chain?.map { it.reagent }?.distinct()?.forEach { id -> Material.matchMaterial(id)?.let { put(id, sources.count(plain(it))) } }
            }
            put("water_near", water(player) != null)
            chain?.let { steps -> putJsonArray("steps") { steps.forEach { add("${it.from.potion} + ${it.reagent} → ${it.to.item} ${it.to.potion}") } } }
            Jobs.saved(player, Kind.BREWING, context.features.settings)?.let { put("player_output", it) }
          }
          ToolOutput(result, lang.text("summary.stands", "count" to rows.size))
        }
        "start" -> start(context, player, task, args)
        "status" -> {
          val job = automation.agent(task).job as? Job ?: throw ToolError("no brewing running for this task")
          ToolOutput(buildJsonObject {
            put("stands", buildJsonArray {
              job.loads.forEach {
                add(buildJsonObject {
                  put("x", it.block.x); put("y", it.block.y); put("z", it.block.z)
                  put("potion", job.chain(it).last().to.potion)
                  put("step", minOf(it.step + 1, job.chain(it).size)); put("steps", job.chain(it).size)
                  put("rounds_left", it.rest.size); put("done", job.done(it))
                })
              }
            })
            put("delivered", job.delivered)
          }, lang.text("summary.brewing_status"))
        }
        "collect" -> {
          val to = Jobs.target(player, args["to"], Kind.BREWING, context.features.settings, args.bool("remember") == true)
            ?: throw ToolError("to is required: \"inventory\", \"auto\", or chests; ask the player")
          val job = automation.agent(task).job as? Job
          val blocks = Blocks.list(player, args["stands"], settings.radius).ifEmpty { job?.blocks?.toList().orEmpty() }
          if (blocks.isEmpty()) throw ToolError("which stands? pass stands")
          if (job != null && Jobs.active(job)) throw ToolError("still brewing; wait for the job to end")
          blocks.forEach { block -> Blocks.unusable(player, block, settings.radius, false)?.let { throw ToolError("the stand at ${block.x} ${block.y} ${block.z} can't be used: ${it.model}") } }
          val taken = take(blocks)
          Jobs.deliver(player, taken, to)
          ToolOutput(buildJsonObject { put("collected", taken.sumOf { it.amount }) }, lang.text("summary.collected", "count" to taken.sumOf { it.amount }))
        }
        else -> throw ToolError("action must be scan, start, status, or collect, not $action")
      }
    }
  }

  /**
   * Which of [chains] a stand's bottles are a step of: the chain, the step, and how many bottles; (-1, -1, 0) when
   * they fit none, and (0, 0, 0) for an empty stand. Water fits the first chain.
   */
  private fun step(block: Block, chains: List<List<Brewing.Brew>>): Triple<Int, Int, Int> {
    val present = bottles(block).filterNotNull()
    if (present.isEmpty()) return Triple(0, 0, 0)
    val kinds = present.map(::bottle).distinct()
    if (kinds.size != 1 || kinds.first() == null) return Triple(-1, -1, 0)
    val kind = kinds.first()!!
    chains.forEachIndexed { index, chain ->
      val at = chain.indexOfFirst { it.from == kind }
      if (at >= 0) return Triple(index, at, present.size)
    }
    return Triple(-1, -1, 0)
  }

  private fun start(context: ToolContext, player: Player, task: Task, args: JsonObject, filled: Int = 0): ToolOutput {
    val automation = context.features.automation
    val settings = automation.settings()
    val holds = automation.reservations
    automation.agent(task).job?.let { if (Jobs.active(it)) throw ToolError("this task already runs a job; one per task") }
    val requests = requests(player, args)
    val chains = requests.map { it.first }
    val to = Jobs.target(player, args["to"], Kind.BREWING, context.features.settings, args.bool("remember") == true)
    val named = Blocks.list(player, args["stands"], settings.radius).ifEmpty { automation.picking.picked(player, task) }
    val candidates = named.ifEmpty { Blocks.near(player.location, settings.radius) { it.type == Material.BREWING_STAND } }
      .filter { it.type == Material.BREWING_STAND && Blocks.unusable(player, it, settings.radius, settings.lineOfSight) == null && !Jobs.busy(it) }
      .filter { stand(it)!!.let { state -> state.brewingTime <= 0 && state.inventory.ingredient == null } }
      .sortedBy { it.location.distanceSquared(player.location) }
    val fitting = candidates.map { it to step(it, chains) }.filter { it.second.first >= 0 }
    if (fitting.isEmpty()) throw ToolError("no stand near the player fits: each needs to be idle with no bottles, water bottles, or a step of these potions in it")

    // Stands that already hold a step go on from there. The rest take the player's own bottles of a later step first
    // (their night vision potions when they want long ones), then water; bottles is each potion's total, three to a stand.
    val sources = Sources.around(player, settings.radius)
    val standing = fitting.filter { it.second.third > 0 }
    val empty = fitting.filter { it.second.third == 0 }.map { it.first }
    val fresh = args.bool("fresh") == true
    val claimed = mutableMapOf<String, Int>()
    val firsts = mutableListOf<Pair<Block, Work>>()
    val waiting = mutableListOf<Work>()
    requests.forEachIndexed { index, (chain, _, bottles) ->
      val here = standing.filter { it.second.first == index }
      here.forEach { (block, at) -> firsts += block to Work(index, at.second, at.third, standing = true) }
      val inStands = here.sumOf { it.second.third }
      val own = if (fresh) emptyMap() else chain.indices.drop(1).associateWith { step ->
        val kind = chain[step].from
        (sources.free(potion(kind), key(kind), holds, task.id) - (claimed[key(kind)] ?: 0)).coerceAtLeast(0)
      }.filterValues { it > 0 }
      val total = (bottles ?: (inStands + own.values.sum()).takeIf { it > 0 } ?: Brewing.STAND_BOTTLES).coerceIn(1, MAX_BOTTLES)
      Brewing.fill((total - inStands).coerceAtLeast(0), Int.MAX_VALUE, own).forEach { fill ->
        if (fill.step > 0) claimed.merge(key(chain[fill.step].from), fill.bottles, Int::plus)
        waiting += Work(index, fill.step, fill.bottles)
      }
    }
    if (firsts.isEmpty() && waiting.isEmpty()) throw ToolError("nothing to brew: the stands already hold these potions; collect them first")
    // Empty stands take a load each; whatever is left waits for rounds, spread over every stand.
    val queue = ArrayDeque(waiting)
    empty.forEach { block -> queue.removeFirstOrNull()?.let { firsts += block to it } }
    val rounds = Brewing.rounds(queue.toList(), firsts.size)
    val plans = firsts.mapIndexed { index, (block, work) -> Triple(block, work, ArrayDeque(rounds.getOrNull(index).orEmpty())) }
    // What it all needs: the bottles of every load not in a stand yet, a reagent per load and step, and the fuel for every step a stand brews.
    val bottlesNeeded = mutableMapOf<Brewing.Bottle, Int>()
    val needs = mutableMapOf<String, Int>()
    var powder = 0
    val powderPer = plans.map { (block, work, rest) ->
      (listOf(work) + rest).forEach { load ->
        if (!load.standing) bottlesNeeded.merge(chains[load.potion][load.step].from, load.bottles, Int::plus)
        chains[load.potion].drop(load.step).forEach { needs.merge(it.reagent, 1, Int::plus) }
      }
      val brews = (listOf(work) + rest).sumOf { chains[it.potion].size - it.step }
      val fuel = stand(block)!!.fuelLevel
      (if (brews > fuel) (brews - fuel + BREWS_PER_POWDER - 1) / BREWS_PER_POWDER else 0).also { powder += it }
    }
    val missing = mutableMapOf<String, Int>()
    bottlesNeeded.forEach { (kind, n) -> (n - sources.free(potion(kind), key(kind), holds, task.id)).takeIf { it > 0 }?.let { missing[key(kind)] = it } }
    needs.forEach { (id, n) ->
      val material = Material.matchMaterial(id) ?: return@forEach
      (n - sources.free(plain(material), id, holds, task.id)).takeIf { it > 0 }?.let { missing[id] = it }
    }
    // Blaze powder may be a reagent too (strength); the fuel gets only what the reagents leave.
    val havePowder = (sources.free(plain(Material.BLAZE_POWDER), "blaze_powder", holds, task.id) - (needs["blaze_powder"] ?: 0)).coerceAtLeast(0)
    val rods = if (powder > havePowder) (powder - havePowder + 1) / 2 else 0
    if (rods > 0) (rods - sources.free(plain(Material.BLAZE_ROD), "blaze_rod", holds, task.id)).takeIf { it > 0 }?.let { missing["blaze_powder"] = powder - havePowder }
    // Water bottles the player lacks are filled from their glass bottles, once, when water is near.
    missing["water_bottle"]?.let { short ->
      if (filled == 0) {
        val made = fill(player, sources, short, holds, task)
        if (made > 0) return start(context, player, task, args, made)
      }
    }
    if (missing.isNotEmpty()) {
      return ToolOutput(buildJsonObject {
        put("started", false)
        putJsonObject("missing") { missing.forEach { (id, n) -> put(id, n) } }
        if (filled > 0) put("filled_water_bottles", filled)
        if ("water_bottle" in missing) put("water", if (water(player) == null) "no water or filled cauldron near to fill glass bottles at" else "not enough glass bottles to fill")
        put("note", "tell the player what's missing and where it comes from; nothing else was taken")
      }, context.lang.text("summary.brewing_missing", "count" to missing.size))
    }

    // Reserve it all, then put in the first bottles and the fuel; the first reagents go in last, and the stands start.
    val inHand = mutableListOf<ItemStack>()
    fun fail(reason: String): Nothing {
      give(player, inHand)
      holds.release(task.id)
      throw ToolError(reason)
    }
    fun reserve(match: (ItemStack) -> Boolean, key: String, n: Int) {
      if (n > 0 && !sources.reserve(match, key, n, holds, task.id)) fail("someone took the $key meanwhile")
    }
    bottlesNeeded.forEach { (kind, n) -> reserve(potion(kind), key(kind), n) }
    needs.forEach { (id, n) -> reserve(plain(Material.matchMaterial(id)!!), id, n) }
    reserve(plain(Material.BLAZE_POWDER), "blaze_powder", minOf(powder, havePowder))
    reserve(plain(Material.BLAZE_ROD), "blaze_rod", rods)
    val (rodsTaken, rodsShort) = sources.takeReserved(plain(Material.BLAZE_ROD), "blaze_rod", rods, holds, task.id)
    inHand += rodsTaken
    if (rodsShort != null) fail("the blaze rods changed meanwhile")
    val (powderTaken, powderShort) = sources.takeReserved(plain(Material.BLAZE_POWDER), "blaze_powder", minOf(powder, havePowder), holds, task.id)
    inHand += powderTaken
    if (powderShort != null) fail("the blaze powder changed meanwhile")
    // The first round's bottles come out before any stand changes, so a shortfall leaves the stands as they were.
    val bottlesInHand = plans.map { (_, work, _) ->
      if (work.standing) return@map emptyList<ItemStack>()
      val kind = chains[work.potion][work.step].from
      val (bottles, short) = sources.takeReserved(potion(kind), key(kind), work.bottles, holds, task.id)
      inHand += bottles
      if (short != null) fail("the ${key(kind)} changed meanwhile")
      bottles.flatMap { stack -> List(stack.amount) { stack.clone().apply { amount = 1 } } }
    }
    // A blaze rod makes two powder, as the crafting table does.
    var powderPool = powderTaken.sumOf { it.amount } + rodsTaken.sumOf { it.amount } * 2
    val loads = plans.mapIndexed { index, (block, work, rest) ->
      val inventory = stand(block)!!.inventory
      bottlesInHand[index].forEachIndexed { slot, bottle -> inventory.setItem(slot, bottle) }
      val n = minOf(powderPool, powderPer[index])
      if (n > 0) {
        val fuel = inventory.fuel
        inventory.fuel = ItemStack(Material.BLAZE_POWDER, (fuel?.takeIf { it.type == Material.BLAZE_POWDER }?.amount ?: 0) + n)
        powderPool -= n
      }
      Load(block, work, rest)
    }
    if (powderPool > 0) give(player, listOf(ItemStack(Material.BLAZE_POWDER, powderPool)))
    val job = Job(task, player, chains, loads, sources, to)
    plans.forEach { (_, work, rest) ->
      (listOf(work) + rest).forEach { load -> chains[load.potion].last().to.let { job.made.merge("${it.item} ${it.potion}", load.bottles, Int::plus) } }
    }
    for (load in loads) if (!job.feed(load)) return ToolOutput(buildJsonObject { put("started", false) }, "")
    automation.agent(task).job = job
    Jobs.add(job)
    Marks.show(context.plugin, player, loads.map { Marks.Mark(it.block, null, chains[it.work.potion].last().to.potion.replace('_', ' '), Marks.Hue.PURPLE) }, context.features.config().tools.marksSeconds)
    val result = buildJsonObject {
      put("started", true)
      put("stands", buildJsonArray {
        loads.forEach { load ->
          add(buildJsonObject {
            put("x", load.block.x); put("y", load.block.y); put("z", load.block.z)
            put("potion", chains[load.work.potion].last().to.potion)
            put("from_step", load.step + 1); put("steps_left", chains[load.work.potion].size - load.step); put("bottles", load.work.bottles)
            if (load.rest.isNotEmpty()) put("rounds", load.rest.size + 1)
          })
        }
      })
      putJsonObject("potions") { job.made.forEach { (what, n) -> put(what, n) } }
      val theirs = plans.flatMap { (_, work, rest) -> listOf(work) + rest }.filter { it.step > 0 && !it.standing }.groupBy { chains[it.potion][it.step].from }
      if (theirs.isNotEmpty()) putJsonArray("from_player") { theirs.forEach { (kind, parts) -> add("${parts.sumOf { it.bottles }} × ${kind.item} ${kind.potion}") } }
      if (filled > 0) put("filled_water_bottles", filled)
      to?.let { put("output", Jobs.describe(it)) }
      val rounded = loads.any { it.rest.isNotEmpty() }
      put("note", "each step brews 20 seconds; wait now, you wake when it's all done" +
        (if (rounded) ". More than the stands hold brews in rounds: each round's potions go to ${if (to != null) "the output" else "the inventory"} before the next goes in" else "") +
        (if (to != null) ". At the end everything goes to the output, so there's no collect to do" else ""))
    }
    return ToolOutput(result, context.lang.text("summary.brewing_started", "count" to loads.size))
  }
}
