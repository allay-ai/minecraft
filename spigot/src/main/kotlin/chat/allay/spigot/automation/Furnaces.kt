package chat.allay.spigot.automation

import chat.allay.core.tasks.Task
import chat.allay.core.tools.*
import chat.allay.spigot.tools.InteractTools
import chat.allay.spigot.tools.Marks
import chat.allay.spigot.tools.Tool
import chat.allay.spigot.tools.ToolContext

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.block.Block
import org.bukkit.block.Furnace
import org.bukkit.entity.Player
import org.bukkit.inventory.BlastingRecipe
import org.bukkit.inventory.CookingRecipe
import org.bukkit.inventory.FurnaceRecipe
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.SmokingRecipe

/**
 * `smelt`: loads furnaces, blast furnaces, and smokers near the player evenly, with exactly the fuel the load needs,
 * and keeps them going from what it reserved until everything is smelted.
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

  private fun furnace(block: Block) = block.state as? Furnace

  private fun free(block: Block) = furnace(block)?.inventory?.let { it.smelting == null && it.result == null } == true && !Jobs.busy(block)

  private fun plain(type: Material): (ItemStack) -> Boolean = { it.type == type && Sources.plain(it) }

  /** The fuels to try when the agent names none, cheapest to waste first. */
  private val FUEL_ORDER = listOf("coal", "charcoal", "coal_block", "blaze_rod", "dried_kelp_block")

  private class Load(val block: Block, var expected: Int, var share: Int)

  private class Job(override val task: Task, val player: Player, val item: Material, val result: ItemStack, val loads: List<Load>, val sources: Sources, var total: Int) : Jobs.BlockJob {
    override val blocks get() = loads.map { it.block }
    var smelted = 0
    var reportedFull = false

    override fun smelted(block: Block) {
      val load = loads.firstOrNull { it.block == block } ?: return
      val inventory = furnace(block)?.inventory ?: return
      load.expected--
      load.share--
      smelted++
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
      if (output != null && output.amount >= output.maxStackSize && load.share > 0 && !reportedFull) {
        reportedFull = true
        Jobs.automation.post(task, "[job] the furnace at ${block.x} ${block.y} ${block.z} is full of ${output.type.key.key}: collect it to go on")
      }
      if (smelted >= total) {
        Jobs.remove(this)
        Jobs.automation.post(task, "[job] smelting done: $smelted × ${result.type.key.key} in ${loads.size} furnaces (${loads.joinToString("; ") { "${it.block.x} ${it.block.y} ${it.block.z}" }}); the output waits in them: collect it")
      }
    }

    /** Furnaces that looked wrong at the last check: a smelt and the job's count are a tick apart, so it takes two looks. */
    private val suspect = mutableSetOf<Block>()

    override fun check() {
      for (load in loads) {
        val inventory = furnace(load.block)?.inventory
          ?: return Jobs.automation.interfered(task, Jobs.automation.text(task, "automation.reason.broken", "block" to "furnace", "x" to load.block.x, "y" to load.block.y, "z" to load.block.z))
        val input = inventory.smelting?.takeIf { it.type == item }?.amount ?: 0
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

  private fun where(source: Sources.Source) = source.block?.let { "${it.type.key.key} ${it.x} ${it.y} ${it.z}" } ?: source.place.substringBefore(':')

  val tool = object : Tool {
    override val name = "smelt"

    override fun run(context: ToolContext, args: JsonObject): ToolOutput {
      val player = context.requirePlayer()
      val task = context.requireTask()
      val automation = context.features.automation
      val settings = automation.settings()
      val lang = context.lang
      automation.require(player, task, chat.allay.core.tasks.Kind.FURNACES)
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
                  else -> put("free", true)
                }
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
            item?.let { put("player_has", sources.free(plain(it), it.key.key, automation.reservations, task.id)) }
            putJsonObject("fuel_at_hand") { (FUEL_ORDER + "lava_bucket").forEach { id -> Material.matchMaterial(id)?.let { m -> sources.count(plain(m)).takeIf { it > 0 }?.let { put(id, it) } } } }
          }
          ToolOutput(result, lang.text("summary.furnaces", "count" to rows.size))
        }
        "start" -> start(context, player, task, args)
        "status" -> {
          val job = automation.agent(task).job as? Job ?: throw ToolError("no smelting running for this task")
          ToolOutput(buildJsonObject { put("smelted", job.smelted); put("total", job.total); put("item", job.item.key.key) }, lang.text("summary.smelting", "done" to job.smelted, "total" to job.total))
        }
        "collect" -> {
          val to = Jobs.output(player, args["to"]) ?: throw ToolError("to is required: \"inventory\" or chests; ask the player")
          val job = automation.agent(task).job as? Job
          val blocks = Blocks.list(player, args["furnaces"], settings.radius).ifEmpty { job?.blocks?.toList().orEmpty() }
          if (blocks.isEmpty()) throw ToolError("which furnaces? pass furnaces")
          val taken = blocks.mapNotNull { block ->
            Blocks.unusable(player, block, settings.radius, false)?.let { throw ToolError("the furnace at ${block.x} ${block.y} ${block.z} can't be used: ${it.model}") }
            furnace(block)?.inventory?.let { inventory -> inventory.result?.also { inventory.result = null } }
          }
          Jobs.deliver(player, taken, to)
          job?.reportedFull = false
          ToolOutput(buildJsonObject { put("collected", taken.sumOf { it.amount }) }, lang.text("summary.collected", "count" to taken.sumOf { it.amount }))
        }
        else -> throw ToolError("action must be scan, start, status, or collect, not $action")
      }
    }
  }

  private fun start(context: ToolContext, player: Player, task: Task, args: JsonObject): ToolOutput {
    val automation = context.features.automation
    val settings = automation.settings()
    val holds = automation.reservations
    automation.agent(task).job?.let { if (Jobs.active(it)) throw ToolError("this task already runs a job; one per task") }
    val item = InteractTools.material(args.string("item") ?: throw ToolError("item is required, like raw_gold"))
    val sample = ItemStack(item)
    val named = Blocks.list(player, args["furnaces"], settings.radius).ifEmpty { automation.picking.picked(player, task) }
    val blocks = named.ifEmpty { Blocks.near(player.location, settings.radius) { Blocks.furnace(it.type) } }
      .filter { Blocks.furnace(it.type) && Blocks.unusable(player, it, settings.radius, settings.lineOfSight) == null && free(it) && recipe(player.server, it, sample) != null }
    if (blocks.isEmpty()) throw ToolError("no free furnace near the player can cook ${item.key.key}")
    val result = recipe(player.server, blocks.first(), sample)!!.result
    val sources = Sources.around(player, settings.radius)
    val available = sources.free(plain(item), item.key.key, holds, task.id)
    val total = minOf(args.int("count") ?: available, available)
    if (total <= 0) throw ToolError("the player has no ${item.key.key} at hand (inventory, bundles, ender chest, chests near)")
    val shares = Workshop.spread(total, blocks.size, cap = Int.MAX_VALUE)
    val used = blocks.zip(shares).filter { it.second > 0 }

    // Fuel: the one asked for, or the first that covers every furnace's share.
    val wanted = args.string("fuel")?.let(Pins::normalize)
    val fuels = if (wanted != null) listOf(wanted) else FUEL_ORDER
    val need = { fuel: String -> used.sumOf { (block, share) -> Workshop.fuelFor(share, fuel, banked(block)) } }
    val coal = { sources.free(plain(Material.COAL), "coal", holds, task.id) }
    val blocksOfCoal = { sources.free(plain(Material.COAL_BLOCK), "coal_block", holds, task.id) }
    val fuel = fuels.firstOrNull { id ->
      val material = Material.matchMaterial(id) ?: return@firstOrNull false
      val n = need(id)
      n == 0 || sources.free(plain(material), id, holds, task.id) >= n || (id == "coal" && coal() + blocksOfCoal() * 9 >= n)
    } ?: throw ToolError("not enough fuel at hand for ${total} items; fuel_at_hand in scan shows what there is")
    val fuelType = Material.matchMaterial(fuel)!!
    if (used.any { (block, _) -> furnace(block)!!.inventory.fuel?.let { it.type != fuelType } == true }) throw ToolError("a furnace already holds another fuel")
    val perFurnace = used.map { (block, share) -> Workshop.fuelFor(share, fuel, banked(block)) }
    if (perFurnace.any { it > 64 }) throw ToolError("one furnace would need more than 64 $fuel; use more furnaces or a stronger fuel")

    val fuelNeeded = perFurnace.sum()
    used.forEachIndexed { index, (block, _) ->
      val there = furnace(block)!!.inventory.fuel?.amount ?: 0
      if (perFurnace[index] > 0 && there + perFurnace[index] > fuelType.maxStackSize) throw ToolError("the fuel slot of the furnace at ${block.x} ${block.y} ${block.z} can't hold that much $fuel; use more furnaces or another fuel")
    }

    // Reserve everything, then take everything; only when all of it is in hand does anything go into a furnace.
    // Whatever fails on the way goes back to the player and frees the holds, so nothing is lost.
    val inHand = mutableListOf<ItemStack>()
    val spare = mutableListOf<Pair<ItemStack, Sources.Source?>>()
    val firsts = used.map { (_, share) -> minOf(64, share) }
    fun fail(reason: String): Nothing {
      give(player, inHand)
      holds.release(task.id)
      throw ToolError(reason)
    }
    if (!sources.reserve(plain(item), item.key.key, total, holds, task.id)) fail("someone took the ${item.key.key} meanwhile")
    val fromCoal = if (fuel == "coal") minOf(fuelNeeded, coal()) else fuelNeeded
    if (fromCoal > 0 && !sources.reserve(plain(fuelType), fuel, fromCoal, holds, task.id)) fail("someone took the $fuel meanwhile")
    val blocksNeeded = if (fuel == "coal" && fuelNeeded > fromCoal) (fuelNeeded - fromCoal + 8) / 9 else 0
    if (blocksNeeded > 0 && !sources.reserve(plain(Material.COAL_BLOCK), "coal_block", blocksNeeded, holds, task.id)) fail("someone took the coal blocks meanwhile")
    val (gotFuel, shortFuel) = sources.takeReserved(plain(fuelType), fuel, fromCoal, holds, task.id)
    inHand += gotFuel
    if (shortFuel != null) fail("the $fuel changed meanwhile")
    if (blocksNeeded > 0) {
      // Coal blocks split into coal, as the crafting table does; what's left over goes back where the block was.
      val source = sources.list.firstOrNull { it.count(plain(Material.COAL_BLOCK)) > 0 }
      val (taken, short) = sources.takeReserved(plain(Material.COAL_BLOCK), "coal_block", blocksNeeded, holds, task.id)
      inHand += taken
      if (short != null) fail("the coal blocks changed meanwhile")
      inHand.removeAll(taken)
      val pieces = taken.sumOf { it.amount } * 9
      inHand += ItemStack(Material.COAL, fuelNeeded - fromCoal)
      val left = pieces - (fuelNeeded - fromCoal)
      if (left > 0) spare += ItemStack(Material.COAL, left) to source
    }
    val loadsInHand = firsts.map { first ->
      val (taken, short) = sources.takeReserved(plain(item), item.key.key, first, holds, task.id)
      inHand += taken
      if (short != null) fail("the ${item.key.key} changed meanwhile")
      taken.sumOf { it.amount }
    }

    // Everything is here: each furnace gets its share up to a stack, and its fuel.
    val loads = used.mapIndexed { index, (block, share) ->
      val inventory = furnace(block)!!.inventory
      inventory.smelting = ItemStack(item, loadsInHand[index])
      if (perFurnace[index] > 0) inventory.fuel = ItemStack(fuelType, (inventory.fuel?.amount ?: 0) + perFurnace[index])
      Load(block, loadsInHand[index], share)
    }
    spare.forEach { (stack, source) -> give(player, listOf(stack), source) }
    val job = Job(task, player, item, result, loads, sources, total)
    automation.agent(task).job = job
    Jobs.add(job)
    Marks.show(context.plugin, player, loads.map { Marks.Mark(it.block, null, "${it.share} × ${item.key.key.replace('_', ' ')}", Marks.Hue.ORANGE) }, context.features.config().tools.marksSeconds)
    val out = buildJsonObject {
      put("smelting", total)
      put("item", item.key.key)
      put("makes", result.type.key.key)
      put("fuel", fuel)
      put("fuel_used", fuelNeeded)
      put("furnaces", buildJsonArray { loads.forEach { load -> add(buildJsonObject { put("x", load.block.x); put("y", load.block.y); put("z", load.block.z); put("share", load.share) }) } })
      put("note", "wait now; you wake when it's done or a furnace is full")
    }
    return ToolOutput(out, context.lang.text("summary.smelting_started", "count" to total, "furnaces" to loads.size))
  }

  /** Ticks of fuel a furnace already has: what burns now and what waits in its fuel slot. */
  private fun banked(block: Block): Int {
    val state = furnace(block) ?: return 0
    val waiting = state.inventory.fuel?.let { (Workshop.burnTicks(it.type.key.key) ?: 0) * it.amount } ?: 0
    return state.burnTime.toInt() + waiting
  }
}
