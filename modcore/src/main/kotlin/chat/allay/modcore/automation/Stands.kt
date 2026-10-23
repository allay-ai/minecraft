package chat.allay.modcore.automation

import chat.allay.core.tasks.Kind
import chat.allay.core.tasks.Task
import chat.allay.core.tools.*
import chat.allay.modcore.audience
import chat.allay.modcore.mixin.BrewingAccess
import chat.allay.modcore.player
import chat.allay.modcore.tools.BlockAt
import chat.allay.modcore.tools.BrewingRecipes
import chat.allay.modcore.tools.InteractTools
import chat.allay.modcore.tools.Marks
import chat.allay.modcore.tools.Tool
import chat.allay.modcore.tools.ToolContext
import chat.allay.modcore.tools.Worlds
import chat.allay.modcore.tools.at
import chat.allay.modcore.tools.key
import chat.allay.modcore.tools.own

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.minecraft.core.component.DataComponents
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.alchemy.PotionContents
import net.minecraft.world.item.alchemy.Potions
import net.minecraft.world.level.block.Blocks as GameBlocks
import net.minecraft.world.level.block.LayeredCauldronBlock
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity
import net.minecraft.world.level.block.state.properties.BlockStateProperties

/**
 * `brew`: brews potions for the player in stands near them, step by step from the game's own recipes. A stand that
 * already holds a step of the way (an awkward potion) starts there, and the player's own potions of a step go in before
 * water bottles: long night vision from the night vision they carry. Bottles fill stands three at a time, so three
 * potions take one stand and one set of reagents; more than the stands hold brew in rounds, each round's potions going
 * to the output before the next goes in. Several potions brew at once, each in stands of its own. Missing water
 * bottles are filled from the player's glass bottles at water or a cauldron near. Everything it needs is reserved up
 * front; each step's end (the mod's `BrewMixin`) puts in the next reagent.
 */
object Stands {
  /** Blaze powder gives a stand this many brews. */
  private const val BREWS_PER_POWDER = 20

  /** The most potions one start brews, in all. */
  private const val MAX_BOTTLES = 64

  /** How far water and cauldrons may be for filling bottles. */
  private const val WATER_REACH = 8

  private const val INGREDIENT = 3
  private const val FUEL = 4

  /** What Bukkit's `PotionMeta` covers. */
  private val POTIONS = setOf(Items.POTION, Items.SPLASH_POTION, Items.LINGERING_POTION, Items.TIPPED_ARROW)

  private fun stand(block: BlockAt) = block.entity as? BrewingStandBlockEntity

  private fun slot(block: BlockAt, index: Int): ItemStack? = stand(block)?.getItem(index)?.takeUnless { it.isEmpty }

  private fun set(block: BlockAt, index: Int, item: ItemStack?) {
    val stand = stand(block) ?: return
    stand.setItem(index, item ?: ItemStack.EMPTY)
    stand.setChanged()
  }

  /** What a bottle in a stand is, as the recipes name bottles; null for an empty slot or something else. */
  fun bottle(item: ItemStack?): Brewing.Bottle? {
    if (item == null || item.isEmpty || item.item !in POTIONS) return null
    val contents = item.get(DataComponents.POTION_CONTENTS) ?: return null
    if (contents.customEffects().isNotEmpty()) return null
    return Brewing.Bottle(Slots.id(item), contents.potion().map { Worlds.key(it) }.orElse(null) ?: return null)
  }

  private fun bottles(block: BlockAt) = (0..2).map { slot(block, it) }

  private fun plain(type: Item): (ItemStack) -> Boolean = { it.item == type && Sources.plain(it) }

  /** A plain bottle of [kind]: no name, no lore, nothing of its own. */
  private fun potion(kind: Brewing.Bottle): (ItemStack) -> Boolean = { item ->
    bottle(item) == kind && item.own(DataComponents.CUSTOM_NAME) == null && item.own(DataComponents.LORE) == null
  }

  private val water = potion(Brewing.WATER)

