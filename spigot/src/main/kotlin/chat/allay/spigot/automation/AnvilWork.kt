package chat.allay.spigot.automation

import chat.allay.core.tools.*
import chat.allay.spigot.tools.Anvils
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.bukkit.ChatColor
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.EnchantmentStorageMeta
import org.bukkit.inventory.meta.Repairable
import kotlin.random.Random

/**
 * `anvil`: works an item on a real anvil near the player, as the game would: enchanted books and items of its kind go
 * onto it in the cheapest order ([chat.allay.core.tools.Mechanics.mergePlan]), from the inventory, bundles, the ender
 * chest when one is near, and chests the player may open, and it can take a new name. The player pays the levels,
 * nothing costs 40 or more in survival, and the anvil wears down like the game's.
 */
object AnvilWork {
  private val ANVILS = setOf(Material.ANVIL, Material.CHIPPED_ANVIL, Material.DAMAGED_ANVIL)

  /** Pieces beyond the item; the planner tries every order, so it stays small. */
  private const val MAX_EXTRA = 7

  /** The game's chance that a use wears an anvil one stage down. */
  private const val WEAR = 0.12

  private class Piece(val source: Sources.Source, val item: ItemStack, val piece: Mechanics.AnvilPiece)

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
      val anvil = Crafting.station(player, settings.radius, settings.lineOfSight) { it in ANVILS }
        ?: throw ToolError("no anvil near the player in plain sight")

