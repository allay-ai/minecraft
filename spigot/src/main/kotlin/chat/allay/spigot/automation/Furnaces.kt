package chat.allay.spigot.automation

import chat.allay.core.tasks.Kind
import chat.allay.core.tasks.Reservations
import chat.allay.core.tasks.Task
import chat.allay.core.tools.*
import chat.allay.spigot.tools.InteractTools
import chat.allay.spigot.tools.Marks
import chat.allay.spigot.tools.Tool
import chat.allay.spigot.tools.ToolContext

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.block.Block
import org.bukkit.block.Furnace
import org.bukkit.entity.ExperienceOrb
import org.bukkit.entity.Player
import org.bukkit.inventory.BlastingRecipe
import org.bukkit.inventory.CookingRecipe
import org.bukkit.inventory.FurnaceRecipe
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.SmokingRecipe
import kotlin.math.floor
import kotlin.random.Random

/**
 * `smelt`: loads furnaces, blast furnaces, and smokers near the player evenly, with exactly the fuel the load needs,
 * and keeps them going from what it reserved until everything is smelted. With an output it empties them on the way
 * and at the end, and the player gets the experience the game gives for it. `fuel` puts in fuel alone, spread evenly.
 */
object Furnaces {
  /** The cooking recipe [block] uses for [item], or null when it can't cook it: a blast furnace takes ores, a smoker food. */
  fun recipe(server: Server, block: Block, item: ItemStack): CookingRecipe<*>? {
    val kind = when (block.type) {
      Material.BLAST_FURNACE -> BlastingRecipe::class.java
      Material.SMOKER -> SmokingRecipe::class.java
      else -> FurnaceRecipe::class.java
    }
    val iterator = server.recipeIterator()
    while (iterator.hasNext()) {
      val recipe = iterator.next()
      if (kind.isInstance(recipe) && (recipe as CookingRecipe<*>).inputChoice.test(item)) return recipe
    }
    return null
  }

  /** Whether a plain furnace cooks [type]: every blast furnace and smoker recipe has one too. */
  private fun cooks(server: Server, type: Material): Boolean {
    val item = ItemStack(type)
    val iterator = server.recipeIterator()
    while (iterator.hasNext()) {
      val recipe = iterator.next()
      if (recipe is FurnaceRecipe && recipe.inputChoice.test(item)) return true
    }
    return false
  }

  private fun furnace(block: Block) = block.state as? Furnace

  /** Nothing cooking and no job on it. Output may wait there: a load that makes the same joins it. */
  private fun free(block: Block) = furnace(block)?.inventory?.smelting == null && !Jobs.busy(block)

  private fun plain(type: Material): (ItemStack) -> Boolean = { it.type == type && Sources.plain(it) }

  /** The fuels to try when the agent names none, cheapest to waste first; the planks, sticks, and bamboo at hand follow. */
  private val FUEL_ORDER = listOf("coal", "charcoal", "coal_block", "blaze_rod", "dried_kelp_block")

  /** What the agent may burn without being told: [FUEL_ORDER], then the planks the player has, sticks, and bamboo. Logs and lava only when asked. */
  private fun choices(sources: Sources): List<String> {
    val planks = sources.list.flatMap { it.slots() }.filterNotNull().filter(Sources::plain).map { it.type.key.key }
      .filter { it.endsWith("_planks") && Workshop.wood(it) }.distinct().sorted()
    return FUEL_ORDER + planks + listOf("stick", "bamboo")
  }

  /** One furnace of a job: what it cooks, what's in its input now, and how much of its share is still to come. */
  private class Load(val block: Block, val item: Material, val result: ItemStack, var expected: Int, var share: Int) {
    /** The share it got in all, for the report. */
    var first = share
  }

