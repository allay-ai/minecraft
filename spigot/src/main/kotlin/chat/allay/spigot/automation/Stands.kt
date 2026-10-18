package chat.allay.spigot.automation

import chat.allay.core.tasks.Task
import chat.allay.core.tools.*
import chat.allay.spigot.tools.BrewingRecipes
import chat.allay.spigot.tools.Marks
import chat.allay.spigot.tools.Tool
import chat.allay.spigot.tools.ToolContext

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
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.PotionMeta

/**
 * `brew`: brews a potion for the player in stands near them, step by step from the game's own recipes. A stand that
 * already holds a step of the way (an awkward potion) starts there, and the player's own potions of a step go in before
 * water bottles: long night vision from the night vision they carry. Bottles fill stands three at a time, so three
 * potions take one stand and one set of reagents. Everything it needs is reserved up front; each
 * step's end ([org.bukkit.event.inventory.BrewEvent]) puts in the next reagent.
 */
object Stands {
  /** Blaze powder gives a stand this many brews. */
  private const val BREWS_PER_POWDER = 20

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

  /** Where in [chain] a stand stands: the step whose bottle all its bottles are, with how many; -1 when they don't fit. */
  private fun step(block: Block, chain: List<Brewing.Brew>): Pair<Int, Int> {
    val present = bottles(block).filterNotNull()
    if (present.isEmpty()) return 0 to 0
    val kinds = present.map(::bottle).distinct()
    if (kinds.size != 1 || kinds.first() == null) return -1 to 0
    val kind = kinds.first()!!
    val index = chain.indexOfFirst { it.from == kind }
    return when {
      index >= 0 -> index to present.size
      chain.lastOrNull()?.to == kind -> chain.size to present.size
      else -> -1 to 0
    }
  }

  private class Load(val block: Block, var step: Int, val bottles: Int)

  private class Job(override val task: Task, val player: Player, val chain: List<Brewing.Brew>, val loads: List<Load>, val sources: Sources) : Jobs.BlockJob {
    override val blocks get() = loads.map { it.block }

    override fun brewed(block: Block) {
      val load = loads.firstOrNull { it.block == block } ?: return
      val brew = chain[load.step]
      val now = bottles(block).filterNotNull().map(::bottle)
      if (now.size != load.bottles || now.any { it != brew.to }) {
        return Jobs.automation.interfered(task, Jobs.automation.text(task, "automation.reason.changed", "block" to block.type.key.key, "x" to block.x, "y" to block.y, "z" to block.z))
      }
      load.step++
      if (load.step < chain.size) {
        if (!feed(load)) return
      }
      if (loads.all { it.step >= chain.size }) {
        Jobs.remove(this)
        val target = chain.last().to
        Jobs.automation.post(task, "[job] brewing done: ${loads.sumOf { it.bottles }} × ${target.item} of ${target.potion} in ${loads.size} stands (${loads.joinToString("; ") { "${it.block.x} ${it.block.y} ${it.block.z}" }}); collect them")
      }
    }

