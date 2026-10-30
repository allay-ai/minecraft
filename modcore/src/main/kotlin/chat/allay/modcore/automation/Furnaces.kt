package chat.allay.modcore.automation

import chat.allay.core.tasks.Kind
import chat.allay.core.tasks.Reservations
import chat.allay.core.tasks.Task
import chat.allay.core.tools.*
import chat.allay.modcore.audience
import chat.allay.modcore.mixin.FurnaceAccess
import chat.allay.modcore.player
import chat.allay.modcore.tools.BlockAt
import chat.allay.modcore.tools.InteractTools
import chat.allay.modcore.tools.Marks
import chat.allay.modcore.tools.Recipes
import chat.allay.modcore.tools.Tool
import chat.allay.modcore.tools.ToolContext

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.ExperienceOrb
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.crafting.AbstractCookingRecipe
import net.minecraft.world.item.crafting.RecipeHolder
import net.minecraft.world.item.crafting.RecipeType
import net.minecraft.world.item.crafting.SingleRecipeInput
import net.minecraft.world.level.block.Blocks as GameBlocks
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity
import kotlin.math.floor
import kotlin.random.Random

/**
 * `smelt`: loads furnaces, blast furnaces, and smokers near the player evenly, with exactly the fuel the load needs,
 * and keeps them going from what it reserved until everything is smelted. With an output it empties them on the way
 * and at the end, and the player gets the experience the game gives for it. `fuel` puts in fuel alone, spread evenly.
 */
object Furnaces {
  private const val INPUT = 0
  private const val FUEL = 1
  private const val RESULT = 2

  /** The cooking recipe [block] uses for [item], or null when it can't cook it: a blast furnace takes ores, a smoker food. */
  fun recipe(level: ServerLevel, block: BlockAt, item: ItemStack): RecipeHolder<out AbstractCookingRecipe>? {
    val input = SingleRecipeInput(item)
    val recipes = level.server.recipeManager
    return when {
      block.state.`is`(GameBlocks.BLAST_FURNACE) -> recipes.getRecipeFor(RecipeType.BLASTING, input, level).orElse(null)
      block.state.`is`(GameBlocks.SMOKER) -> recipes.getRecipeFor(RecipeType.SMOKING, input, level).orElse(null)
      else -> recipes.getRecipeFor(RecipeType.SMELTING, input, level).orElse(null)
    }
  }

  /** What [recipe] makes of [item]. */
  private fun result(recipe: RecipeHolder<out AbstractCookingRecipe>, item: Item): ItemStack = recipe.value().assemble(SingleRecipeInput(ItemStack(item)))

  /** Whether a plain furnace cooks [type]: every blast furnace and smoker recipe has one too. */
  private fun cooks(level: ServerLevel, type: Item): Boolean = level.server.recipeManager.getRecipeFor(RecipeType.SMELTING, SingleRecipeInput(ItemStack(type)), level).isPresent

  private fun furnace(block: BlockAt) = block.entity as? AbstractFurnaceBlockEntity

  private fun input(block: BlockAt): ItemStack? = furnace(block)?.getItem(INPUT)?.takeUnless { it.isEmpty }
  private fun fuelIn(block: BlockAt): ItemStack? = furnace(block)?.getItem(FUEL)?.takeUnless { it.isEmpty }
  private fun output(block: BlockAt): ItemStack? = furnace(block)?.getItem(RESULT)?.takeUnless { it.isEmpty }

  private fun set(block: BlockAt, slot: Int, item: ItemStack?) {
    val furnace = furnace(block) ?: return
    furnace.setItem(slot, item ?: ItemStack.EMPTY)
    furnace.setChanged()
  }

  /** Nothing cooking and no job on it. Output may wait there: a load that makes the same joins it. */
  private fun free(block: BlockAt) = input(block) == null && !Jobs.busy(block)

  private fun plain(type: Item): (ItemStack) -> Boolean = { it.item == type && Sources.plain(it) }

  private fun id(type: Item) = Recipes.id(type)

  private fun type(id: String): Item? = InteractTools.known(id)

  /** The fuels to try when the agent names none, cheapest to waste first; the planks, sticks, and bamboo at hand follow. */
  private val FUEL_ORDER = listOf("coal", "charcoal", "coal_block", "blaze_rod", "dried_kelp_block")

  /** What the agent may burn without being told: [FUEL_ORDER], then the planks the player has, sticks, and bamboo. Logs and lava only when asked. */
  private fun choices(sources: Sources): List<String> {
    val planks = sources.list.flatMap { it.slots() }.filterNotNull().filter(Sources::plain).map(Slots::id)
      .filter { it.endsWith("_planks") && Workshop.wood(it) }.distinct().sorted()
    return FUEL_ORDER + planks + listOf("stick", "bamboo")
  }

