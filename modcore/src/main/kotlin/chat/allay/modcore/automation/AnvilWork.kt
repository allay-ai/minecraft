package chat.allay.modcore.automation

import chat.allay.core.Legacy
import chat.allay.core.tools.*
import chat.allay.modcore.Sounds
import chat.allay.modcore.tools.Anvils
import chat.allay.modcore.tools.BlockAt
import chat.allay.modcore.tools.InteractTools
import chat.allay.modcore.tools.Marks
import chat.allay.modcore.tools.Recipes
import chat.allay.modcore.tools.Tool
import chat.allay.modcore.tools.ToolContext
import chat.allay.modcore.tools.Worlds
import chat.allay.modcore.tools.key

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.ItemEnchantments
import net.minecraft.world.level.block.AnvilBlock
import net.minecraft.world.level.block.Blocks as GameBlocks
import net.minecraft.world.level.block.state.BlockState
import kotlin.random.Random

/**
 * `anvil`: works an item on a real anvil near the player, as the game would: enchanted books and items of its kind go
 * onto it in the cheapest order ([chat.allay.core.tools.Mechanics.mergePlan]), from the inventory, bundles, the ender
 * chest when one is near, and chests the player may open, and it can take a new name. The player pays the levels,
 * nothing costs 40 or more in survival, and the anvil wears down like the game's.
 */
object AnvilWork {
  private fun anvil(state: BlockState) = state.`is`(GameBlocks.ANVIL) || state.`is`(GameBlocks.CHIPPED_ANVIL) || state.`is`(GameBlocks.DAMAGED_ANVIL)

  /** Pieces beyond the item; the planner tries every order, so it stays small. */
  private const val MAX_EXTRA = 7

  /** The game's chance that a use wears an anvil one stage down. */
  private const val WEAR = 0.12

  private class Piece(val source: Sources.Source, val item: ItemStack, val piece: Mechanics.AnvilPiece)

  /** Enchantment levels on an item, as Bukkit's `getEnchantments` sums them. */
  private fun levels(item: ItemStack) = item.get(DataComponents.ENCHANTMENTS)?.entrySet()?.sumOf { it.intValue } ?: 0