    /** Puts the reagent of the load's step into its stand, from what was reserved. False when someone took it. */
    fun feed(load: Load): Boolean {
      val reagent = Material.matchMaterial(chain[load.step].reagent)!!
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
        if (load.step >= chain.size) continue
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

  val tool = object : Tool {
    override val name = "brew"

    override fun run(context: ToolContext, args: JsonObject): ToolOutput {
      val player = context.requirePlayer()
      val task = context.requireTask()
      val automation = context.features.automation
      val settings = automation.settings()
      val lang = context.lang
      automation.require(player, task, chat.allay.core.tasks.Kind.BREWING)
      val chain = args.string("potion")?.let { potion ->
        val (brews, target) = BrewingRecipes.target(player.server, potion, args.string("form"))
        Brewing.chain(brews, target) ?: throw ToolError("the brewing stand can't make ${target.item} of ${target.potion}")
      }
      return when (val action = args.string("action")?.lowercase()) {
        "scan" -> {
          val marks = mutableListOf<Marks.Mark>()
          val rows = buildJsonArray {
            Blocks.near(player.location, settings.radius) { it.type == Material.BREWING_STAND }.forEach { block ->
              val state = stand(block) ?: return@forEach
              val unfit = Blocks.unusable(player, block, settings.radius, settings.lineOfSight)
              val busy = state.brewingTime > 0 || state.inventory.ingredient != null || Jobs.busy(block)
              val start = chain?.let { step(block, it) }
              add(buildJsonObject {
                put("x", block.x); put("y", block.y); put("z", block.z)
                putJsonArray("bottles") { bottles(block).forEach { item -> add(bottle(item)?.let { "${it.item} ${it.potion}" } ?: item?.type?.key?.key ?: "empty") } }
                put("fuel", state.fuelLevel)
                when {
                  unfit != null -> put("unsuitable", unfit.model)
                  busy -> put("busy", true)
                  start != null && start.first < 0 -> put("unsuitable", "its bottles aren't a step toward this potion")
                  start != null -> put("starts_at_step", start.first + 1)
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
              put("blaze_powder", sources.count(plain(Material.BLAZE_POWDER)))
              put("blaze_rod", sources.count(plain(Material.BLAZE_ROD)))
              chain?.map { it.reagent }?.distinct()?.forEach { id -> Material.matchMaterial(id)?.let { put(id, sources.count(plain(it))) } }
            }
            chain?.let { steps -> putJsonArray("steps") { steps.forEach { add("${it.from.potion} + ${it.reagent} → ${it.to.item} ${it.to.potion}") } } }
          }
          ToolOutput(result, lang.text("summary.stands", "count" to rows.size))
        }
        "start" -> start(context, player, task, args, chain ?: throw ToolError("start needs potion"))
        "status" -> {
          val job = automation.agent(task).job as? Job ?: throw ToolError("no brewing running for this task")
          ToolOutput(buildJsonObject {
            put("steps", job.chain.size)
            put("stands", buildJsonArray { job.loads.forEach { add(buildJsonObject { put("x", it.block.x); put("y", it.block.y); put("z", it.block.z); put("step", minOf(it.step + 1, job.chain.size)); put("done", it.step >= job.chain.size) }) } })
          }, lang.text("summary.brewing_status"))
        }
        "collect" -> {
          val to = Jobs.output(player, args["to"]) ?: throw ToolError("to is required: \"inventory\" or chests; ask the player")
          val job = automation.agent(task).job as? Job
          val blocks = Blocks.list(player, args["stands"], settings.radius).ifEmpty { job?.blocks?.toList().orEmpty() }
          if (blocks.isEmpty()) throw ToolError("which stands? pass stands")
          if (job != null && job.loads.any { it.step < job.chain.size }) throw ToolError("still brewing; wait for the job to end")
          val taken = blocks.flatMap { block ->
            Blocks.unusable(player, block, settings.radius, false)?.let { throw ToolError("the stand at ${block.x} ${block.y} ${block.z} can't be used: ${it.model}") }
            val inventory = stand(block)?.inventory ?: return@flatMap emptyList()
            (0..2).mapNotNull { slot -> inventory.getItem(slot)?.also { inventory.setItem(slot, null) } }
          }
          Jobs.deliver(player, taken, to)
          ToolOutput(buildJsonObject { put("collected", taken.sumOf { it.amount }) }, lang.text("summary.collected", "count" to taken.sumOf { it.amount }))
        }
        else -> throw ToolError("action must be scan, start, status, or collect, not $action")
      }
    }
  }

  private fun start(context: ToolContext, player: Player, task: Task, args: JsonObject, chain: List<Brewing.Brew>): ToolOutput {
    val automation = context.features.automation
    val settings = automation.settings()
    val holds = automation.reservations
    automation.agent(task).job?.let { if (Jobs.active(it)) throw ToolError("this task already runs a job; one per task") }
    val named = Blocks.list(player, args["stands"], settings.radius).ifEmpty { automation.picking.picked(player, task) }
    val candidates = named.ifEmpty { Blocks.near(player.location, settings.radius) { it.type == Material.BREWING_STAND } }
      .filter { it.type == Material.BREWING_STAND && Blocks.unusable(player, it, settings.radius, settings.lineOfSight) == null && !Jobs.busy(it) }
      .filter { stand(it)!!.let { state -> state.brewingTime <= 0 && state.inventory.ingredient == null } }
      .sortedBy { it.location.distanceSquared(player.location) }
    val fitting = candidates.map { it to step(it, chain) }.filter { it.second.first >= 0 && it.second.first < chain.size }
    if (fitting.isEmpty()) throw ToolError("no stand near the player fits: each needs to be idle with no bottles, water bottles, or a step of this potion in it")

    // Stands that already hold a step go on from there. Empty ones take the player's own bottles of a later step first
    // (their night vision potions when they want long ones), then water; bottles is the total, three to a stand.
    val sources = Sources.around(player, settings.radius)
    val loaded = fitting.filter { it.second.second > 0 }.map { (block, start) -> Triple(block, start.first, start.second) }
    val empty = fitting.filter { it.second.second == 0 }.map { it.first }
    val fresh = args.bool("fresh") == true
    val own = if (fresh) emptyMap() else chain.indices.drop(1).associateWith { index -> sources.free(potion(chain[index].from), key(chain[index].from), holds, task.id) }.filterValues { it > 0 }
    val inStands = loaded.sumOf { it.third }
    val total = args.int("bottles")?.coerceIn(1, Brewing.STAND_BOTTLES * fitting.size)
      ?: (inStands + own.values.sum()).takeIf { it > 0 }?.coerceAtMost(Brewing.STAND_BOTTLES * fitting.size)
      ?: Brewing.STAND_BOTTLES
    val fills = Brewing.fill(total - inStands, empty.size, own)
    val plans = loaded + fills.mapIndexed { index, fill -> Triple(empty[index], fill.step, fill.bottles) }
    if (plans.isEmpty()) throw ToolError("the stands that hold this potion's steps already make ${inStands} bottles; collect or brew those first")

    // What it all needs: the bottles for empty stands, a reagent per stand and step left, and fuel for those steps.
    val bottlesNeeded = mutableMapOf<Brewing.Bottle, Int>()
    fills.forEach { bottlesNeeded.merge(chain[it.step].from, it.bottles, Int::plus) }
    val needs = mutableMapOf<String, Int>()
    var powder = 0
    plans.forEach { (block, start, _) ->
      chain.drop(start).forEach { needs.merge(it.reagent, 1, Int::plus) }
      val brews = chain.size - start
      val fuel = stand(block)!!.fuelLevel
      if (brews > fuel) powder += (brews - fuel + BREWS_PER_POWDER - 1) / BREWS_PER_POWDER
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
    if (missing.isNotEmpty()) {
      return ToolOutput(buildJsonObject {
        put("started", false)
        putJsonObject("missing") { missing.forEach { (id, n) -> put(id, n) } }
        put("note", "tell the player what's missing and where it comes from; nothing was taken")
      }, context.lang.text("summary.brewing_missing", "count" to missing.size))
    }

    // Reserve it all, then put in the bottles and the fuel; the first reagents go in last, and the stands start.
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
    // Bottles come out before any stand changes, so a shortfall leaves the stands as they were.
    val bottlesInHand = plans.mapIndexed { index, (_, start, n) ->
      if (index < loaded.size) return@mapIndexed emptyList<ItemStack>()
      val kind = chain[start].from
      val (bottles, short) = sources.takeReserved(potion(kind), key(kind), n, holds, task.id)
      inHand += bottles
      if (short != null) fail("the ${key(kind)} changed meanwhile")
      bottles.flatMap { stack -> List(stack.amount) { stack.clone().apply { amount = 1 } } }
    }
    // A blaze rod makes two powder, as the crafting table does.
    var powderPool = powderTaken.sumOf { it.amount } + rodsTaken.sumOf { it.amount } * 2
    val loads = plans.mapIndexed { index, (block, start, n) ->
      val inventory = stand(block)!!.inventory
      bottlesInHand[index].forEachIndexed { slot, bottle -> inventory.setItem(slot, bottle) }
      val state = stand(block)!!
      val brews = chain.size - start
      if (brews > state.fuelLevel && powderPool > 0) {
        val n = minOf(powderPool, (brews - state.fuelLevel + BREWS_PER_POWDER - 1) / BREWS_PER_POWDER)
        val fuel = inventory.fuel
        inventory.fuel = ItemStack(Material.BLAZE_POWDER, (fuel?.takeIf { it.type == Material.BLAZE_POWDER }?.amount ?: 0) + n)
        powderPool -= n
      }
      Load(block, start, n)
    }
    if (powderPool > 0) give(player, listOf(ItemStack(Material.BLAZE_POWDER, powderPool)))
    val job = Job(task, player, chain, loads, sources)
    for (load in loads) if (!job.feed(load)) return ToolOutput(buildJsonObject { put("started", false) }, "")
    automation.agent(task).job = job
    Jobs.add(job)
    Marks.show(context.plugin, player, loads.map { Marks.Mark(it.block, null, chain.last().to.potion.replace('_', ' '), Marks.Hue.PURPLE) }, context.features.config().tools.marksSeconds)
    val result = buildJsonObject {
      put("started", true)
      put("stands", buildJsonArray { loads.forEach { load -> add(buildJsonObject { put("x", load.block.x); put("y", load.block.y); put("z", load.block.z); put("from_step", load.step + 1); put("steps_left", chain.size - load.step); put("bottles", load.bottles) }) } })
      put("steps", chain.size)
      put("bottles", loads.sumOf { it.bottles })
      val theirs = fills.filter { it.step > 0 }.groupBy { chain[it.step].from }
      if (theirs.isNotEmpty()) putJsonArray("from_player") { theirs.forEach { (kind, parts) -> add("${parts.sumOf { it.bottles }} × ${kind.item} ${kind.potion}") } }
      put("note", "each step brews 20 seconds; wait now, you wake when it's all done")
    }
    return ToolOutput(result, context.lang.text("summary.brewing_started", "count" to loads.size))
  }
}