  /** One furnace of a job: what it cooks, what's in its input now, and how much of its share is still to come. */
  private class Load(val block: BlockAt, val item: Item, val result: ItemStack, var expected: Int, var share: Int) {
    /** The share it got in all, for the report. */
    var first = share
  }

  private class Job(
    override val task: Task, starter: ServerPlayer, val loads: List<Load>, var sources: Sources, var total: Int, val fuel: String, val to: Jobs.Output?,
  ) : Jobs.BlockJob {
    private val owner = starter.audience

    /** The player as the game has them now: a respawn makes a new one, and what goes to the old one is lost. */
    val player: ServerPlayer get() = owner.player

    override val blocks get() = loads.map { it.block }
    var smelted = 0
    var reportedFull = false
    var collected = 0
    var experience = 0

    override fun smelted(block: BlockAt) {
      val load = loads.firstOrNull { it.block == block } ?: return
      furnace(block) ?: return
      load.expected--
      load.share--
      smelted++
      val item = load.item
      val input = input(block)
      if ((input?.count ?: 0) != load.expected || (input != null && input.item != item)) {
        return Jobs.automation.interfered(task, Jobs.automation.text(task, "automation.reason.changed", "block" to block.key, "x" to block.x, "y" to block.y, "z" to block.z))
      }
      // Refill from what was reserved, as far as this furnace's share goes.
      if (load.expected == 0 && load.share > 0) {
        val (taken, short) = here().takeReserved(plain(item), id(item), minOf(64, load.share), Jobs.automation.reservations, task.id)
        if (short != null) return Jobs.automation.interfered(task, Jobs.automation.text(task, "automation.reason.taken", "item" to id(item), "place" to where(short)))
        val amount = taken.sumOf { it.count }
        if (amount > 0) {
          set(block, INPUT, ItemStack(item, amount))
          load.expected = amount
        }
      }
      val output = output(block)
      if (output != null && output.count >= output.maxStackSize && load.share > 0) {
        // With an output the job empties a full furnace itself; without one the agent hears about it once.
        if (to != null) {
          collect(listOf(block))
        } else if (!reportedFull) {
          reportedFull = true
          Jobs.automation.post(task, "[job] the furnace at ${block.x} ${block.y} ${block.z} is full of ${Slots.id(output)}: collect it to go on")
        }
      }
      if (smelted >= total) {
        Jobs.remove(this)
        val made = loads.groupBy { Slots.id(it.result) }.entries.joinToString(", ") { (id, list) -> "${list.sumOf { load -> load.first }} × $id" }
        val where = loads.joinToString("; ") { "${it.block.x} ${it.block.y} ${it.block.z}" }
        val news = if (to != null && present(player)) {
          collect(blocks)
          "smelting done: $made in ${loads.size} furnaces ($where); collected $collected to ${Jobs.describe(to)}, with $experience experience for the player"
        } else if (to != null) {
          "smelting done: $made in ${loads.size} furnaces ($where); the player is dead or away, so the output waits in them: collect it when they're back"
        } else {
          "smelting done: $made in ${loads.size} furnaces ($where); the output waits in them: collect it"
        }
        task.report(news)
        Jobs.automation.post(task, "[job] $news")
      }
    }

    /**
     * The places it reserved from, as they are now: a chest that turned unfit since, or the ender chest with none near
     * any more, can't be taken from.
     */
    fun here(): Sources = Jobs.automation.settings().let { sources.now(player, it.radius, it.pauseDistance) }

    /**
     * Empties [furnaces] to the job's output, with the experience. Not while the player is dead or gone: the output
     * waits in the furnaces then, and [check] empties full ones once they're back.
     */
    fun collect(furnaces: List<BlockAt>) {
      if (!present(player)) return
      val (items, xp) = takeOutputs(player, furnaces)
      collected += items.filter { it.item != Items.BUCKET }.sumOf { it.count }
      experience += xp
      Jobs.deliver(player, items, to ?: Jobs.Output.Inventory)
    }

    /** Furnaces that looked wrong at the last check: a smelt and the job's count are a tick apart, so it takes two looks. */
    private val suspect = mutableSetOf<BlockAt>()

    override fun check() {
      for (load in loads) {
        if (!Jobs.loaded(load.block)) {
          suspect -= load.block
          continue
        }
        furnace(load.block)
          ?: return Jobs.automation.interfered(task, Jobs.automation.text(task, "automation.reason.broken", "block" to "furnace", "x" to load.block.x, "y" to load.block.y, "z" to load.block.z))
        val input = input(load.block)?.takeIf { it.item == load.item }?.count ?: 0
        if (input >= load.expected) {
          suspect -= load.block
        } else if (!suspect.add(load.block)) {
          return Jobs.automation.interfered(task, Jobs.automation.text(task, "automation.reason.changed", "block" to load.block.key, "x" to load.block.x, "y" to load.block.y, "z" to load.block.z))
        }
      }
      // A furnace that filled up while the player was dead or away stands still: empty it once they're back.
      if (to != null && present(player)) {
        val full = loads.filter { load -> load.share > 0 && Jobs.loaded(load.block) && output(load.block)?.let { it.count >= it.maxStackSize } == true }
        if (full.isNotEmpty()) collect(full.map { it.block })
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
  private fun takeOutputs(player: ServerPlayer, furnaces: List<BlockAt>): Pair<List<ItemStack>, Int> {
    var xp = 0
    val items = furnaces.flatMap { block ->
      furnace(block) ?: return@flatMap emptyList()
      val result = output(block)?.copy()?.also { set(block, RESULT, null) }
      val bucket = fuelIn(block)?.takeIf { it.item == Items.BUCKET }?.copy()?.also { set(block, FUEL, null) }
      if (result != null) xp += experience(player, block)
      listOfNotNull(result, bucket)
    }
    return items to xp
  }

  /**
   * The experience [block] holds for what it cooked, paid out to [player] as orbs where they stand, the way taking the
   * output does in the game, and the furnace's store emptied, so it's paid once: what Paper allows on Bukkit
   * (`setRecipesUsed`). Server thread.
   */
  private fun experience(player: ServerPlayer, block: BlockAt): Int {
    val furnace = furnace(block) ?: return 0
    val used = (furnace as FurnaceAccess).`allay$recipesUsed`()
    if (used.isEmpty()) return 0
    val recipes = player.level().server.recipeManager
    val points = used.reference2IntEntrySet().sumOf { entry ->
      ((recipes.byKey(entry.key).orElse(null)?.value() as? AbstractCookingRecipe)?.experience()?.toDouble() ?: 0.0) * entry.intValue
    }
    var whole = floor(points).toInt()
    if (Random.nextDouble() < points - whole) whole++
    used.clear()
    furnace.setChanged()
    if (whole > 0) ExperienceOrb.award(player.level(), player.position(), whole)
    return whole
  }

  private fun where(source: Sources.Source) = source.chest?.let { "${it.key} ${it.x} ${it.y} ${it.z}" } ?: source.place.substringBefore(':')

  /**
   * Reserves and takes [amount] of [fuel] for [task] into [inHand]. Coal comes from coal blocks too when coal runs
   * short, split as the crafting table does, and the rest of a block goes back where it was. Calls [fail] when
   * someone took it meanwhile; returns how many coal blocks were split.
   */
  private fun takeFuel(player: ServerPlayer, sources: Sources, holds: Reservations, task: Task, fuel: String, amount: Int, inHand: MutableList<ItemStack>, fail: (String) -> Nothing): Int {
    if (amount <= 0) return 0
    val type = type(fuel)!!
    val fromItems = if (type == Items.COAL) minOf(amount, sources.free(plain(Items.COAL), "coal", holds, task.id)) else amount
    val blocks = if (type == Items.COAL) (amount - fromItems + 8) / 9 else 0
    if (fromItems > 0 && !sources.reserve(plain(type), fuel, fromItems, holds, task.id)) fail("someone took the $fuel meanwhile")
    if (blocks > 0 && !sources.reserve(plain(Items.COAL_BLOCK), "coal_block", blocks, holds, task.id)) fail("someone took the coal blocks meanwhile")
    if (fromItems > 0) {
      val (taken, short) = sources.takeReserved(plain(type), fuel, fromItems, holds, task.id)
      inHand += taken
      if (short != null) fail("the $fuel changed meanwhile")
    }
    if (blocks > 0) {
      val source = sources.list.firstOrNull { it.count(plain(Items.COAL_BLOCK)) > 0 }
      val (split, short) = sources.takeReserved(plain(Items.COAL_BLOCK), "coal_block", blocks, holds, task.id)
      inHand += split
      if (short != null) fail("the coal blocks changed meanwhile")
      inHand.removeAll(split)
      inHand += ItemStack(Items.COAL, amount - fromItems)
      val left = blocks * 9 - (amount - fromItems)
      if (left > 0) give(player, listOf(ItemStack(Items.COAL, left)), source)
    }
    return blocks
  }

  /** How much of [fuel] the player could put in, coal blocks counted as nine coal. */
  private fun fuelAtHand(sources: Sources, holds: Reservations, task: Task, fuel: String): Int {
    val type = type(fuel) ?: return 0
    val have = sources.free(plain(type), fuel, holds, task.id)
    return if (type == Items.COAL) have + sources.free(plain(Items.COAL_BLOCK), "coal_block", holds, task.id) * 9 else have
  }

  /** A fuel as the model names it, its plural too: “coal blocks” is coal_block. */
  private fun fuelName(name: String): String = Names.normalize(name).let { id -> Names.forms(id).firstOrNull { Workshop.burnTicks(it) != null } ?: id }

  /**
   * One item or several: `item` and `count`, or `items`. An item a furnace makes rather than cooks stands for what cooks
   * into it: iron_ingot (or “iron”) for raw_iron and the iron ores, glass for sand, cooked_beef for beef.
   */
  private fun asked(level: ServerLevel, args: JsonObject): List<Pair<List<Item>, Int?>> {
    val list = (args["items"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.map { entry ->
      InteractTools.item(entry.string("item") ?: throw ToolError("each of items needs an item")) to entry.int("count")
    } ?: listOf(InteractTools.item(args.string("item") ?: throw ToolError("item or items is required, like raw_gold")) to args.int("count"))
    val groups = list.map { (item, count) -> (if (cooks(level, item)) listOf(item) else inputs(level, item).ifEmpty { listOf(item) }) to count }
    if (groups.flatMap { it.first }.toSet().size != groups.sumOf { it.first.size }) throw ToolError("an item is named twice")
    return groups
  }

  /** What a plain furnace cooks into [result], the plain forms first: raw_iron before iron_ore. */
  private fun inputs(level: ServerLevel, result: Item): List<Item> {
    val found = linkedSetOf<Item>()
    Recipes.all(level.server).forEach { holder ->
      val recipe = holder.value()
      if (recipe.type == RecipeType.SMELTING && recipe is AbstractCookingRecipe && Recipes.result(recipe)?.item == result) recipe.input().items().forEach { found += it.value() }
    }
    return found.sortedBy { if (id(it).endsWith("_ore")) 1 else 0 }
  }

  /** How many of each item to cook: a group shares its count in order, and an item the player lacks goes to [missing]. */
  private fun shares(groups: List<Pair<List<Item>, Int?>>, available: (Item) -> Int, missing: MutableList<String>): List<Pair<Item, Int>> =
    groups.flatMap { (items, count) ->
      var left = count ?: Int.MAX_VALUE
      val have = items.associateWith(available)
      if (have.values.all { it <= 0 }) missing += items.joinToString(" or ") { id(it) }
      items.mapNotNull { item ->
        val n = minOf(left, have.getValue(item))
        left -= maxOf(n, 0)
        if (n > 0) item to n else null
      }
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
      val around = { Blocks.near(player, settings.radius) { Blocks.furnace(it.state) } }
      return when (val action = args.string("action")?.lowercase()) {
        "scan" -> {
          val item = args.string("item")?.let(InteractTools::item)
          val marks = mutableListOf<Marks.Mark>()
          val rows = buildJsonArray {
            around().forEach { block ->
              furnace(block) ?: return@forEach
              val unfit = Blocks.unusable(player, block, settings.radius, settings.lineOfSight)
              val busy = unfit == null && !free(block)
              add(buildJsonObject {
                put("x", block.x)
                put("y", block.y)
                put("z", block.z)
                put("type", block.key)
                when {
                  unfit != null -> put("unsuitable", unfit.model)
                  busy -> put("busy", listOfNotNull(input(block)?.let { "cooking ${it.count} ${Slots.id(it)}" }, output(block)?.let { "output ${it.count} ${Slots.id(it)}" }).joinToString(", "))
                  else -> {
                    put("free", true)
                    output(block)?.let { put("output_waiting", "${it.count} ${Slots.id(it)}") }
                  }
                }
                fuelIn(block)?.let { put("fuel", "${it.count} ${Slots.id(it)}") }
                if (item != null && unfit == null) put("cooks_item", recipe(player.level(), block, ItemStack(item)) != null)
              })
              marks += Marks.Mark(block, null, null, if (unfit == null && !busy) Marks.Hue.GREEN else Marks.Hue.RED)
            }
          }
          if (marks.isNotEmpty()) Marks.show(context.scheduler, player, marks, context.features.config().tools.marksSeconds)
          val sources = Sources.around(player, settings.radius)
          val result = buildJsonObject {
            put("furnaces", rows)
            put("note", "green glow: free; red: busy or unusable; the player sees it")
            if (rows.isEmpty()) unreached(player, settings.radius, "furnaces") { Blocks.furnace(it.state) }
            item?.let { put("player_has", sources.free(plain(it), id(it), automation.reservations, task.id)) }
            // What the player could smelt right now, so the agent needn't search for it.
            val smeltable = sources.list.flatMap { it.slots() }.filterNotNull().filter(Sources::plain).map { it.item }.distinct()
              .filter { type -> cooks(player.level(), type) }
            putJsonObject("to_smelt_at_hand") { smeltable.forEach { type -> sources.free(plain(type), id(type), automation.reservations, task.id).takeIf { it > 0 }?.let { put(id(type), it) } } }
            putJsonObject("fuel_at_hand") {
              (choices(sources) + "lava_bucket").forEach { id -> type(id)?.let { m -> sources.count(plain(m)).takeIf { it > 0 }?.let { put(id, it) } } }
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
            putJsonObject("left") { job.loads.groupBy { id(it.item) }.forEach { (id, list) -> put(id, list.sumOf { it.share }) } }
            job.to?.let {
              put("output", Jobs.describe(it))
              put("collected", job.collected)
            }
          }, lang.text("summary.smelting", "done" to job.smelted, "total" to job.total))
        }
        "collect" -> {
          val to = Jobs.target(player, args["to"], Kind.FURNACES, context.features.settings, args.bool("remember") == true)
            ?: throw ToolError("to is required: \"inventory\", \"auto\", or chests; ask the player")
          val job = automation.agent(task).job as? Job
          val blocks = Blocks.list(player, args["furnaces"], settings.radius).ifEmpty { job?.blocks?.toList().orEmpty() }
          if (blocks.isEmpty()) throw ToolError("which furnaces? pass furnaces")
          if (!present(player)) throw ToolError("the player is dead or away; collect once they're back")
          blocks.forEach { block ->
            val unfit = Blocks.unusable(player, block, settings.radius, settings.lineOfSight) ?: Blocks.Unfit.BUSY.takeIf { Jobs.busy(block, task) }
            unfit?.let { throw ToolError("the furnace at ${block.x} ${block.y} ${block.z} can't be used: ${it.model}") }
          }
          val (items, xp) = takeOutputs(player, blocks)
          Jobs.deliver(player, items, to)
          job?.reportedFull = false
          val count = items.filter { it.item != Items.BUCKET }.sumOf { it.count }
          if (count > 0) task.report("collected ${Jobs.counts(items)} from the furnaces to ${Jobs.describe(to)}")
          ToolOutput(buildJsonObject { put("collected", count); put("experience", xp); put("to", Jobs.describe(to)) }, lang.text("summary.collected", "count" to count))
        }
        else -> throw ToolError("action must be scan, start, add, fuel, status, or collect, not $action")
      }
    }
  }

  private fun start(context: ToolContext, player: ServerPlayer, task: Task, args: JsonObject): ToolOutput {
    val automation = context.features.automation
    val settings = automation.settings()
    val holds = automation.reservations
    val level = player.level()
    automation.agent(task).job?.let { if (Jobs.active(it)) throw ToolError("this task already runs a job; add more to it with action add") }
    // One item, or several at once: “smelt the iron and the gold”.
    val asked = asked(level, args)
    val to = Jobs.target(player, args["to"], Kind.FURNACES, context.features.settings, args.bool("remember") == true)
    val named = Blocks.list(player, args["furnaces"], settings.radius).ifEmpty { automation.picking.picked(player, task) }
    val usable = named.ifEmpty { Blocks.near(player, settings.radius) { Blocks.furnace(it.state) } }
      .filter { Blocks.furnace(it.state) && Blocks.unusable(player, it, settings.radius, settings.lineOfSight) == null && free(it) }
    val sources = Sources.around(player, settings.radius)
    // Items the player doesn't have are left out, so “all my iron” may name every iron ore there is.
    val missing = mutableListOf<String>()
    val counts = shares(asked, { item -> sources.free(plain(item), id(item), holds, task.id) }, missing).toMap()
    if (counts.isEmpty()) throw ToolError("the player has none of ${missing.joinToString()} at hand (inventory, bundles, ender chest, chests near)")
    val wanted = args.string("fuel")?.let(::fuelName)
    if (wanted != null && Workshop.burnTicks(wanted) == null) throw ToolError("$wanted isn't a furnace fuel")
    val fuels = if (wanted != null) listOf(wanted) else choices(sources)

    // Furnaces holding another fuel they'd need more of are left out and the split runs again without them.
    val skipped = mutableListOf<String>()
    var candidates = usable
    lateinit var used: List<Triple<BlockAt, Item, Int>>
    lateinit var recipes: Map<BlockAt, Map<Item, RecipeHolder<out AbstractCookingRecipe>?>>
    lateinit var fuel: String
    lateinit var perFurnace: List<Int>
    while (true) {
      // A furnace with output waiting takes only what makes the same, so the output can grow.
      recipes = candidates.associateWith { block -> counts.keys.associateWith { item -> recipe(level, block, ItemStack(item)) } }
      val ovens = candidates.map { block ->
        val waiting = output(block)
        Workshop.Oven(counts.keys.filter { item ->
          val recipe = recipes[block]!![item] ?: return@filter false
          waiting == null || Slots.similar(waiting, result(recipe, item))
        }.associate { id(it) to if (block.state.`is`(GameBlocks.FURNACE)) 1 else 2 })
      }
      val parts = Workshop.allot(counts.mapKeys { id(it.key) }, ovens)
        ?: throw ToolError("no free furnace near the player can cook ${counts.keys.filter { item -> candidates.none { recipes[it]!![item] != null } }.joinToString { id(it) }}" +
          if (skipped.isNotEmpty()) "; left out: ${skipped.joinToString("; ")}" else "")
      used = candidates.indices.mapNotNull { index -> parts[index]?.let { part -> Triple(candidates[index], type(part.item)!!, part.count) } }
      // Fuel: the one asked for, or the first that covers every furnace's share.
      val need = { id: String -> used.sumOf { (block, _, share) -> Workshop.fuelFor(share, id, banked(block)) } }
      fuel = fuels.firstOrNull { id -> need(id).let { n -> n == 0 || fuelAtHand(sources, holds, task, id) >= n } }
        ?: throw ToolError("not enough fuel at hand for ${used.sumOf { it.third }} items; fuel_at_hand in scan shows what there is")
      val type = type(fuel)!!
      perFurnace = used.map { (block, _, share) -> Workshop.fuelFor(share, fuel, banked(block)) }
      val clash = used.indices.filter { perFurnace[it] > 0 && fuelIn(used[it].first)?.let { there -> there.item != type } == true }
      if (clash.isEmpty()) break
      clash.forEach { skipped += "${spot(used[it].first)}: burns ${Slots.id(fuelIn(used[it].first)!!)}" }
      candidates = candidates - clash.map { used[it].first }.toSet()
    }
    val fuelType = type(fuel)!!
    val total = used.sumOf { it.third }
    used.forEachIndexed { index, (block, _, _) ->
      val there = fuelIn(block)?.count ?: 0
      if (perFurnace[index] > 0 && there + perFurnace[index] > fuelType.defaultMaxStackSize) {
        throw ToolError(if (fuelType == Items.LAVA_BUCKET) "a bucket of lava cooks 100 items; the furnace at ${block.x} ${block.y} ${block.z} would need more: use more furnaces or another fuel"
          else "the fuel slot of the furnace at ${block.x} ${block.y} ${block.z} can't hold that much $fuel; use more furnaces or a stronger fuel")
      }
    }

    // Reserve everything, then take everything; only when all of it is in hand does anything go into a furnace.
    // Whatever fails on the way goes back to the player and frees the holds, so nothing is lost.
    // A refusal gives back only what this call reserved: a running job keeps its holds.
    val booking = Booking(holds, task.id)
    val inHand = mutableListOf<ItemStack>()
    val firsts = used.map { (_, item, share) -> item to minOf(64, share) }
    fun fail(reason: String): Nothing {
      give(player, inHand)
      booking.undo()
      throw ToolError(reason)
    }
    used.groupBy { it.second }.forEach { (item, list) ->
      if (!sources.reserve(plain(item), id(item), list.sumOf { it.third }, holds, task.id)) fail("someone took the ${id(item)} meanwhile")
    }
    val fuelNeeded = perFurnace.sum()
    val split = takeFuel(player, sources, holds, task, fuel, fuelNeeded, inHand, ::fail)
    val loadsInHand = firsts.map { (item, first) ->
      val (taken, short) = sources.takeReserved(plain(item), id(item), first, holds, task.id)
      inHand += taken
      if (short != null) fail("the ${id(item)} changed meanwhile")
      taken.sumOf { it.count }
    }

    // Everything is here: each furnace gets its share up to a stack, and its fuel.
    val loads = used.mapIndexed { index, (block, item, share) ->
      set(block, INPUT, ItemStack(item, loadsInHand[index]))
      if (perFurnace[index] > 0) set(block, FUEL, ItemStack(fuelType, (fuelIn(block)?.count ?: 0) + perFurnace[index]))
      Load(block, item, result(recipes[block]!![item]!!, item), loadsInHand[index], share)
    }
    val job = Job(task, player, loads, sources, total, fuel, to)
    automation.agent(task).job = job
    Jobs.add(job)
    Marks.show(context.scheduler, player, loads.map { Marks.Mark(it.block, null, "${it.share} × ${id(it.item).replace('_', ' ')}", Marks.Hue.ORANGE) }, context.features.config().tools.marksSeconds)
    val out = buildJsonObject {
      put("smelting", total)
      if (missing.isNotEmpty()) put("not_at_hand", buildJsonArray { missing.forEach { add(JsonPrimitive(it)) } })
      putJsonObject("makes") { loads.groupBy { Slots.id(it.result) }.forEach { (id, list) -> put(id, list.sumOf { it.share }) } }
      put("fuel", fuel)
      put("fuel_used", fuelNeeded)
      if (split > 0) put("coal_blocks_split", split)
      put("furnaces", buildJsonArray {
        loads.forEach { load ->
          add(buildJsonObject {
            put("x", load.block.x)
            put("y", load.block.y)
            put("z", load.block.z)
            put("type", load.block.key)
            put("item", id(load.item))
            put("share", load.share)
          })
        }
      })
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
  private fun add(context: ToolContext, player: ServerPlayer, task: Task, args: JsonObject): ToolOutput {
    val automation = context.features.automation
    val settings = automation.settings()
    val holds = automation.reservations
    val job = automation.agent(task).job as? Job ?: throw ToolError("no smelting running for this task; start one")
    if (!Jobs.active(job)) throw ToolError("the smelting already ended; start a new one")
    val sources = Sources.around(player, settings.radius)
    val fuelType = type(job.fuel)!!
    // A refusal gives back only what this call reserved: a running job keeps its holds.
    val booking = Booking(holds, task.id)
    val inHand = mutableListOf<ItemStack>()
    fun fail(reason: String): Nothing {
      give(player, inHand)
      booking.undo()
      throw ToolError(reason)
    }
    // Plan every item first, so a refusal leaves the job as it was.
    class Extra(val load: Load, val count: Int, val fuel: Int)
    val missing = mutableListOf<String>()
    val asked = asked(player.level(), args).map { (items, count) -> items.filter { item -> job.loads.any { it.item == item } }.ifEmpty { items } to count }
    val extras = shares(asked, { item -> sources.free(plain(item), id(item), holds, task.id) }, missing).flatMap { (item, n) ->
      val loads = job.loads.filter { it.item == item }
      if (loads.isEmpty()) throw ToolError("this job cooks ${job.loads.map { id(it.item) }.distinct().joinToString()}; ${id(item)} needs its own start once this one ends")
      val shares = Workshop.fill(n, loads.map { Int.MAX_VALUE / 2 })
      loads.mapIndexed { index, load -> Extra(load, shares[index], Workshop.fuelFor(load.share + shares[index], job.fuel, banked(load.block))) }.filter { it.count > 0 }
    }
    if (extras.isEmpty()) throw ToolError("the player has none of ${missing.joinToString()} at hand")
    extras.forEach { extra ->
      val there = fuelIn(extra.load.block)
      if (extra.fuel > 0 && there != null && there.item != fuelType) throw ToolError("the furnace at ${extra.load.block.x} ${extra.load.block.y} ${extra.load.block.z} burns ${Slots.id(there)} now")
      if ((there?.count ?: 0) + extra.fuel > fuelType.defaultMaxStackSize) throw ToolError("the furnace at ${extra.load.block.x} ${extra.load.block.y} ${extra.load.block.z} can't hold the fuel for that much; start another task with more furnaces")
    }
    val fuelNeeded = extras.sumOf { it.fuel }
    if (fuelNeeded > fuelAtHand(sources, holds, task, job.fuel)) throw ToolError("not enough ${job.fuel} at hand for that much more")
    extras.groupBy { it.load.item }.forEach { (item, list) ->
      if (!sources.reserve(plain(item), id(item), list.sumOf { it.count }, holds, task.id)) fail("someone took the ${id(item)} meanwhile")
    }
    takeFuel(player, sources, holds, task, job.fuel, fuelNeeded, inHand, ::fail)
    job.sources = sources
    extras.forEach { extra ->
      val load = extra.load
      if (extra.fuel > 0) set(load.block, FUEL, ItemStack(fuelType, (fuelIn(load.block)?.count ?: 0) + extra.fuel))
      load.share += extra.count
      load.first += extra.count
      job.total += extra.count
      // A furnace that finished its share has nothing in it to set off the refill: it gets its first stack now.
      if (load.expected == 0) {
        val (taken, short) = sources.takeReserved(plain(load.item), id(load.item), minOf(64, load.share), holds, task.id)
        if (short != null) return@forEach automation.interfered(task, automation.text(task, "automation.reason.taken", "item" to id(load.item), "place" to where(short)))
        val amount = taken.sumOf { it.count }
        if (amount > 0) {
          set(load.block, INPUT, ItemStack(load.item, amount))
          load.expected = amount
        }
      }
    }
    val out = buildJsonObject {
      putJsonObject("added") { extras.groupBy { id(it.load.item) }.forEach { (id, list) -> put(id, list.sumOf { it.count }) } }
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
  private fun fuel(context: ToolContext, player: ServerPlayer, task: Task, args: JsonObject): ToolOutput {
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
    val around = named.ifEmpty { Blocks.near(player, settings.radius) { Blocks.furnace(it.state) } }
      .filter { Blocks.furnace(it.state) && (types.isEmpty() || it.key in types) }
      .filter { block -> Blocks.unusable(player, block, settings.radius, settings.lineOfSight)?.let { skipped += "${spot(block)}: ${it.model}"; false } ?: true }
    if (around.isEmpty()) throw ToolError("no usable furnace near the player${if (types.isNotEmpty()) " of ${types.joinToString()}" else ""}${if (skipped.isNotEmpty()) "; ${skipped.joinToString("; ")}" else ""}")
    val sources = Sources.around(player, settings.radius)
    val asked = args.string("fuel")?.let(::fuelName)
    val id = asked
      ?: around.mapNotNull { fuelIn(it)?.let(Slots::id) }.filter { Workshop.burnTicks(it) != null }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
      ?: choices(sources).firstOrNull { fuel -> fuelAtHand(sources, holds, task, fuel) > 0 }
      ?: throw ToolError("the player has no fuel at hand (inventory, bundles, ender chest, chests near)")
    val type = type(id)?.takeIf { Workshop.burnTicks(id) != null } ?: throw ToolError("$id isn't a furnace fuel")
    val max = type.defaultMaxStackSize
    // A fuel slot holds one kind: furnaces burning another are left as they are.
    val furnaces = around.filter { block ->
      val there = fuelIn(block)
      when {
        there == null || there.item == type -> true
        else -> false.also { skipped += "${spot(block)}: burns ${Slots.id(there)}" }
      }
    }
    if (furnaces.isEmpty()) throw ToolError("every furnace near the player burns another fuel: ${skipped.joinToString("; ")}")
    val room = furnaces.map { max - (fuelIn(it)?.count ?: 0) }
    val have = fuelAtHand(sources, holds, task, id)
    if (have <= 0) throw ToolError("the player has no $id at hand (inventory, bundles, ender chest, chests near)")
    val wanted = when {
      count != null -> Workshop.fill(count, room)
      each != null -> room.map { minOf(it, each) }
      upTo != null -> furnaces.mapIndexed { index, block -> minOf(room[index], (upTo.coerceAtMost(max) - (fuelIn(block)?.count ?: 0)).coerceAtLeast(0)) }
      // What's in the input slot now, less what already burns or waits: blast furnaces cook twice as fast and burn twice as fast, so it's the same.
      else -> furnaces.mapIndexed { index, block -> minOf(room[index], Workshop.fuelFor(input(block)?.count ?: 0, id, banked(block))) }
    }
    // Short of fuel: as evenly as what there is allows.
    val shares = if (wanted.sum() <= have) wanted else Workshop.fill(have, wanted)
    val placed = shares.sum()
    if (placed == 0) throw ToolError(if (need) "no furnace needs more fuel for what it's cooking" else "the fuel slots are full")

    // A refusal gives back only what this call reserved: a running job keeps its holds.
    val booking = Booking(holds, task.id)
    val inHand = mutableListOf<ItemStack>()
    fun fail(reason: String): Nothing {
      give(player, inHand)
      booking.undo()
      throw ToolError(reason)
    }
    val split = takeFuel(player, sources, holds, task, id, placed, inHand, ::fail)
    val filled = furnaces.indices.filter { shares[it] > 0 }
    filled.forEach { index -> set(furnaces[index], FUEL, ItemStack(type, (fuelIn(furnaces[index])?.count ?: 0) + shares[index])) }
    Marks.show(context.scheduler, player, filled.map { Marks.Mark(furnaces[it], null, "+${shares[it]} ${id.replace('_', ' ')}", Marks.Hue.ORANGE) }, context.features.config().tools.marksSeconds)
    val ticks = Workshop.burnTicks(id) ?: 0
    val out = buildJsonObject {
      put("fuel", id)
      put("placed", placed)
      if (split > 0) put("coal_blocks_split", split)
      if (placed < wanted.sum()) put("short", wanted.sum() - placed)
      put("furnaces", buildJsonArray {
        filled.forEach { index ->
          val block = furnaces[index]
          val now = fuelIn(block)?.count ?: 0
          add(buildJsonObject {
            put("x", block.x)
            put("y", block.y)
            put("z", block.z)
            put("type", block.key)
            put("added", shares[index])
            put("holds", now)
            // How many items its fuel slot cooks, whatever the kind: blast furnaces and smokers burn twice as fast and cook twice as fast.
            put("cooks_items", now * ticks / Workshop.COOK_TICKS)
          })
        }
      })
      if (skipped.isNotEmpty()) put("skipped", buildJsonArray { skipped.forEach { add(JsonPrimitive(it)) } })
    }
    return ToolOutput(out, context.lang.text("summary.fuelled", "count" to placed, "furnaces" to filled.size))
  }

  private fun spot(block: BlockAt) = "${block.key} ${block.x} ${block.y} ${block.z}"

  /** Ticks of fuel a furnace already has: what burns now and what waits in its fuel slot. */
  private fun banked(block: BlockAt): Int {
    val furnace = furnace(block) ?: return 0
    val waiting = fuelIn(block)?.let { (Workshop.burnTicks(Slots.id(it)) ?: 0) * it.count } ?: 0
    return (furnace as FurnaceAccess).`allay$litTime`() + waiting
  }
}