  /** How reservations name a bottle of [kind]. */
  private fun key(kind: Brewing.Bottle) = if (kind == Brewing.WATER) "water_bottle" else "${kind.item}:${kind.potion}"

  /**
   * One load of a stand: [potion]'s chain from [step] on, with [bottles] bottles; [standing] when they're in the stand
   * already, and then [extra] of them go in beside those to fill it up.
   */
  private class Work(val potion: Int, val step: Int, val bottles: Int, val standing: Boolean = false, val extra: Int = 0)

  /** A stand of a job: the load it brews now, the step it's at, and the loads waiting for their round. */
  private class Load(val block: BlockAt, var work: Work, val rest: ArrayDeque<Work>) {
    var step = work.step
    var rounds = 1
  }

  private class Job(
    override val task: Task, starter: ServerPlayer, val chains: List<List<Brewing.Brew>>, val loads: List<Load>, val sources: Sources, val to: Jobs.Output?,
  ) : Jobs.BlockJob {
    private val owner = starter.audience

    /** The player as the game has them now: a respawn makes a new one, and what goes to the old one is lost. */
    val player: ServerPlayer get() = owner.player

    override val blocks get() = loads.map { it.block }
    var delivered = 0

    fun chain(load: Load) = chains[load.work.potion]

    fun done(load: Load) = load.rest.isEmpty() && load.step >= chain(load).size

    override fun brewed(block: BlockAt) {
      val load = loads.firstOrNull { it.block == block } ?: return
      val chain = chain(load)
      if (load.step >= chain.size) return
      val brew = chain[load.step]
      val now = bottles(block).filterNotNull().map(::bottle)
      if (now.size != load.work.bottles || now.any { it != brew.to }) {
        return Jobs.automation.interfered(task, Jobs.automation.text(task, "automation.reason.changed", "block" to block.key, "x" to block.x, "y" to block.y, "z" to block.z))
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
        val news = if (to != null) {
          delivered += take(blocks).also { Jobs.deliver(player, it, to) }.sumOf { it.count }
          "brewing done: $made in ${loads.size} stands ($where); all $delivered went to ${Jobs.describe(to)}"
        } else {
          val gone = if (delivered > 0) "; $delivered from earlier rounds went to the inventory" else ""
          "brewing done: $made in ${loads.size} stands ($where)$gone; collect the rest"
        }
        task.report(news)
        Jobs.automation.post(task, "[job] $news")
      }
    }

    /** Every potion of the job with how many, for the report. */
    val made = mutableMapOf<String, Int>()

    private fun summary() = made.entries.joinToString(", ") { (what, n) -> "$n × $what" }

    /** A round is done: its potions go out, and the stand's next load goes in. False when someone took what it needs. */
    private fun next(load: Load): Boolean {
      delivered += take(listOf(load.block)).also { Jobs.deliver(player, it, to ?: Jobs.Output.Inventory) }.sumOf { it.count }
      val work = load.rest.removeFirst()
      val kind = chains[work.potion][work.step].from
      val (taken, short) = sources.takeReserved(potion(kind), key(kind), work.bottles, Jobs.automation.reservations, task.id)
      if (short != null || taken.sumOf { it.count } < work.bottles) {
        give(player, taken)
        Jobs.automation.interfered(task, Jobs.automation.text(task, "automation.reason.taken", "item" to key(kind), "place" to (short?.block?.let { "${it.key} ${it.x} ${it.y} ${it.z}" } ?: "?")))
        return false
      }
      taken.flatMap { stack -> List(stack.count) { stack.copyWithCount(1) } }.forEachIndexed { slot, bottle -> set(load.block, slot, bottle) }
      load.work = work
      load.step = work.step
      load.rounds++
      return feed(load)
    }

    /** Puts the reagent of the load's step into its stand, from what was reserved. False when someone took it. */
    fun feed(load: Load): Boolean {
      val reagent = InteractTools.known(chain(load)[load.step].reagent)!!
      val (taken, short) = sources.takeReserved(plain(reagent), Slots.id(ItemStack(reagent)), 1, Jobs.automation.reservations, task.id)
      if (short != null || taken.isEmpty()) {
        Jobs.automation.interfered(task, Jobs.automation.text(task, "automation.reason.taken", "item" to Slots.id(ItemStack(reagent)), "place" to (short?.block?.let { "${it.key} ${it.x} ${it.y} ${it.z}" } ?: "?")))
        return false
      }
      if (slot(load.block, INGREDIENT) != null) {
        give(player, taken)
        Jobs.automation.interfered(task, Jobs.automation.text(task, "automation.reason.changed", "block" to load.block.key, "x" to load.block.x, "y" to load.block.y, "z" to load.block.z))
        return false
      }
      set(load.block, INGREDIENT, taken.first().copyWithCount(1))
      return true
    }

    /** Loads that looked wrong at the last check: a step's end and the next reagent are a tick apart, so it takes two looks. */
    private val suspect = mutableSetOf<BlockAt>()

    override fun check() {
      for (load in loads) {
        if (done(load)) continue
        val stand = stand(load.block)
          ?: return Jobs.automation.interfered(task, Jobs.automation.text(task, "automation.reason.broken", "block" to "brewing_stand", "x" to load.block.x, "y" to load.block.y, "z" to load.block.z))
        // A stand of the job brews all the time but for a tick or two between steps. Standing still means someone took
        // the reagent or bottles (the game stops then, and the reagent stays), or put in what doesn't brew.
        if ((stand as BrewingAccess).`allay$brewTime`() > 0) {
          suspect -= load.block
        } else if (suspect.add(load.block).not()) {
          return Jobs.automation.interfered(task, Jobs.automation.text(task, "automation.reason.changed", "block" to load.block.key, "x" to load.block.x, "y" to load.block.y, "z" to load.block.z))
        }
      }
    }

    override fun cancel() {
      Jobs.remove(this)
    }
  }

