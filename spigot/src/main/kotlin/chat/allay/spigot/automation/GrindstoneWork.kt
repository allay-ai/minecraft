package chat.allay.spigot.automation

import chat.allay.core.tools.*
import chat.allay.spigot.tools.InteractTools
import chat.allay.spigot.tools.Marks
import chat.allay.spigot.tools.Tool
import chat.allay.spigot.tools.ToolContext

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.ExperienceOrb
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.inventory.meta.EnchantmentStorageMeta
import org.bukkit.inventory.meta.Repairable
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

  /** An item's enchantments by key, stored ones for a book. */
  private fun enchantments(item: ItemStack): Map<String, Int> {
    val meta = item.itemMeta
    val found = if (meta is EnchantmentStorageMeta) meta.storedEnchants else item.enchantments
    return found.mapKeys { it.key.key.key }
  }

  @Suppress("DEPRECATION")
  private fun curse(key: String) = key in Mechanics.CURSES || Enchantment.getByKey(org.bukkit.NamespacedKey.minecraft(key))?.isCursed == true

  private fun damage(item: ItemStack) = (item.itemMeta as? Damageable)?.damage ?: 0

  private fun maxDamage(item: ItemStack) = (item.itemMeta as? Damageable)?.takeIf { it.hasMaxDamage() }?.maxDamage ?: item.type.maxDurability.toInt()

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
      val grindstone = Crafting.station(player, settings.radius, settings.lineOfSight) { it == Material.GRINDSTONE }
        ?: throw ToolError("no grindstone near the player in plain sight")
      val combine = args.bool("combine") == true

      // The item: the one in hand, or the richest of a kind they name, worn pieces too.
      val named = args.string("item")?.takeUnless { it.equals("hand", ignoreCase = true) }?.let(InteractTools::material)
      val (slot, target) = if (named == null) {
        val hand = player.inventory.heldItemSlot
        hand to (player.inventory.getItem(hand)?.takeUnless { it.type.isAir } ?: throw ToolError("the player holds nothing; ask them to hold the item, or name it"))
      } else {
        (0..40).mapNotNull { index -> player.inventory.getItem(index)?.takeIf { it.type == named }?.let { index to it } }
          .maxByOrNull { enchantments(it.second).values.sum() } ?: throw ToolError("the player has no ${named.key.key}")
      }
      val second = if (!combine) null else second(player, settings.radius, slot, target)
      // Unbreakable gear has no durability to join, as the game's grindstone sees it.
      if (combine && (maxDamage(target) <= 0 || target.itemMeta?.isUnbreakable == true)) throw ToolError("${target.type.key.key} has no durability to join: it never wears or is unbreakable; a grindstone joins only tools, weapons, and armor")
      if (combine && second == null) throw ToolError("no second ${target.type.key.key} at hand (inventory, bundles, ender chest, chests near)")

      val own = enchantments(target)
      val other = second?.let { enchantments(it.item) }.orEmpty()
      val removed = (own.entries + other.entries).filter { !curse(it.key) }
      if (!combine && removed.isEmpty()) throw ToolError("${target.type.key.key} has no enchantment a grindstone takes off${if (own.isNotEmpty()) "; curses stay" else ""}")
      val curses = (own + other).filterKeys(::curse).let { kept -> kept.keys.associateWith { key -> maxOf(own[key] ?: 0, other[key] ?: 0) } }
      val costs = removed.sumOf { Mechanics.minCost(it.key, it.value) }
      val range = Mechanics.grindstoneExperienceRange(costs)
      val max = maxDamage(target)
      val damage = if (second == null) damage(target) else Mechanics.grindstoneDamage(max, damage(target), damage(second.item))
      val out = buildJsonObject {
        put("item", target.type.key.key)
        put("grindstone", buildJsonObject { put("x", grindstone.x); put("y", grindstone.y); put("z", grindstone.z) })
        putJsonObject("takes_off") { removed.groupBy { it.key }.forEach { (key, levels) -> put(key, levels.maxOf { it.value }) } }
        if (curses.isNotEmpty()) putJsonObject("curses_stay") { curses.forEach { (key, level) -> put(key, level) } }
        if (second != null) {
          put("with", buildJsonObject {
            put("item", second.item.type.key.key)
            put("from", label(second.source))
            put("durability", max - damage(second.item))
            if (other.isNotEmpty()) put("enchantments_lost", other.entries.joinToString { "${it.key} ${it.value}" })
          })
          put("durability", buildJsonObject { put("before", max - damage(target)); put("after", max - damage); put("max", max) })
        }
        put("experience", if (range.first == range.last) "${range.first}" else "${range.first}–${range.last}")
        put("levels", 0)
      }
      if (!apply) return ToolOutput(out, context.lang.text("summary.grindstone_plan", "count" to removed.size))
      if (player.inventory.getItem(slot)?.isSimilar(target) != true) throw ToolError("the item moved meanwhile; plan again")

      if (second != null && second.source.take({ it.isSimilar(second.item) }, 1).isEmpty()) throw ToolError("the second ${second.item.type.key.key} at ${label(second.source)} is gone; plan again")
      // Everything but curses comes off; a book left with nothing turns plain and keeps its name, as the game's does.
      val book = target.type == Material.ENCHANTED_BOOK && curses.isEmpty()
      val worked = target.clone().apply {
        val meta = itemMeta
        if (meta is EnchantmentStorageMeta) meta.storedEnchants.keys.toList().forEach(meta::removeStoredEnchant)
        else meta?.enchants?.keys?.toList()?.forEach(meta::removeEnchant)
        curses.forEach { (key, level) ->
          val enchantment = org.bukkit.Registry.ENCHANTMENT.get(org.bukkit.NamespacedKey.minecraft(key)) ?: return@forEach
          if (meta is EnchantmentStorageMeta) meta.addStoredEnchant(enchantment, level, true) else meta?.addEnchant(enchantment, level, true)
        }
        if (meta is Repairable) meta.repairCost = Mechanics.grindstoneRepairCost(curses.size)
        if (meta is Damageable && second != null) meta.damage = damage
        itemMeta = meta
      }
      val made = if (!book) worked else ItemStack(Material.BOOK, worked.amount).apply {
        worked.itemMeta?.let { itemMeta = Bukkit.getItemFactory().asMetaFor(it, Material.BOOK) }
      }
      player.inventory.setItem(slot, made)
      // The experience drops at the grindstone, as the game's does.
      val experience = Mechanics.grindstoneExperience(costs) { if (it > 0) Random.nextInt(it) else 0 }
      if (experience > 0) grindstone.world.spawn(grindstone.location.add(0.5, 1.0, 0.5), ExperienceOrb::class.java) { it.experience = experience }
      player.playSound(grindstone.location, Sound.BLOCK_GRINDSTONE_USE, 1f, 1f)
      task.report("grindstone: ${made.type.key.key} in inventory slot $slot${if (second != null) ", joined with a second one, durability ${max - damage} of $max" else ""}, " +
        "took off ${removed.joinToString { "${it.key} ${it.value}" }.ifEmpty { "nothing" }}; $experience experience at the grindstone")
      Marks.show(context.plugin, player, listOf(Marks.Mark(grindstone, null, made.type.key.key.replace('_', ' '), Marks.Hue.GREEN)), 10)
      return ToolOutput(JsonObject(out + ("done" to JsonPrimitive(true)) + ("experience" to JsonPrimitive(experience))), context.lang.text("summary.grindstone_done", "points" to experience))
    }
  }

  /**
   * The second piece to join [target] with: one of the same item anywhere the player may take from, but never [target]
   * itself. The one that loses least comes first: fewest enchantment levels, then the most durability left.
   */
  private fun second(player: Player, radius: Int, slot: Int, target: ItemStack): Second? {
    var skipped = false
    return Sources.around(player, radius).list.flatMap { source ->
      source.slots().filterNotNull().filter { stack ->
        if (stack.type != target.type) return@filter false
        if (!skipped && slot in 0..35 && source.own && source.block == null && !source.place.contains('#') && stack.isSimilar(target)) { skipped = true; return@filter false }
        true
      }.map { Second(source, it.clone().apply { amount = 1 }) }
    }.sortedWith(compareBy<Second> { enchantments(it.item).values.sum() }.thenBy { damage(it.item) }).firstOrNull()
  }

  /** Where a piece lies, as the player would look for it. */
  private fun label(source: Sources.Source): String = source.block?.let { "${it.type.key.key} ${it.x} ${it.y} ${it.z}" } ?: when {
    source.place.startsWith("ender:") -> "ender chest"
    '#' in source.place -> "a bundle in the inventory"
    else -> "inventory"
  }
}