  private class Job(
    override val task: Task, val player: Player, val loads: List<Load>, var sources: Sources, var total: Int, val fuel: String, val to: Jobs.Output?,
  ) : Jobs.BlockJob {
    override val blocks get() = loads.map { it.block }
    var smelted = 0
    var reportedFull = false
    var collected = 0
    var experience = 0

    override fun smelted(block: Block) {
      val load = loads.firstOrNull { it.block == block } ?: return
      val inventory = furnace(block)?.inventory ?: return
      load.expected--
      load.share--
      smelted++
      val item = load.item
      val input = inventory.smelting
      if ((input?.amount ?: 0) != load.expected || (input != null && input.type != item)) {
        return Jobs.automation.interfered(task, Jobs.automation.text(task, "automation.reason.changed", "block" to block.type.key.key, "x" to block.x, "y" to block.y, "z" to block.z))
      }
      // Refill from what was reserved, as far as this furnace's share goes.
      if (load.expected == 0 && load.share > 0) {
        val (taken, short) = sources.takeReserved(plain(item), item.key.key, minOf(64, load.share), Jobs.automation.reservations, task.id)
        if (short != null) return Jobs.automation.interfered(task, Jobs.automation.text(task, "automation.reason.taken", "item" to item.key.key, "place" to where(short)))
        val amount = taken.sumOf { it.amount }
        if (amount > 0) {
          inventory.smelting = ItemStack(item, amount)
          load.expected = amount
        }
      }
      val output = inventory.result
      if (output != null && output.amount >= output.maxStackSize && load.share > 0) {
        // With an output the job empties a full furnace itself; without one the agent hears about it once.
        if (to != null) {
          collect(listOf(block))
        } else if (!reportedFull) {
          reportedFull = true
          Jobs.automation.post(task, "[job] the furnace at ${block.x} ${block.y} ${block.z} is full of ${output.type.key.key}: collect it to go on")
        }
      }
      if (smelted >= total) {
        Jobs.remove(this)
        val made = loads.groupBy { it.result.type.key.key }.entries.joinToString(", ") { (id, list) -> "${list.sumOf { load -> load.first }} × $id" }
        val where = loads.joinToString("; ") { "${it.block.x} ${it.block.y} ${it.block.z}" }
        val news = if (to != null) {
          collect(blocks)
          "smelting done: $made in ${loads.size} furnaces ($where); collected $collected to ${Jobs.describe(to)}, with $experience experience for the player"
        } else {
          "smelting done: $made in ${loads.size} furnaces ($where); the output waits in them: collect it"
        }
        task.report(news)
        Jobs.automation.post(task, "[job] $news")
      }
    }

    /** Empties [furnaces] to the job's output, with the experience. */
    fun collect(furnaces: List<Block>) {
      val (items, xp) = takeOutputs(player, furnaces)
      collected += items.filter { it.type != Material.BUCKET }.sumOf { it.amount }
      experience += xp
      Jobs.deliver(player, items, to ?: Jobs.Output.Inventory)
    }

    /** Furnaces that looked wrong at the last check: a smelt and the job's count are a tick apart, so it takes two looks. */
    private val suspect = mutableSetOf<Block>()

    override fun check() {
      for (load in loads) {
        val inventory = furnace(load.block)?.inventory
          ?: return Jobs.automation.interfered(task, Jobs.automation.text(task, "automation.reason.broken", "block" to "furnace", "x" to load.block.x, "y" to load.block.y, "z" to load.block.z))
        val input = inventory.smelting?.takeIf { it.type == load.item }?.amount ?: 0
        if (input >= load.expected) {
          suspect -= load.block
        } else if (!suspect.add(load.block)) {
          return Jobs.automation.interfered(task, Jobs.automation.text(task, "automation.reason.changed", "block" to load.block.type.key.key, "x" to load.block.x, "y" to load.block.y, "z" to load.block.z))
        }
      }
    }

    override fun cancel() {
      Jobs.remove(this)
    }
  }

  /**
   * Takes the output of [furnaces] and the empty buckets lava left in their fuel slots, and pays [player] the experience
   * the game gives for taking the output. Returns the items and the experience.
   */
  private fun takeOutputs(player: Player, furnaces: List<Block>): Pair<List<ItemStack>, Int> {
    var xp = 0
    val items = furnaces.flatMap { block ->
      val inventory = furnace(block)?.inventory ?: return@flatMap emptyList()
      val result = inventory.result?.also { inventory.result = null }
      val bucket = inventory.fuel?.takeIf { it.type == Material.BUCKET }?.also { inventory.fuel = null }
      if (result != null) xp += experience(player, block)
      listOfNotNull(result, bucket)
    }
    return items to xp
  }

  /**
   * The experience [block] holds for what it cooked, paid out to [player] as an orb, the way taking the output does in
   * the game. That needs the store emptied, which Paper allows (`setRecipesUsed`); on plain Spigot the experience
   * stays in the furnace, for whoever takes from it by hand next, so nothing is paid twice. Server thread.
   */
  private fun experience(player: Player, block: Block): Int {
    // A fresh state after the output left, so writing it back can't bring the output back.
    val state = furnace(block) ?: return 0
    val clear = runCatching { state.javaClass.getMethod("setRecipesUsed", Map::class.java) }.getOrNull() ?: return 0
    val used = state.recipesUsed
    if (used.isEmpty()) return 0
    val points = used.entries.sumOf { (recipe, count) -> recipe.experience.toDouble() * count }
    var whole = floor(points).toInt()
    if (Random.nextDouble() < points - whole) whole++
    runCatching { clear.invoke(state, emptyMap<CookingRecipe<*>, Int>()); state.update() }.onFailure { return 0 }
    if (whole > 0) player.world.spawn(player.location, ExperienceOrb::class.java) { it.experience = whole }
    return whole
  }