      // The item: the one in hand, or the richest of a kind they name, worn pieces too.
      val named = args.string("item")?.takeUnless { it.equals("hand", ignoreCase = true) }?.let(InteractTools::material)
      val (slot, target) = if (named == null) {
        val hand = player.inventory.heldItemSlot
        hand to (player.inventory.getItem(hand)?.takeUnless { it.type.isAir } ?: throw ToolError("the player holds nothing; ask them to hold the item, or name it"))
      } else {
        (0..40).mapNotNull { index -> player.inventory.getItem(index)?.takeIf { it.type == named }?.let { index to it } }
          .maxByOrNull { it.second.enchantments.values.sum() } ?: throw ToolError("the player has no ${named.key.key}")
      }
      if (target.type == Material.BOOK) throw ToolError("a plain book can't be worked on an anvil")
      val rules = Anvils.Rules(target.type)
      val rename = args.string("rename")?.let(ChatColor::stripColor)?.replace("§", "")?.trim()?.take(50)?.ifEmpty { null }
      val wanted = (args["enchantments"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(Names::enchantment)?.ifEmpty { null } }?.toSet().orEmpty()
      wanted.firstOrNull { Registry.ENCHANTMENT.get(NamespacedKey.minecraft(it)) == null }?.let { throw ToolError("no enchantment called $it") }
      wanted.firstOrNull { !rules.fits(it) }?.let { throw ToolError("$it doesn't go on ${target.type.key.key}") }
      if (wanted.isEmpty() && rename == null && args.bool("merge") != true) throw ToolError("say what to do: enchantments to add, merge: true for everything that fits, or rename")

      // Books and items of its kind from everywhere the player may take from; the item itself stays out.
      val sources = Sources.around(player, settings.radius)
      var skipped = false
      val candidates = if (wanted.isEmpty() && args.bool("merge") != true) emptyList() else sources.list.flatMap { source ->
        source.slots().filterNotNull().filter { stack ->
          if (!skipped && slot in 0..35 && source.own && source.block == null && stack.isSimilar(target) && !source.place.contains('#')) { skipped = true; return@filter false }
          val piece = Anvils.piece(stack)
          val keys = piece.enchantments.keys.filter(rules::fits)
          val useful = when {
            stack.type == Material.ENCHANTED_BOOK -> keys.isNotEmpty()
            stack.type == target.type -> piece.enchantments.isNotEmpty()
            else -> false
          }
          useful && (wanted.isEmpty() || keys.any { it in wanted })
        }.map { Piece(source, it.clone().apply { amount = 1 }, Anvils.piece(it)) }
      }.sortedByDescending { candidate -> candidate.piece.enchantments.filterKeys { wanted.isEmpty() || it in wanted }.values.sum() }.take(MAX_EXTRA)
      if (candidates.isEmpty() && rename == null) throw ToolError("no enchanted book or ${target.type.key.key} with ${if (wanted.isEmpty()) "anything that fits" else wanted.joinToString()} at hand (inventory, bundles, ender chest, chests near)")

      val base = Anvils.piece(target)
      val merge = if (candidates.isEmpty()) null else Mechanics.mergePlan(listOf(base) + candidates.map { it.piece }, rules)
        ?: if (rename == null) throw ToolError("nothing at hand adds to ${target.type.key.key}: what fits is already on it at that level, or clashes with what's there") else null
      val used = merge?.used.orEmpty().filter { it > 0 }.map { candidates[it - 1] }
      // A new name costs a level more on the last step, or the item's prior work penalty and one alone.
      val renameCost = if (rename != null) 1 else 0
      val steps = merge?.steps?.size ?: 1
      val total = (merge?.total ?: base.repairCost) + renameCost
      val worst = (merge?.steps?.maxOfOrNull { it.cost } ?: base.repairCost) + renameCost
      val creative = player.gameMode == GameMode.CREATIVE
      val tooExpensive = !creative && (merge?.tooExpensive == true || worst >= 40)
      val result = merge?.result ?: base
      val out = buildJsonObject {
        put("item", target.type.key.key)
        put("anvil", buildJsonObject { put("x", anvil.x); put("y", anvil.y); put("z", anvil.z); put("type", anvil.type.key.key) })
        putJsonObject("enchantments") { result.enchantments.forEach { (key, level) -> put(key, level) } }
        if (rename != null) put("rename", rename)
        put("uses", buildJsonArray {
          used.forEach { piece -> add(buildJsonObject { put("item", piece.item.type.key.key); put("enchantments", piece.piece.enchantments.entries.joinToString { "${it.key} ${it.value}" }); put("from", label(piece.source)) }) }
        })
        put("steps", steps)
        put("levels", if (creative) 0 else total)
        put("player_levels", player.level)
        if (tooExpensive) put("too_expensive", "a step costs 40 levels or more; the game refuses that in survival")
        else if (!creative && player.level < total) put("short_of_levels", total - player.level)
      }
      if (!apply) return ToolOutput(out, context.lang.text("summary.anvil_plan", "levels" to total))
      if (tooExpensive) throw ToolError("too expensive: a step costs 40 levels or more")
      if (!creative && player.level < total) throw ToolError("the player has ${player.level} levels and this takes $total")
      if (player.inventory.getItem(slot)?.isSimilar(target) != true) throw ToolError("the item moved meanwhile; plan again")

      // Take what it uses; if anything is gone, it all goes back.
      val taken = mutableListOf<Pair<ItemStack, Sources.Source>>()
      for (piece in used) {
        val got = piece.source.take({ it.isSimilar(piece.item) }, 1)
        if (got.isEmpty()) {
          taken.forEach { (stack, source) -> give(player, listOf(stack), source) }
          throw ToolError("the ${piece.item.type.key.key} at ${label(piece.source)} is gone; plan again")
        }
        taken += got.first() to piece.source
      }
      val made = target.clone()
      val meta = made.itemMeta
      if (meta != null) {
        if (meta is EnchantmentStorageMeta) {
          meta.storedEnchants.keys.toList().forEach(meta::removeStoredEnchant)
          result.enchantments.forEach { (key, level) -> Registry.ENCHANTMENT.get(NamespacedKey.minecraft(key))?.let { meta.addStoredEnchant(it, level, true) } }
        } else {
          meta.enchants.keys.toList().forEach(meta::removeEnchant)
          result.enchantments.forEach { (key, level) -> Registry.ENCHANTMENT.get(NamespacedKey.minecraft(key))?.let { meta.addEnchant(it, level, true) } }
        }
        // The prior work penalty grows with each use, as the game's does; a rename alone counts as one.
        if (meta is Repairable) meta.repairCost = if (merge != null) result.repairCost else base.repairCost * 2 + 1
        if (rename != null) meta.setDisplayName(rename)
        made.itemMeta = meta
      }
      player.inventory.setItem(slot, made)
      if (!creative) player.giveExpLevels(-total)
      val worn = wear(anvil, steps)
      task.report("anvil: ${made.type.key.key} in inventory slot $slot now has ${made.enchantments.entries.joinToString { "${it.key.key.key} ${it.value}" }.ifEmpty { "no enchantments" }}${rename?.let { ", named “$it”" } ?: ""}, for $total levels")
      player.playSound(anvil.location, if (worn == Material.AIR) Sound.BLOCK_ANVIL_DESTROY else Sound.BLOCK_ANVIL_USE, 1f, 1f)
      Marks.show(context.plugin, player, listOf(Marks.Mark(anvil, null, made.type.key.key.replace('_', ' '), Marks.Hue.GREEN)).filter { worn != Material.AIR }, 10)
      return ToolOutput(JsonObject(out + ("done" to JsonPrimitive(true)) + ("anvil_now" to JsonPrimitive(worn.key.key))), context.lang.text("summary.anvil_done", "levels" to total))
    }
  }

  /** Where a piece lies, as the player would look for it. */
  private fun label(source: Sources.Source): String = source.block?.let { "${it.type.key.key} ${it.x} ${it.y} ${it.z}" } ?: when {
    source.place.startsWith("ender:") -> "ender chest"
    '#' in source.place -> "a bundle in the inventory"
    else -> "inventory"
  }

  /** Each use may wear the anvil one stage, as in the game: chipped, damaged, then gone. */
  private fun wear(anvil: Block, uses: Int): Material {
    repeat(uses) {
      if (Random.nextDouble() >= WEAR) return@repeat
      val next = when (anvil.type) {
        Material.ANVIL -> Material.CHIPPED_ANVIL
        Material.CHIPPED_ANVIL -> Material.DAMAGED_ANVIL
        else -> Material.AIR
      }
      if (next == Material.AIR) anvil.type = Material.AIR
      else anvil.blockData = next.createBlockData().also { data ->
        (anvil.blockData as? org.bukkit.block.data.Directional)?.facing?.let { (data as? org.bukkit.block.data.Directional)?.facing = it }
      }
      if (next == Material.AIR) return Material.AIR
    }
    return anvil.type
  }
}