  /** Takes every bottle out of [stands]. */
  private fun take(stands: List<BlockAt>): List<ItemStack> = stands.flatMap { block ->
    stand(block) ?: return@flatMap emptyList()
    (0..2).mapNotNull { index -> slot(block, index)?.copy()?.also { set(block, index, null) } }
  }

  /** Plants that always stand in a water source. */
  private val UNDERWATER = setOf(GameBlocks.KELP, GameBlocks.KELP_PLANT, GameBlocks.SEAGRASS, GameBlocks.TALL_SEAGRASS, GameBlocks.BUBBLE_COLUMN)

  /** A water source a glass bottle fills at, as the game's bottle sees it: flowing water doesn't count. */
  private fun source(block: BlockAt): Boolean {
    val state = block.state
    return (state.`is`(GameBlocks.WATER) && state.getValue(LiquidBlock.LEVEL) == 0) || state.block in UNDERWATER ||
      (state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED))
  }

  /** Water the player could fill bottles at near them: null for none, [Int.MAX_VALUE] for water itself, else the cauldrons' levels. */
  private fun water(player: ServerPlayer): Pair<Int, List<BlockAt>>? {
    val origin = player.blockPosition()
    val level = player.level()
    val cauldrons = mutableListOf<BlockAt>()
    var levels = 0
    for (dx in -WATER_REACH..WATER_REACH) for (dy in -WATER_REACH..WATER_REACH) for (dz in -WATER_REACH..WATER_REACH) {
      val block = level.at(origin.offset(dx, dy, dz))
      if (source(block)) return Int.MAX_VALUE to emptyList()
      if (block.state.`is`(GameBlocks.WATER_CAULDRON)) {
        cauldrons += block
        levels += block.state.getValue(LayeredCauldronBlock.LEVEL)
      }
    }
    return if (levels > 0) levels to cauldrons else null
  }

  /**
   * Fills up to [count] of the player's glass bottles with water, as clicking water does: from water near (endless) or
   * cauldrons, a level a bottle. Each water bottle goes back where its glass bottle was, else to the inventory. Returns
   * how many it filled and how many of those fell at the player's feet for want of room.
   */
  private fun fill(player: ServerPlayer, sources: Sources, count: Int, holds: chat.allay.core.tasks.Reservations, task: Task): Pair<Int, Int> {
    val (levels, cauldrons) = water(player) ?: return 0 to 0
    val n = minOf(count, levels, sources.free(plain(Items.GLASS_BOTTLE), "glass_bottle", holds, task.id))
    if (n <= 0) return 0 to 0
    val bottle = PotionContents.createItemStack(Items.POTION, Potions.WATER)
    var left = n
    var dropped = 0
    for (source in sources.list) {
      if (left <= 0) break
      // Glass bottles other tasks hold stay where they are.
      val free = (source.count(plain(Items.GLASS_BOTTLE)) - holds.held(source.place, "glass_bottle")).coerceAtLeast(0)
      val got = source.take(plain(Items.GLASS_BOTTLE), minOf(left, free)).sumOf { it.count }
      left -= got
      dropped += give(player, List(got) { bottle.copy() }, source)
    }
    val filled = n - left
    var lower = if (levels == Int.MAX_VALUE) 0 else filled
    for (cauldron in cauldrons) {
      if (lower <= 0) break
      val state = cauldron.state.takeIf { it.`is`(GameBlocks.WATER_CAULDRON) } ?: continue
      val now = state.getValue(LayeredCauldronBlock.LEVEL)
      val take = minOf(lower, now)
      lower -= take
      cauldron.level.setBlockAndUpdate(cauldron.pos, if (now - take <= 0) GameBlocks.CAULDRON.defaultBlockState() else state.setValue(LayeredCauldronBlock.LEVEL, now - take))
    }
    return filled to dropped
  }

  /** The potions a call asks for: `potions`, or `potion` with `form` and `bottles`. */
  private fun requests(args: JsonObject): List<Triple<List<Brewing.Brew>, Brewing.Bottle, Int?>> {
    val entries = (args["potions"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.map { Triple(it.string("potion") ?: throw ToolError("each of potions needs a potion"), it.string("form"), it.int("bottles")) }
      ?: listOfNotNull(args.string("potion")?.let { Triple(it, args.string("form"), args.int("bottles")) })
    if (entries.isEmpty()) throw ToolError("start needs potion or potions")
    val list = entries.map { (name, form, bottles) ->
      val (brews, target) = BrewingRecipes.target(name, form)
      Triple(Brewing.chain(brews, target) ?: throw ToolError("the brewing stand can't make ${target.item} of ${target.potion}"), target, bottles)
    }
    if (list.map { it.second }.toSet().size != list.size) throw ToolError("a potion is named twice")
    return list
  }

  private fun isStand(block: BlockAt) = block.state.`is`(GameBlocks.BREWING_STAND)

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
            val (brews, target) = BrewingRecipes.target(potion, args.string("form"))
            Brewing.chain(brews, target) ?: throw ToolError("the brewing stand can't make ${target.item} of ${target.potion}")
          }
          val marks = mutableListOf<Marks.Mark>()
          val rows = buildJsonArray {
            Blocks.near(player, settings.radius, ::isStand).forEach { block ->
              val state = stand(block) ?: return@forEach
              val reason = blocked(player, block, settings.radius, settings.lineOfSight)
              val (kind, count) = held(block) ?: (null to 0)
              val at = chain?.let { fit(kind, it) }
              add(buildJsonObject {
                put("x", block.x)
                put("y", block.y)
                put("z", block.z)
                putJsonArray("bottles") { bottles(block).forEach { item -> add(bottle(item)?.let { "${it.item} ${it.potion}" } ?: item?.let(Slots::id) ?: "empty") } }
                put("fuel", fuel(block, state))
                when {
                  reason != null -> put(if (reason.startsWith("busy")) "busy" else "unsuitable", reason)
                  chain != null && at == null -> put("unsuitable", mismatch(kind!!, count, listOf(chain)))
                  at != null -> put("starts_at_step", at + 1)
                }
              })
              marks += Marks.Mark(block, null, null, if (reason == null && (chain == null || at != null)) Marks.Hue.GREEN else Marks.Hue.RED)
            }
          }
          if (marks.isNotEmpty()) Marks.show(context.scheduler, player, marks, context.features.config().tools.marksSeconds)
          val sources = Sources.around(player, settings.radius)
          val result = buildJsonObject {
            put("stands", rows)
            put("note", "green glow: usable; red: busy or not fit; the player sees it")
            if (rows.isEmpty()) unreached(player, settings.radius, "brewing stands", ::isStand)
            putJsonObject("at_hand") {
              put("water_bottles", sources.count(water))
              put("glass_bottles", sources.count(plain(Items.GLASS_BOTTLE)))
              put("blaze_powder", sources.count(plain(Items.BLAZE_POWDER)))
              put("blaze_rod", sources.count(plain(Items.BLAZE_ROD)))
              chain?.map { it.reagent }?.distinct()?.forEach { id -> InteractTools.known(id)?.let { put(id, sources.count(plain(it))) } }
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
                  put("x", it.block.x)
                  put("y", it.block.y)
                  put("z", it.block.z)
                  job.chain(it).last().to.let { made ->
                    put("potion", made.potion)
                    if (made.item != "potion") put("form", made.item)
                  }
                  put("bottles", it.work.bottles)
                  put("step", minOf(it.step + 1, job.chain(it).size))
                  put("steps", job.chain(it).size)
                  put("rounds_left", it.rest.size)
                  put("done", job.done(it))
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
          if (taken.isNotEmpty()) task.report("collected ${Jobs.counts(taken)} from the stands to ${Jobs.describe(to)}")
          ToolOutput(buildJsonObject { put("collected", taken.sumOf { it.count }) }, lang.text("summary.collected", "count" to taken.sumOf { it.count }))
        }
        else -> throw ToolError("action must be scan, start, status, or collect, not $action")
      }
    }
  }

  /** The one kind of bottle a stand holds and how many: (null, 0) when it's empty, null when its bottles differ or aren't plain potions. */
  private fun held(block: BlockAt): Pair<Brewing.Bottle?, Int>? {
    val present = bottles(block).filterNotNull()
    if (present.isEmpty()) return null to 0
    val kind = present.map(::bottle).distinct().singleOrNull() ?: return null
    return kind to present.size
  }

  /** The step of [chain] bottles of [kind] start at: 0 for an empty stand, null when they aren't a step of it. */
  private fun fit(kind: Brewing.Bottle?, chain: List<Brewing.Brew>): Int? = if (kind == null) 0 else chain.indexOfFirst { it.from == kind }.takeIf { it >= 0 }

  /** Why [count] bottles of [kind] suit none of [chains], for the model. */
  private fun mismatch(kind: Brewing.Bottle, count: Int, chains: List<List<Brewing.Brew>>) =
    if (chains.any { it.lastOrNull()?.to == kind }) "it holds $count finished ${kind.item} ${kind.potion}; collect them first"
    else "its $count × ${kind.item} ${kind.potion} aren't a step toward this potion; the player takes them out first"

  /** Brews a stand has left: its fuel and the blaze powder waiting in its fuel slot. */
  private fun fuel(block: BlockAt, stand: BrewingStandBlockEntity) =
    (stand as BrewingAccess).`allay$fuel`() + (slot(block, FUEL)?.takeIf { it.item == Items.BLAZE_POWDER }?.count ?: 0) * BREWS_PER_POWDER

  /** Why a stand can't take a job now, for the model; null when it can. Busy ones say so first. */
  private fun blocked(player: ServerPlayer, block: BlockAt, radius: Int, sight: Boolean): String? {
    val stand = stand(block) ?: return "not a brewing stand"
    Blocks.unusable(player, block, radius, sight)?.let { return it.model }
    if (Jobs.busy(block)) return "busy: an agent's job uses it"
    val time = (stand as BrewingAccess).`allay$brewTime`()
    if (time > 0) return "busy: brewing, ${(time + 19) / 20} s left"
    slot(block, INGREDIENT)?.let { return "${it.count} × ${Slots.id(it)} sits in its ingredient slot; the player takes it out first" }
    if (held(block) == null) return "its bottles differ or aren't plain potions; the player takes them out first"
    return null
  }

  private fun start(context: ToolContext, player: ServerPlayer, task: Task, args: JsonObject, filled: Int = 0, dropped: Int = 0): ToolOutput {
    val automation = context.features.automation
    val settings = automation.settings()
    val holds = automation.reservations
    automation.agent(task).job?.let { if (Jobs.active(it)) throw ToolError("this task already runs a job; one per task") }
    val requests = requests(args)
    val chains = requests.map { it.first }
    val to = Jobs.target(player, args["to"], Kind.BREWING, context.features.settings, args.bool("remember") == true)
    val named = Blocks.list(player, args["stands"], settings.radius).ifEmpty { automation.picking.picked(player, task) }
    val skipped = mutableListOf<String>()
    val fitting = named.ifEmpty { Blocks.near(player, settings.radius, ::isStand) }
      .filter(::isStand)
      .sortedBy { it.corner.distanceToSqr(player.position()) }
      .mapNotNull { block ->
        val (kind, count) = held(block) ?: (null to 0)
        val reason = blocked(player, block, settings.radius, settings.lineOfSight)
          ?: kind?.takeIf { bottle -> chains.none { fit(bottle, it) != null } }?.let { mismatch(it, count, chains) }
        if (reason == null) Triple(block, kind, count) else null.also { skipped += "${block.x} ${block.y} ${block.z}: $reason" }
      }
    if (fitting.isEmpty()) throw ToolError(
      if (skipped.isEmpty()) "no brewing stand within ${settings.radius} blocks of the player"
      else "no stand near the player fits: ${skipped.take(6).joinToString("; ")}",
    )

    // Stands that already hold a step go on from there, filled up to three from the player's bottles of that kind;
    // stands of water bottles suit any potion. Only as many as the potion needs, so stands kept ready stay ready.
    // The rest take the player's own bottles of a later step first (their night vision potions when they want long
    // ones), then water; bottles is each potion's total, three to a stand.
    val sources = Sources.around(player, settings.radius)
    val standing = fitting.filter { it.third > 0 }
    val empty = fitting.filter { it.third == 0 }.map { it.first }
    val fresh = args.bool("fresh") == true
    val claimed = mutableMapOf<String, Int>()
    val firsts = mutableListOf<Pair<BlockAt, Work>>()
    val waiting = mutableListOf<Work>()
    val used = mutableSetOf<BlockAt>()
    requests.forEachIndexed { index, (chain, _, bottles) ->
      fun free(kind: Brewing.Bottle) = (sources.free(potion(kind), key(kind), holds, task.id) - (claimed[key(kind)] ?: 0)).coerceAtLeast(0)
      fun own() = if (fresh) emptyMap() else chain.indices.drop(1).associateWith { free(chain[it].from) }.filterValues { it > 0 }
      val here = standing.filter { it.first !in used }.mapNotNull { (block, kind, count) -> fit(kind, chain)?.let { block to Brewing.Held(it, count) } }
      val total = (bottles ?: (here.filter { it.second.step > 0 }.sumOf { it.second.bottles } + own().values.sum()).takeIf { it > 0 } ?: Brewing.STAND_BOTTLES).coerceIn(1, MAX_BOTTLES)
      var inStands = 0
      Brewing.standing(here.map { it.second }, total) { step, wanted ->
        minOf(wanted, free(chain[step].from)).also { if (it > 0) claimed.merge(key(chain[step].from), it, Int::plus) }
      }.forEach { (at, extra) ->
        val (block, held) = here[at]
        used += block
        firsts += block to Work(index, held.step, held.bottles + extra, standing = true, extra = extra)
        inStands += held.bottles + extra
      }
      Brewing.fill((total - inStands).coerceAtLeast(0), Int.MAX_VALUE, own()).forEach { fill ->
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
        val n = if (load.standing) load.extra else load.bottles
        if (n > 0) bottlesNeeded.merge(chains[load.potion][load.step].from, n, Int::plus)
        chains[load.potion].drop(load.step).forEach { needs.merge(it.reagent, 1, Int::plus) }
      }
      val brews = (listOf(work) + rest).sumOf { chains[it.potion].size - it.step }
      val fuel = fuel(block, stand(block)!!)
      (if (brews > fuel) (brews - fuel + BREWS_PER_POWDER - 1) / BREWS_PER_POWDER else 0).also { powder += it }
    }
    val missing = mutableMapOf<String, Int>()
    bottlesNeeded.forEach { (kind, n) -> (n - sources.free(potion(kind), key(kind), holds, task.id)).takeIf { it > 0 }?.let { missing[key(kind)] = it } }
    needs.forEach { (id, n) ->
      val material = InteractTools.known(id) ?: return@forEach
      (n - sources.free(plain(material), id, holds, task.id)).takeIf { it > 0 }?.let { missing[id] = it }
    }
    // Blaze powder may be a reagent too (strength); the fuel gets only what the reagents leave.
    val havePowder = (sources.free(plain(Items.BLAZE_POWDER), "blaze_powder", holds, task.id) - (needs["blaze_powder"] ?: 0)).coerceAtLeast(0)
    val rods = if (powder > havePowder) (powder - havePowder + 1) / 2 else 0
    if (rods > 0) {
      val haveRods = sources.free(plain(Items.BLAZE_ROD), "blaze_rod", holds, task.id)
      // In powder, on top of what the reagents miss: each rod at hand makes two.
      if (rods > haveRods) missing.merge("blaze_powder", powder - havePowder - haveRods * 2, Int::plus)
    }
    // Water bottles the player lacks are filled from their glass bottles when water is near, but only once nothing
    // else is missing, so a start that can't go ahead leaves the glass bottles as they were.
    var fillable = 0
    var water: String? = null
    missing["water_bottle"]?.let { short ->
      val near = water(player)
      val glass = sources.free(plain(Items.GLASS_BOTTLE), "glass_bottle", holds, task.id)
      fillable = if (near == null || filled > 0) 0 else minOf(short, near.first, glass)
      if (fillable == short && missing.size == 1) {
        val (made, fell) = fill(player, sources, short, holds, task)
        if (made > 0) return start(context, player, task, args, made, fell)
      }
      if (fillable == short) missing -= "water_bottle" else missing["water_bottle"] = short - fillable
      water = when {
        fillable == short -> null
        dropped > 0 -> "$dropped filled water bottles fell at the player's feet: the inventory is full; they pick them up or make room"
        near == null -> "no water source or filled cauldron within $WATER_REACH blocks to fill glass bottles at; the player has $glass glass bottles"
        glass < short -> "${short - glass} glass bottles short to fill at the water near"
        else -> "the cauldrons near hold only ${near.first} levels of water"
      }
    }
    if (missing.isNotEmpty()) {
      return ToolOutput(buildJsonObject {
        put("started", false)
        putJsonObject("missing") { missing.forEach { (id, n) -> put(id, n) } }
        if (filled > 0) put("filled_water_bottles", filled)
        water?.let { put("water", it) }
        if (fillable > 0) put("fills_water_bottles", "$fillable water bottles fill from the player's glass bottles at the water near once the rest is here")
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
    needs.forEach { (id, n) -> reserve(plain(InteractTools.known(id)!!), id, n) }
    reserve(plain(Items.BLAZE_POWDER), "blaze_powder", minOf(powder, havePowder))
    reserve(plain(Items.BLAZE_ROD), "blaze_rod", rods)
    val (rodsTaken, rodsShort) = sources.takeReserved(plain(Items.BLAZE_ROD), "blaze_rod", rods, holds, task.id)
    inHand += rodsTaken
    if (rodsShort != null) fail("the blaze rods changed meanwhile")
    val (powderTaken, powderShort) = sources.takeReserved(plain(Items.BLAZE_POWDER), "blaze_powder", minOf(powder, havePowder), holds, task.id)
    inHand += powderTaken
    if (powderShort != null) fail("the blaze powder changed meanwhile")
    // The first round's bottles come out before any stand changes, so a shortfall leaves the stands as they were.
    val bottlesInHand = plans.map { (_, work, _) ->
      val n = if (work.standing) work.extra else work.bottles
      if (n == 0) return@map emptyList<ItemStack>()
      val kind = chains[work.potion][work.step].from
      val (bottles, short) = sources.takeReserved(potion(kind), key(kind), n, holds, task.id)
      inHand += bottles
      if (short != null) fail("the ${key(kind)} changed meanwhile")
      bottles.flatMap { stack -> List(stack.count) { stack.copyWithCount(1) } }
    }
    // A blaze rod makes two powder, as the crafting table does.
    var powderPool = powderTaken.sumOf { it.count } + rodsTaken.sumOf { it.count } * 2
    val loads = plans.mapIndexed { index, (block, work, rest) ->
      // Into the free slots: a stand filled up keeps the bottles it held where they were.
      bottlesInHand[index].zip((0..2).filter { slot(block, it) == null }).forEach { (bottle, at) -> set(block, at, bottle) }
      val n = minOf(powderPool, powderPer[index])
      if (n > 0) {
        val fuel = slot(block, FUEL)
        set(block, FUEL, ItemStack(Items.BLAZE_POWDER, (fuel?.takeIf { it.item == Items.BLAZE_POWDER }?.count ?: 0) + n))
        powderPool -= n
      }
      Load(block, work, rest)
    }
    if (powderPool > 0) give(player, listOf(ItemStack(Items.BLAZE_POWDER, powderPool)))
    val job = Job(task, player, chains, loads, sources, to)
    plans.forEach { (_, work, rest) ->
      (listOf(work) + rest).forEach { load -> chains[load.potion].last().to.let { job.made.merge("${it.item} ${it.potion}", load.bottles, Int::plus) } }
    }
    for (load in loads) if (!job.feed(load)) return ToolOutput(buildJsonObject { put("started", false) }, "")
    automation.agent(task).job = job
    Jobs.add(job)
    Marks.show(context.scheduler, player, loads.map { Marks.Mark(it.block, null, chains[it.work.potion].last().to.potion.replace('_', ' '), Marks.Hue.PURPLE) }, context.features.config().tools.marksSeconds)
    val result = buildJsonObject {
      put("started", true)
      put("stands", buildJsonArray {
        loads.forEach { load ->
          add(buildJsonObject {
            put("x", load.block.x)
            put("y", load.block.y)
            put("z", load.block.z)
            put("potion", chains[load.work.potion].last().to.potion)
            put("from_step", load.step + 1)
            put("steps_left", chains[load.work.potion].size - load.step)
            put("bottles", load.work.bottles)
            if (load.rest.isNotEmpty()) put("rounds", load.rest.size + 1)
          })
        }
      })
      putJsonObject("potions") { job.made.forEach { (what, n) -> put(what, n) } }
      val theirs = plans.flatMap { (_, work, rest) -> listOf(work) + rest }.filter { it.step > 0 && (if (it.standing) it.extra else it.bottles) > 0 }.groupBy { chains[it.potion][it.step].from }
      if (theirs.isNotEmpty()) putJsonArray("from_player") { theirs.forEach { (kind, parts) -> add("${parts.sumOf { if (it.standing) it.extra else it.bottles }} × ${kind.item} ${kind.potion}") } }
      if (skipped.isNotEmpty()) putJsonArray("skipped_stands") { skipped.take(6).forEach { add(it) } }
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