  private fun where(source: Sources.Source) = source.block?.let { "${it.type.key.key} ${it.x} ${it.y} ${it.z}" } ?: source.place.substringBefore(':')

  /**
   * Reserves and takes [amount] of [fuel] for [task] into [inHand]. Coal comes from coal blocks too when coal runs
   * short, split as the crafting table does, and the rest of a block goes back where it was. Calls [fail] when
   * someone took it meanwhile; returns how many coal blocks were split.
   */
  private fun takeFuel(player: Player, sources: Sources, holds: Reservations, task: Task, fuel: String, amount: Int, inHand: MutableList<ItemStack>, fail: (String) -> Nothing): Int {
    if (amount <= 0) return 0
    val type = Material.matchMaterial(fuel)!!
    val fromItems = if (type == Material.COAL) minOf(amount, sources.free(plain(Material.COAL), "coal", holds, task.id)) else amount
    val blocks = if (type == Material.COAL) (amount - fromItems + 8) / 9 else 0
    if (fromItems > 0 && !sources.reserve(plain(type), fuel, fromItems, holds, task.id)) fail("someone took the $fuel meanwhile")
    if (blocks > 0 && !sources.reserve(plain(Material.COAL_BLOCK), "coal_block", blocks, holds, task.id)) fail("someone took the coal blocks meanwhile")
    if (fromItems > 0) {
      val (taken, short) = sources.takeReserved(plain(type), fuel, fromItems, holds, task.id)
      inHand += taken
      if (short != null) fail("the $fuel changed meanwhile")
    }
    if (blocks > 0) {
      val source = sources.list.firstOrNull { it.count(plain(Material.COAL_BLOCK)) > 0 }
      val (split, short) = sources.takeReserved(plain(Material.COAL_BLOCK), "coal_block", blocks, holds, task.id)
      inHand += split
      if (short != null) fail("the coal blocks changed meanwhile")
      inHand.removeAll(split)
      inHand += ItemStack(Material.COAL, amount - fromItems)
      val left = blocks * 9 - (amount - fromItems)
      if (left > 0) give(player, listOf(ItemStack(Material.COAL, left)), source)
    }
    return blocks
  }

  /** How much of [fuel] the player could put in, coal blocks counted as nine coal. */
  private fun fuelAtHand(sources: Sources, holds: Reservations, task: Task, fuel: String): Int {
    val type = Material.matchMaterial(fuel) ?: return 0
    val have = sources.free(plain(type), fuel, holds, task.id)
    return if (type == Material.COAL) have + sources.free(plain(Material.COAL_BLOCK), "coal_block", holds, task.id) * 9 else have
  }

