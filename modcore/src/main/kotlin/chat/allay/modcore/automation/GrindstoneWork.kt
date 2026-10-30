package chat.allay.modcore.automation

import chat.allay.core.tools.*
import chat.allay.modcore.Sounds
import chat.allay.modcore.tools.InteractTools
import chat.allay.modcore.tools.Marks
import chat.allay.modcore.tools.Tool
import chat.allay.modcore.tools.ToolContext
import chat.allay.modcore.tools.Worlds

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.minecraft.core.Holder
import net.minecraft.core.component.DataComponents
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.tags.EnchantmentTags
import net.minecraft.world.entity.ExperienceOrb
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.Enchantment
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.ItemEnchantments
import net.minecraft.world.level.block.Blocks as GameBlocks
import net.minecraft.world.phys.Vec3
import kotlin.random.Random

/**
 * `grindstone`: works an item on a real grindstone near the player, as the game's does. Alone, the item loses every
 * enchantment but curses (an enchanted book with none left turns plain) and their experience drops at the grindstone.
 * With `combine`, a second piece of the same item from the inventory, bundles, the ender chest when one is near, or
 * chests the player may open joins it: both durabilities and 5% more, and every enchantment but curses gone from both.
 * It costs no levels and never wears down.
 */
object GrindstoneWork {
  private class Second(val source: Sources.Source, val item: ItemStack)

  /** An item's enchantments, stored ones for a book, as the game's grindstone reads them. */
  private fun enchantments(item: ItemStack): List<Pair<Holder<Enchantment>, Int>> =
    EnchantmentHelper.getEnchantmentsForCrafting(item).entrySet().map { it.key to it.intValue }

  private fun key(enchantment: Holder<Enchantment>) = enchantment.unwrapKey().map { Worlds.key(it.identifier()) }.orElse("unknown")

  private fun curse(enchantment: Holder<Enchantment>) = enchantment.`is`(EnchantmentTags.CURSE)