  val tool = object : Tool {
    override val name = "anvil"

    override fun run(context: ToolContext, args: JsonObject): ToolOutput {
      val player = context.requirePlayer()
      val task = context.requireTask()
      val automation = context.features.automation
      val settings = automation.settings()
      automation.require(player, task, chat.allay.core.tasks.Kind.CRAFTING)
      val apply = when (val action = args.string("action")?.lowercase()) {
        "plan", null -> false
        "apply" -> true
        else -> throw ToolError("action must be plan or apply, not $action")
      }
      val anvil = Crafting.station(player, settings.radius, settings.lineOfSight, ::anvil)
        ?: throw ToolError("no anvil near the player in plain sight")

      // The item: the one in hand, or the richest of a kind they name, worn pieces too.
      val inventory = player.inventory
      val named = args.string("item")?.takeUnless { it.equals("hand", ignoreCase = true) }?.let(InteractTools::item)
      val (slot, target) = if (named == null) {
        val hand = inventory.selectedSlot
        hand to (inventory.getItem(hand).takeUnless { it.isEmpty }?.copy() ?: throw ToolError("the player holds nothing; ask them to hold the item, or name it"))
      } else {
        (0..40).mapNotNull { index -> inventory.getItem(index).takeIf { !it.isEmpty && it.item == named }?.let { index to it.copy() } }
          .maxByOrNull { levels(it.second) } ?: throw ToolError("the player has no ${Recipes.id(named)}")
      }
      val targetId = Slots.id(target)
      if (target.item == Items.BOOK) throw ToolError("a plain book can't be worked on an anvil")
      val rules = Anvils.Rules(player, target.item)
      val rename = args.string("rename")?.let(Legacy::strip)?.replace("§", "")?.trim()?.take(50)?.ifEmpty { null }
      val wanted = (args["enchantments"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(Names::enchantment)?.ifEmpty { null } }?.toSet().orEmpty()
      wanted.firstOrNull { Anvils.enchantment(player, it) == null }?.let { throw ToolError("no enchantment called $it") }
      wanted.firstOrNull { !rules.fits(it) }?.let { throw ToolError("$it doesn't go on $targetId") }
      if (wanted.isEmpty() && rename == null && args.bool("merge") != true) throw ToolError("say what to do: enchantments to add, merge: true for everything that fits, or rename")

      // Books and items of its kind from everywhere the player may take from; the item itself stays out.
      val sources = Sources.around(player, settings.radius)
      var skipped = false
      val candidates = if (wanted.isEmpty() && args.bool("merge") != true) emptyList() else sources.list.flatMap { source ->
        source.slots().filterNotNull().filter { stack ->
          if (!skipped && slot in 0..35 && source.own && source.block == null && Slots.similar(stack, target) && !source.place.contains('#')) {
            skipped = true
            return@filter false
          }
          val piece = Anvils.piece(stack)
          val keys = piece.enchantments.keys.filter(rules::fits)
          val useful = when {
            stack.item == Items.ENCHANTED_BOOK -> keys.isNotEmpty()
            stack.item == target.item -> piece.enchantments.isNotEmpty()
            else -> false
          }
          useful && (wanted.isEmpty() || keys.any { it in wanted })
        }.map { Piece(source, it.copyWithCount(1), Anvils.piece(it)) }
      }.sortedByDescending { candidate -> candidate.piece.enchantments.filterKeys { wanted.isEmpty() || it in wanted }.values.sum() }.take(MAX_EXTRA)
      if (candidates.isEmpty() && rename == null) throw ToolError("no enchanted book or $targetId with ${if (wanted.isEmpty()) "anything that fits" else wanted.joinToString()} at hand (inventory, bundles, ender chest, chests near)")

      val base = Anvils.piece(target)
      val merge = if (candidates.isEmpty()) null else Mechanics.mergePlan(listOf(base) + candidates.map { it.piece }, rules)
        ?: if (rename == null) throw ToolError("nothing at hand adds to $targetId: what fits is already on it at that level, or clashes with what's there") else null
      val used = merge?.used.orEmpty().filter { it > 0 }.map { candidates[it - 1] }
      // A new name costs a level more on the last step, or the item's prior work penalty and one alone.
      val renameCost = if (rename != null) 1 else 0
      val steps = merge?.steps?.size ?: 1
      val total = (merge?.total ?: base.repairCost) + renameCost
      val worst = (merge?.steps?.maxOfOrNull { it.cost } ?: base.repairCost) + renameCost
      val creative = player.isCreative
      val tooExpensive = !creative && (merge?.tooExpensive == true || worst >= 40)
      val result = merge?.result ?: base
      val out = buildJsonObject {
        put("item", targetId)
        put("anvil", buildJsonObject {
          put("x", anvil.x)
          put("y", anvil.y)
          put("z", anvil.z)
          put("type", anvil.key)
        })
        putJsonObject("enchantments") { result.enchantments.forEach { (key, level) -> put(key, level) } }
        if (rename != null) put("rename", rename)
        put("uses", buildJsonArray {
          used.forEach { piece ->
            add(buildJsonObject {
              put("item", Slots.id(piece.item))
              put("enchantments", piece.piece.enchantments.entries.joinToString { "${it.key} ${it.value}" })
              put("from", label(piece.source))
            })
          }
        })
        put("steps", steps)
        put("levels", if (creative) 0 else total)
        put("player_levels", player.experienceLevel)
        if (tooExpensive) put("too_expensive", "a step costs 40 levels or more; the game refuses that in survival")
        else if (!creative && player.experienceLevel < total) put("short_of_levels", total - player.experienceLevel)
      }
      if (!apply) return ToolOutput(out, context.lang.text("summary.anvil_plan", "levels" to total))
      if (tooExpensive) throw ToolError("too expensive: a step costs 40 levels or more")
      if (!creative && player.experienceLevel < total) throw ToolError("the player has ${player.experienceLevel} levels and this takes $total")
      if (!inventory.getItem(slot).let { !it.isEmpty && Slots.similar(it, target) }) throw ToolError("the item moved meanwhile; plan again")

      // Take what it uses; if anything is gone, it all goes back.
      val taken = mutableListOf<Pair<ItemStack, Sources.Source>>()
      for (piece in used) {
        val got = piece.source.take({ Slots.similar(it, piece.item) }, 1)
        if (got.isEmpty()) {
          taken.forEach { (stack, source) -> give(player, listOf(stack), source) }
          throw ToolError("the ${Slots.id(piece.item)} at ${label(piece.source)} is gone; plan again")
        }
        taken += got.first() to piece.source
      }
      val made = target.copy()
      val enchantments = ItemEnchantments.Mutable(ItemEnchantments.EMPTY)
      result.enchantments.forEach { (key, level) -> Anvils.enchantment(player, key)?.let { enchantments.set(it, level) } }
      made.set(if (made.has(DataComponents.STORED_ENCHANTMENTS)) DataComponents.STORED_ENCHANTMENTS else DataComponents.ENCHANTMENTS, enchantments.toImmutable())
      // The prior work penalty grows with each use, as the game's does; a rename alone counts as one.
      made.set(DataComponents.REPAIR_COST, if (merge != null) result.repairCost else base.repairCost * 2 + 1)
      if (rename != null) made.set(DataComponents.CUSTOM_NAME, Component.literal(rename))
      inventory.setItem(slot, made)
      if (!creative) player.giveExperienceLevels(-total)
      val worn = wear(anvil, steps)
      val now = made.get(DataComponents.ENCHANTMENTS)?.entrySet()?.joinToString { "${Worlds.key(it.key)} ${it.intValue}" }.orEmpty()
      task.report("anvil: ${Slots.id(made)} in inventory slot $slot now has ${now.ifEmpty { "no enchantments" }}${rename?.let { ", named “$it”" } ?: ""}, for $total levels")
      Sounds.play(player, anvil.corner, if (worn == null) SoundEvents.ANVIL_DESTROY else SoundEvents.ANVIL_USE, 1f, 1f)
      Marks.show(context.scheduler, player, listOf(Marks.Mark(anvil, null, Slots.id(made).replace('_', ' '), Marks.Hue.GREEN)).filter { worn != null }, 10)
      return ToolOutput(JsonObject(out + ("done" to JsonPrimitive(true)) + ("anvil_now" to JsonPrimitive(worn ?: "air"))), context.lang.text("summary.anvil_done", "levels" to total))
    }
  }

  /** Where a piece lies, as the player would look for it. */
  private fun label(source: Sources.Source): String = source.block?.let { "${it.key} ${it.x} ${it.y} ${it.z}" } ?: when {
    source.place.startsWith("ender:") -> "ender chest"
    '#' in source.place -> "a bundle in the inventory"
    else -> "inventory"
  }

  /** Each use may wear the anvil one stage, as in the game: chipped, damaged, then gone. Returns what stands there now, or null when it broke. */
  private fun wear(anvil: BlockAt, uses: Int): String? {
    repeat(uses) {
      if (Random.nextDouble() >= WEAR) return@repeat
      val next = AnvilBlock.damage(anvil.state)
      if (next == null) {
        anvil.level.removeBlock(anvil.pos, false)
        return null
      }
      anvil.level.setBlockAndUpdate(anvil.pos, next)
    }
    return anvil.key
  }
}