  /** One item or several: `item` and `count`, or `items`. */
  private fun asked(args: JsonObject): List<Pair<Material, Int?>> {
    val list = (args["items"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.map { entry ->
      InteractTools.material(entry.string("item") ?: throw ToolError("each of items needs an item")) to entry.int("count")
    } ?: listOf(InteractTools.material(args.string("item") ?: throw ToolError("item or items is required, like raw_gold")) to args.int("count"))
    if (list.map { it.first }.toSet().size != list.size) throw ToolError("an item is named twice")
    return list
  }

  val tool = object : Tool {
    override val name = "smelt"

    override fun run(context: ToolContext, args: JsonObject): ToolOutput {
      val player = context.requirePlayer()
      val task = context.requireTask()
      val automation = context.features.automation
      val settings = automation.settings()
      val lang = context.lang
      automation.require(player, task, Kind.FURNACES)
      val around = { Blocks.near(player.location, settings.radius) { Blocks.furnace(it.type) } }
      return when (val action = args.string("action")?.lowercase()) {
        "scan" -> {
          val item = args.string("item")?.let(InteractTools::material)
          val marks = mutableListOf<Marks.Mark>()
          val rows = buildJsonArray {
            around().forEach { block ->
              val inventory = furnace(block)?.inventory ?: return@forEach
              val unfit = Blocks.unusable(player, block, settings.radius, settings.lineOfSight)
              val busy = unfit == null && !free(block)
              add(buildJsonObject {
                put("x", block.x); put("y", block.y); put("z", block.z); put("type", block.type.key.key)
                when {
                  unfit != null -> put("unsuitable", unfit.model)
                  busy -> put("busy", listOfNotNull(inventory.smelting?.let { "cooking ${it.amount} ${it.type.key.key}" }, inventory.result?.let { "output ${it.amount} ${it.type.key.key}" }).joinToString(", "))
                  else -> {
                    put("free", true)
                    inventory.result?.let { put("output_waiting", "${it.amount} ${it.type.key.key}") }
                  }
                }
                inventory.fuel?.let { put("fuel", "${it.amount} ${it.type.key.key}") }
                if (item != null && unfit == null) put("cooks_item", recipe(player.server, block, ItemStack(item)) != null)
              })
              marks += Marks.Mark(block, null, null, if (unfit == null && !busy) Marks.Hue.GREEN else Marks.Hue.RED)
            }
          }
          if (marks.isNotEmpty()) Marks.show(context.plugin, player, marks, context.features.config().tools.marksSeconds)
          val sources = Sources.around(player, settings.radius)
          val result = buildJsonObject {
            put("furnaces", rows)
            put("note", "green glow: free; red: busy or unusable; the player sees it")
            if (rows.isEmpty()) unreached(player, settings.radius, "furnaces") { Blocks.furnace(it.type) }
            item?.let { put("player_has", sources.free(plain(it), it.key.key, automation.reservations, task.id)) }
            // What the player could smelt right now, so the agent needn't search for it.
            val smeltable = sources.list.flatMap { it.slots() }.filterNotNull().filter(Sources::plain).map { it.type }.distinct()
              .filter { type -> cooks(player.server, type) }
            putJsonObject("to_smelt_at_hand") { smeltable.forEach { type -> sources.free(plain(type), type.key.key, automation.reservations, task.id).takeIf { it > 0 }?.let { put(type.key.key, it) } } }
            putJsonObject("fuel_at_hand") {
              (choices(sources) + "lava_bucket").forEach { id -> Material.matchMaterial(id)?.let { m -> sources.count(plain(m)).takeIf { it > 0 }?.let { put(id, it) } } }
            }
            Jobs.saved(player, Kind.FURNACES, context.features.settings)?.let { put("player_output", it) }
          }
          ToolOutput(result, lang.text("summary.furnaces", "count" to rows.size))
        }
        "start" -> start(context, player, task, args)
        "add" -> add(context, player, task, args)
        "fuel" -> fuel(context, player, task, args)
        "status" -> {
          val job = automation.agent(task).job as? Job ?: throw ToolError("no smelting running for this task")
          ToolOutput(buildJsonObject {
            put("smelted", job.smelted)
            put("total", job.total)
            putJsonObject("left") { job.loads.groupBy { it.item.key.key }.forEach { (id, list) -> put(id, list.sumOf { it.share }) } }
            job.to?.let { put("output", Jobs.describe(it)); put("collected", job.collected) }
          }, lang.text("summary.smelting", "done" to job.smelted, "total" to job.total))
        }
        "collect" -> {
          val to = Jobs.target(player, args["to"], Kind.FURNACES, context.features.settings, args.bool("remember") == true)
            ?: throw ToolError("to is required: \"inventory\", \"auto\", or chests; ask the player")
          val job = automation.agent(task).job as? Job
          val blocks = Blocks.list(player, args["furnaces"], settings.radius).ifEmpty { job?.blocks?.toList().orEmpty() }
          if (blocks.isEmpty()) throw ToolError("which furnaces? pass furnaces")
          blocks.forEach { block -> Blocks.unusable(player, block, settings.radius, false)?.let { throw ToolError("the furnace at ${block.x} ${block.y} ${block.z} can't be used: ${it.model}") } }
          val (items, xp) = takeOutputs(player, blocks)
          Jobs.deliver(player, items, to)
          job?.reportedFull = false
          val count = items.filter { it.type != Material.BUCKET }.sumOf { it.amount }
          if (count > 0) task.report("collected ${Jobs.counts(items)} from the furnaces to ${Jobs.describe(to)}")
          ToolOutput(buildJsonObject { put("collected", count); put("experience", xp); put("to", Jobs.describe(to)) }, lang.text("summary.collected", "count" to count))
        }
        else -> throw ToolError("action must be scan, start, add, fuel, status, or collect, not $action")
      }
    }
  }

  private fun start(context: ToolContext, player: Player, task: Task, args: JsonObject): ToolOutput {
    val automation = context.features.automation
    val settings = automation.settings()
    val holds = automation.reservations
    automation.agent(task).job?.let { if (Jobs.active(it)) throw ToolError("this task already runs a job; add more to it with action add") }
    // One item, or several at once: “smelt the iron and the gold”.
    val asked = asked(args)
    val to = Jobs.target(player, args["to"], Kind.FURNACES, context.features.settings, args.bool("remember") == true)
    val named = Blocks.list(player, args["furnaces"], settings.radius).ifEmpty { automation.picking.picked(player, task) }
    val usable = named.ifEmpty { Blocks.near(player.location, settings.radius) { Blocks.furnace(it.type) } }
      .filter { Blocks.furnace(it.type) && Blocks.unusable(player, it, settings.radius, settings.lineOfSight) == null && free(it) }
    val sources = Sources.around(player, settings.radius)
    // Items the player doesn't have are left out, so “all my iron” may name every iron ore there is.
    val missing = mutableListOf<String>()
    val counts = asked.mapNotNull { (item, count) ->
      val available = sources.free(plain(item), item.key.key, holds, task.id)
      if (available <= 0) missing += item.key.key
      if (available <= 0) null else item to minOf(count ?: available, available)
    }.toMap()
    if (counts.isEmpty()) throw ToolError("the player has none of ${missing.joinToString()} at hand (inventory, bundles, ender chest, chests near)")
    val wanted = args.string("fuel")?.let(Pins::normalize)
    if (wanted != null && Workshop.burnTicks(wanted) == null) throw ToolError("$wanted isn't a furnace fuel")
    val fuels = if (wanted != null) listOf(wanted) else choices(sources)

    // Furnaces holding another fuel they'd need more of are left out and the split runs again without them.
    val skipped = mutableListOf<String>()
    var candidates = usable
    lateinit var used: List<Triple<Block, Material, Int>>
    lateinit var recipes: Map<Block, Map<Material, CookingRecipe<*>?>>
    lateinit var fuel: String
    lateinit var perFurnace: List<Int>
    while (true) {
      // A furnace with output waiting takes only what makes the same, so the output can grow.
      recipes = candidates.associateWith { block -> counts.keys.associateWith { item -> recipe(player.server, block, ItemStack(item)) } }
      val ovens = candidates.map { block ->
        val waiting = furnace(block)!!.inventory.result
        Workshop.Oven(counts.keys.filter { item ->
          val recipe = recipes[block]!![item] ?: return@filter false
          waiting == null || waiting.isSimilar(recipe.result)
        }.associate { it.key.key to if (block.type == Material.FURNACE) 1 else 2 })
      }
      val parts = Workshop.allot(counts.mapKeys { it.key.key.key }, ovens)
        ?: throw ToolError("no free furnace near the player can cook ${counts.keys.filter { item -> candidates.none { recipes[it]!![item] != null } }.joinToString { it.key.key }}" +
          if (skipped.isNotEmpty()) "; left out: ${skipped.joinToString("; ")}" else "")
      used = candidates.indices.mapNotNull { index -> parts[index]?.let { part -> Triple(candidates[index], Material.matchMaterial(part.item)!!, part.count) } }
      // Fuel: the one asked for, or the first that covers every furnace's share.
      val need = { id: String -> used.sumOf { (block, _, share) -> Workshop.fuelFor(share, id, banked(block)) } }
      fuel = fuels.firstOrNull { id -> need(id).let { n -> n == 0 || fuelAtHand(sources, holds, task, id) >= n } }
        ?: throw ToolError("not enough fuel at hand for ${used.sumOf { it.third }} items; fuel_at_hand in scan shows what there is")
      val type = Material.matchMaterial(fuel)!!
      perFurnace = used.map { (block, _, share) -> Workshop.fuelFor(share, fuel, banked(block)) }
      val clash = used.indices.filter { perFurnace[it] > 0 && furnace(used[it].first)!!.inventory.fuel?.let { there -> there.type != type } == true }
      if (clash.isEmpty()) break
      clash.forEach { skipped += "${spot(used[it].first)}: burns ${furnace(used[it].first)!!.inventory.fuel!!.type.key.key}" }
      candidates = candidates - clash.map { used[it].first }.toSet()
    }
    val fuelType = Material.matchMaterial(fuel)!!
    val total = used.sumOf { it.third }
    used.forEachIndexed { index, (block, _, _) ->
      val there = furnace(block)!!.inventory.fuel?.amount ?: 0
      if (perFurnace[index] > 0 && there + perFurnace[index] > fuelType.maxStackSize) {
        throw ToolError(if (fuelType == Material.LAVA_BUCKET) "a bucket of lava cooks 100 items; the furnace at ${block.x} ${block.y} ${block.z} would need more: use more furnaces or another fuel"
          else "the fuel slot of the furnace at ${block.x} ${block.y} ${block.z} can't hold that much $fuel; use more furnaces or a stronger fuel")
      }
    }

    // Reserve everything, then take everything; only when all of it is in hand does anything go into a furnace.
    // Whatever fails on the way goes back to the player and frees the holds, so nothing is lost.
    val inHand = mutableListOf<ItemStack>()
    val firsts = used.map { (_, item, share) -> item to minOf(64, share) }
    fun fail(reason: String): Nothing {
      give(player, inHand)
      holds.release(task.id)
      throw ToolError(reason)
    }
    used.groupBy { it.second }.forEach { (item, list) ->
      if (!sources.reserve(plain(item), item.key.key, list.sumOf { it.third }, holds, task.id)) fail("someone took the ${item.key.key} meanwhile")
    }
    val fuelNeeded = perFurnace.sum()
    val split = takeFuel(player, sources, holds, task, fuel, fuelNeeded, inHand, ::fail)
    val loadsInHand = firsts.map { (item, first) ->
      val (taken, short) = sources.takeReserved(plain(item), item.key.key, first, holds, task.id)
      inHand += taken
      if (short != null) fail("the ${item.key.key} changed meanwhile")
      taken.sumOf { it.amount }
    }

    // Everything is here: each furnace gets its share up to a stack, and its fuel.
    val loads = used.mapIndexed { index, (block, item, share) ->
      val inventory = furnace(block)!!.inventory
      inventory.smelting = ItemStack(item, loadsInHand[index])
      if (perFurnace[index] > 0) inventory.fuel = ItemStack(fuelType, (inventory.fuel?.amount ?: 0) + perFurnace[index])
      Load(block, item, recipes[block]!![item]!!.result, loadsInHand[index], share)
    }
    val job = Job(task, player, loads, sources, total, fuel, to)
    automation.agent(task).job = job
    Jobs.add(job)
    Marks.show(context.plugin, player, loads.map { Marks.Mark(it.block, null, "${it.share} × ${it.item.key.key.replace('_', ' ')}", Marks.Hue.ORANGE) }, context.features.config().tools.marksSeconds)
    val out = buildJsonObject {
      put("smelting", total)
      if (missing.isNotEmpty()) put("not_at_hand", buildJsonArray { missing.forEach { add(JsonPrimitive(it)) } })
      putJsonObject("makes") { loads.groupBy { it.result.type.key.key }.forEach { (id, list) -> put(id, list.sumOf { it.share }) } }
      put("fuel", fuel)
      put("fuel_used", fuelNeeded)
      if (split > 0) put("coal_blocks_split", split)
      put("furnaces", buildJsonArray { loads.forEach { load -> add(buildJsonObject { put("x", load.block.x); put("y", load.block.y); put("z", load.block.z); put("type", load.block.type.key.key); put("item", load.item.key.key); put("share", load.share) }) } })
      if (skipped.isNotEmpty()) put("skipped", buildJsonArray { skipped.forEach { add(JsonPrimitive(it)) } })
      to?.let { put("output", Jobs.describe(it)) }
      put("note", "the split weighs each furnace's speed, so they finish about together; wait now, you wake when it's done" +
        if (to != null) ". Full furnaces empty themselves to the output, and the rest goes there at the end, so there's no collect to do" else " or a furnace is full")
    }
    return ToolOutput(out, context.lang.text("summary.smelting_started", "count" to total, "furnaces" to loads.size))
  }

  /**
   * `add`: more of what a running job cooks, spread over its furnaces of that item, with the fuel that takes. A
   * furnace that already finished its share starts again.
   */
  private fun add(context: ToolContext, player: Player, task: Task, args: JsonObject): ToolOutput {
    val automation = context.features.automation
    val settings = automation.settings()
    val holds = automation.reservations
    val job = automation.agent(task).job as? Job ?: throw ToolError("no smelting running for this task; start one")
    if (!Jobs.active(job)) throw ToolError("the smelting already ended; start a new one")
    val sources = Sources.around(player, settings.radius)
    val fuelType = Material.matchMaterial(job.fuel)!!
    val inHand = mutableListOf<ItemStack>()
    fun fail(reason: String): Nothing {
      give(player, inHand)
      throw ToolError(reason)
    }
    // Plan every item first, so a refusal leaves the job as it was.
    class Extra(val load: Load, val count: Int, val fuel: Int)
    val missing = mutableListOf<String>()
    val extras = asked(args).flatMap { (item, count) ->
      val loads = job.loads.filter { it.item == item }
      if (loads.isEmpty()) throw ToolError("this job cooks ${job.loads.map { it.item.key.key }.distinct().joinToString()}; ${item.key.key} needs its own start once this one ends")
      val available = sources.free(plain(item), item.key.key, holds, task.id)
      if (available <= 0) missing += item.key.key
      val n = minOf(count ?: available, available)
      val shares = Workshop.fill(n, loads.map { Int.MAX_VALUE / 2 })
      loads.mapIndexed { index, load -> Extra(load, shares[index], Workshop.fuelFor(load.share + shares[index], job.fuel, banked(load.block))) }.filter { it.count > 0 }
    }
    if (extras.isEmpty()) throw ToolError("the player has none of ${missing.joinToString()} at hand")
    extras.forEach { extra ->
      val there = furnace(extra.load.block)!!.inventory.fuel
      if (extra.fuel > 0 && there != null && there.type != fuelType) throw ToolError("the furnace at ${extra.load.block.x} ${extra.load.block.y} ${extra.load.block.z} burns ${there.type.key.key} now")
      if ((there?.amount ?: 0) + extra.fuel > fuelType.maxStackSize) throw ToolError("the furnace at ${extra.load.block.x} ${extra.load.block.y} ${extra.load.block.z} can't hold the fuel for that much; start another task with more furnaces")
    }
    val fuelNeeded = extras.sumOf { it.fuel }
    if (fuelNeeded > fuelAtHand(sources, holds, task, job.fuel)) throw ToolError("not enough ${job.fuel} at hand for that much more")
    extras.groupBy { it.load.item }.forEach { (item, list) ->
      if (!sources.reserve(plain(item), item.key.key, list.sumOf { it.count }, holds, task.id)) fail("someone took the ${item.key.key} meanwhile")
    }
    takeFuel(player, sources, holds, task, job.fuel, fuelNeeded, inHand, ::fail)
    job.sources = sources
    extras.forEach { extra ->
      val load = extra.load
      val inventory = furnace(load.block)!!.inventory
      if (extra.fuel > 0) inventory.fuel = ItemStack(fuelType, (inventory.fuel?.amount ?: 0) + extra.fuel)
      load.share += extra.count
      load.first += extra.count
      job.total += extra.count
      // A furnace that finished its share has nothing in it to set off the refill: it gets its first stack now.
      if (load.expected == 0) {
        val (taken, short) = sources.takeReserved(plain(load.item), load.item.key.key, minOf(64, load.share), holds, task.id)
        if (short != null) return@forEach automation.interfered(task, automation.text(task, "automation.reason.taken", "item" to load.item.key.key, "place" to where(short)))
        val amount = taken.sumOf { it.amount }
        if (amount > 0) {
          inventory.smelting = ItemStack(load.item, amount)
          load.expected = amount
        }
      }
    }
    val out = buildJsonObject {
      putJsonObject("added") { extras.groupBy { it.load.item.key.key }.forEach { (id, list) -> put(id, list.sumOf { it.count }) } }
      put("total", job.total)
      put("fuel_used", fuelNeeded)
      if (missing.isNotEmpty()) put("not_at_hand", buildJsonArray { missing.forEach { add(JsonPrimitive(it)) } })
    }
    return ToolOutput(out, context.lang.text("summary.smelting_started", "count" to extras.sumOf { it.count }, "furnaces" to extras.map { it.load }.distinct().size))
  }

  /**
   * `fuel`: fuel alone, nothing to cook. How much: count spread evenly, each added to every furnace, up_to a level in
   * every fuel slot, or need, just what each furnace needs for what it's cooking now. Which: furnaces named or picked,
   * else every usable one around, of the types asked. The fuel: the one asked, else what the furnaces already burn,
   * else the cheapest at hand; coal blocks split into coal when the coal runs short, and leftovers go back. A bucket of
   * lava goes only into an empty fuel slot, one to a furnace.
   */
  private fun fuel(context: ToolContext, player: Player, task: Task, args: JsonObject): ToolOutput {
    val automation = context.features.automation
    val settings = automation.settings()
    val holds = automation.reservations
    val count = args.int("count")
    val each = args.int("each")
    val upTo = args.int("up_to")
    val need = args.bool("need") == true
    if (listOf(count != null, each != null, upTo != null, need).count { it } != 1) throw ToolError("say how much with exactly one of count (in all, spread evenly), each (added to every furnace), up_to (the level each fuel slot should reach), or need: true")
    val types = (args["types"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.let(Pins::normalize) }?.toSet().orEmpty()
    val named = Blocks.list(player, args["furnaces"], settings.radius).ifEmpty { automation.picking.picked(player, task) }
    val skipped = mutableListOf<String>()
    val around = named.ifEmpty { Blocks.near(player.location, settings.radius) { Blocks.furnace(it.type) } }
      .filter { Blocks.furnace(it.type) && (types.isEmpty() || it.type.key.key in types) }
      .filter { block -> Blocks.unusable(player, block, settings.radius, settings.lineOfSight)?.let { skipped += "${spot(block)}: ${it.model}"; false } ?: true }
    if (around.isEmpty()) throw ToolError("no usable furnace near the player${if (types.isNotEmpty()) " of ${types.joinToString()}" else ""}${if (skipped.isNotEmpty()) "; ${skipped.joinToString("; ")}" else ""}")
    val sources = Sources.around(player, settings.radius)
    val asked = args.string("fuel")?.let(Pins::normalize)
    val id = asked
      ?: around.mapNotNull { furnace(it)?.inventory?.fuel?.type?.key?.key }.filter { Workshop.burnTicks(it) != null }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
      ?: choices(sources).firstOrNull { fuel -> fuelAtHand(sources, holds, task, fuel) > 0 }
      ?: throw ToolError("the player has no fuel at hand (inventory, bundles, ender chest, chests near)")
    val type = Material.matchMaterial(id)?.takeIf { Workshop.burnTicks(id) != null } ?: throw ToolError("$id isn't a furnace fuel")
    // A fuel slot holds one kind: furnaces burning another are left as they are.
    val furnaces = around.filter { block ->
      val there = furnace(block)?.inventory?.fuel
      when {
        there == null || there.type == type -> true
        else -> false.also { skipped += "${spot(block)}: burns ${there.type.key.key}" }
      }
    }
    if (furnaces.isEmpty()) throw ToolError("every furnace near the player burns another fuel: ${skipped.joinToString("; ")}")
    val room = furnaces.map { type.maxStackSize - (furnace(it)!!.inventory.fuel?.amount ?: 0) }
    val have = fuelAtHand(sources, holds, task, id)
    if (have <= 0) throw ToolError("the player has no $id at hand (inventory, bundles, ender chest, chests near)")
    val wanted = when {
      count != null -> Workshop.fill(count, room)
      each != null -> room.map { minOf(it, each) }
      upTo != null -> furnaces.mapIndexed { index, block -> minOf(room[index], (upTo.coerceAtMost(type.maxStackSize) - (furnace(block)!!.inventory.fuel?.amount ?: 0)).coerceAtLeast(0)) }
      // What's in the input slot now, less what already burns or waits: blast furnaces cook twice as fast and burn twice as fast, so it's the same.
      else -> furnaces.mapIndexed { index, block -> minOf(room[index], Workshop.fuelFor(furnace(block)!!.inventory.smelting?.amount ?: 0, id, banked(block))) }
    }
    // Short of fuel: as evenly as what there is allows.
    val shares = if (wanted.sum() <= have) wanted else Workshop.fill(have, wanted)
    val placed = shares.sum()
    if (placed == 0) throw ToolError(if (need) "no furnace needs more fuel for what it's cooking" else "the fuel slots are full")

    val inHand = mutableListOf<ItemStack>()
    fun fail(reason: String): Nothing {
      give(player, inHand)
      holds.release(task.id)
      throw ToolError(reason)
    }
    val split = takeFuel(player, sources, holds, task, id, placed, inHand, ::fail)
    val filled = furnaces.indices.filter { shares[it] > 0 }
    filled.forEach { index ->
      val inventory = furnace(furnaces[index])!!.inventory
      inventory.fuel = ItemStack(type, (inventory.fuel?.amount ?: 0) + shares[index])
    }
    Marks.show(context.plugin, player, filled.map { Marks.Mark(furnaces[it], null, "+${shares[it]} ${id.replace('_', ' ')}", Marks.Hue.ORANGE) }, context.features.config().tools.marksSeconds)
    val ticks = Workshop.burnTicks(id) ?: 0
    val out = buildJsonObject {
      put("fuel", id)
      put("placed", placed)
      if (split > 0) put("coal_blocks_split", split)
      if (placed < wanted.sum()) put("short", wanted.sum() - placed)
      put("furnaces", buildJsonArray {
        filled.forEach { index ->
          val block = furnaces[index]
          val now = furnace(block)!!.inventory.fuel?.amount ?: 0
          add(buildJsonObject {
            put("x", block.x); put("y", block.y); put("z", block.z); put("type", block.type.key.key)
            put("added", shares[index]); put("holds", now)
            // How many items its fuel slot cooks, whatever the kind: blast furnaces and smokers burn twice as fast and cook twice as fast.
            put("cooks_items", now * ticks / Workshop.COOK_TICKS)
          })
        }
      })
      if (skipped.isNotEmpty()) put("skipped", buildJsonArray { skipped.forEach { add(JsonPrimitive(it)) } })
    }
    return ToolOutput(out, context.lang.text("summary.fuelled", "count" to placed, "furnaces" to filled.size))
  }

  private fun spot(block: Block) = "${block.type.key.key} ${block.x} ${block.y} ${block.z}"

  /** Ticks of fuel a furnace already has: what burns now and what waits in its fuel slot. */
  private fun banked(block: Block): Int {
    val state = furnace(block) ?: return 0
    val waiting = state.inventory.fuel?.let { (Workshop.burnTicks(it.type.key.key) ?: 0) * it.amount } ?: 0
    return state.burnTime.toInt() + waiting
  }
}