  val tool = object : Tool {
    override val name = "grindstone"

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
      val grindstone = Crafting.station(player, settings.radius, settings.lineOfSight) { it.`is`(GameBlocks.GRINDSTONE) }
        ?: throw ToolError("no grindstone near the player in plain sight")
      val combine = args.bool("combine") == true

      // The item: the one in hand, or the richest of a kind they name, worn pieces too.
      val inventory = player.inventory
      val named = args.string("item")?.takeUnless { it.equals("hand", ignoreCase = true) }?.let(InteractTools::item)
      val (slot, target) = if (named == null) {
        val hand = inventory.selectedSlot
        hand to (inventory.getItem(hand).takeUnless { it.isEmpty }?.copy() ?: throw ToolError("the player holds nothing; ask them to hold the item, or name it"))
      } else {
        (0..40).mapNotNull { index -> inventory.getItem(index).takeIf { !it.isEmpty && it.item == named }?.let { index to it.copy() } }
          .maxByOrNull { (_, item) -> enchantments(item).sumOf { it.second } } ?: throw ToolError("the player has no ${chat.allay.modcore.tools.Recipes.id(named)}")
      }
      val second = if (!combine) null else second(player, settings.radius, slot, target)
      // Unbreakable gear has no durability to join, as the game's grindstone sees it.
      if (combine && !target.isDamageableItem) throw ToolError("${Slots.id(target)} has no durability to join: it never wears or is unbreakable; a grindstone joins only tools, weapons, and armor")
      if (combine && second == null) throw ToolError("no second ${Slots.id(target)} at hand (inventory, bundles, ender chest, chests near)")

      val own = enchantments(target)
      val other = second?.let { enchantments(it.item) }.orEmpty()
      val removed = (own + other).filter { !curse(it.first) }
      if (!combine && removed.isEmpty()) throw ToolError("${Slots.id(target)} has no enchantment a grindstone takes off${if (own.isNotEmpty()) "; curses stay" else ""}")
      val curses = (own + other).filter { curse(it.first) }.groupBy { it.first }.mapValues { (_, levels) -> levels.maxOf { it.second } }
      val costs = removed.sumOf { (enchantment, level) -> enchantment.value().getMinCost(level) }
      val range = Mechanics.grindstoneExperienceRange(costs)
      val max = target.maxDamage
      val damage = if (second == null) target.damageValue else Mechanics.grindstoneDamage(max, target.damageValue, second.item.damageValue)
      val out = buildJsonObject {
        put("item", Slots.id(target))
        put("grindstone", buildJsonObject { put("x", grindstone.x); put("y", grindstone.y); put("z", grindstone.z) })
        putJsonObject("takes_off") { removed.groupBy { key(it.first) }.forEach { (key, levels) -> put(key, levels.maxOf { it.second }) } }
        if (curses.isNotEmpty()) putJsonObject("curses_stay") { curses.forEach { (enchantment, level) -> put(key(enchantment), level) } }
        if (second != null) {
          put("with", buildJsonObject {
            put("item", Slots.id(second.item))
            put("from", label(second.source))
            put("durability", max - second.item.damageValue)
            if (other.isNotEmpty()) put("enchantments_lost", other.joinToString { "${key(it.first)} ${it.second}" })
          })
          put("durability", buildJsonObject {
            put("before", max - target.damageValue)
            put("after", max - damage)
            put("max", max)
          })
        }
        put("experience", if (range.first == range.last) "${range.first}" else "${range.first}–${range.last}")
        put("levels", 0)
      }
      if (!apply) return ToolOutput(out, context.lang.text("summary.grindstone_plan", "count" to removed.size))
      if (!inventory.getItem(slot).let { !it.isEmpty && Slots.similar(it, target) }) throw ToolError("the item moved meanwhile; plan again")

      if (second != null && second.source.take({ Slots.similar(it, second.item) }, 1).isEmpty()) {
        throw ToolError("the second ${Slots.id(second.item)} at ${label(second.source)} is gone; plan again")
      }
      // Everything but curses comes off first; then a book left with nothing turns plain and keeps its name, in the
      // game's order (GrindstoneMenu.removeNonCursesFrom), so no empty stored_enchantments stays on a book that should stack.
      var made = target.copy()
      val kept = ItemEnchantments.Mutable(ItemEnchantments.EMPTY)
      curses.forEach { (enchantment, level) -> kept.set(enchantment, level) }
      if (made.has(DataComponents.STORED_ENCHANTMENTS)) made.set(DataComponents.STORED_ENCHANTMENTS, kept.toImmutable())
      if (made.has(DataComponents.ENCHANTMENTS)) made.set(DataComponents.ENCHANTMENTS, kept.toImmutable())
      if (made.`is`(Items.ENCHANTED_BOOK) && curses.isEmpty()) made = made.transmuteCopy(Items.BOOK)
      made.set(DataComponents.REPAIR_COST, Mechanics.grindstoneRepairCost(curses.size))
      if (second != null) made.damageValue = damage
      inventory.setItem(slot, made)
      // The experience drops at the grindstone, as the game's does.
      val experience = Mechanics.grindstoneExperience(costs) { if (it > 0) Random.nextInt(it) else 0 }
      if (experience > 0) ExperienceOrb.award(grindstone.level, Vec3.atCenterOf(grindstone.pos), experience)
      Sounds.play(player, grindstone.corner, SoundEvents.GRINDSTONE_USE, 1f, 1f)
      task.report("grindstone: ${Slots.id(made)} in inventory slot $slot${if (second != null) ", joined with a second one, durability ${max - damage} of $max" else ""}, " +
        "took off ${removed.joinToString { "${key(it.first)} ${it.second}" }.ifEmpty { "nothing" }}; $experience experience at the grindstone")
      Marks.show(context.scheduler, player, listOf(Marks.Mark(grindstone, null, Slots.id(made).replace('_', ' '), Marks.Hue.GREEN)), 10)
      return ToolOutput(JsonObject(out + ("done" to JsonPrimitive(true)) + ("experience" to JsonPrimitive(experience))), context.lang.text("summary.grindstone_done", "points" to experience))
    }
  }

  /**
   * The second piece to join [target] with: one of the same item anywhere the player may take from, but never [target]
   * itself. The one that loses least comes first: fewest enchantment levels, then the most durability left.
   */
  private fun second(player: ServerPlayer, radius: Int, slot: Int, target: ItemStack): Second? {
    var skipped = false
    return Sources.around(player, radius).list.flatMap { source ->
      source.slots().filterNotNull().filter { stack ->
        if (stack.isEmpty || stack.item != target.item) return@filter false
        if (!skipped && slot in 0..35 && source.own && source.block == null && !source.place.contains('#') && Slots.similar(stack, target)) {
          skipped = true
          return@filter false
        }
        true
      }.map { Second(source, it.copyWithCount(1)) }
    }.sortedWith(compareBy<Second> { second -> enchantments(second.item).sumOf { it.second } }.thenBy { it.item.damageValue }).firstOrNull()
  }

  /** Where a piece lies, as the player would look for it. */
  private fun label(source: Sources.Source): String = source.block?.let { "${it.key} ${it.x} ${it.y} ${it.z}" } ?: when {
    source.place.startsWith("ender:") -> "ender chest"
    '#' in source.place -> "a bundle in the inventory"
    else -> "inventory"
  }
}
