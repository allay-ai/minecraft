package chat.allay.spigot.menus

import chat.allay.core.*
import chat.allay.core.tools.*

import org.bukkit.Material
import org.bukkit.Registry
import org.bukkit.Sound
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.EnchantmentStorageMeta
import org.bukkit.inventory.meta.Repairable
import chat.allay.core.Lang
import chat.allay.spigot.menus.Menu.Companion.lore
import chat.allay.core.tools.Mechanics

/**
 * Enchantments for the item in hand. The player picks the ones they want, and the window shows the cheapest order
 * to combine the books on an anvil, step by step, from [Mechanics.anvilPlan].
 */
class EnchantMenu(player: Player, private val lang: Lang, private val item: ItemStack) : Menu(player, 54, lang.text("menus.enchant.title")) {
  private val options: List<Enchantment> = applicable(item)
  private val selected = linkedSetOf<Enchantment>()
  private var plan: Plan? = null
  private var page = 0

  private class Plan(val books: List<Enchantment>, val result: Mechanics.AnvilPlan)

  private val penalty get() = (item.itemMeta as? Repairable)?.repairCost ?: 0

  override fun draw() {
    clear()
    val plan = plan
    if (plan == null) drawChoice() else drawPlan(plan)
    set(CLOSE, icon(Material.BARRIER, lang.text("menus.close")))
  }

  private fun drawChoice() {
    set(ITEM, item.clone().lore(null, listOf(lang.text("menus.enchant.penalty", "penalty" to penalty))))
    options.take(36).forEachIndexed { index, enchantment ->
      val has = item.getEnchantmentLevel(enchantment)
      val conflict = conflicts(enchantment)
      val status = when {
        has >= enchantment.maxLevel -> lang.text("menus.enchant.has")
        enchantment in selected -> lang.text("menus.enchant.selected")
        conflict -> lang.text("menus.enchant.conflict")
        else -> lang.text("menus.enchant.pick")
      }
      val lore = listOfNotNull(
        status,
        lang.text("menus.enchant.treasure").takeIf { enchantment.isTreasure && !enchantment.isCursed },
        lang.text("menus.enchant.curse").takeIf { enchantment.isCursed },
        lang.text("menus.enchant.price", "cost" to Mechanics.bookWeight(enchantment.key.key)),
      )
      // Picked and possible ones shine; the rest go dull, so the choice reads at a glance.
      set(FIRST + index, book(listOf(enchantment), shine = enchantment in selected || (!conflict && has < enchantment.maxLevel)).lore(null, lore))
    }
    set(PLAN, icon(Material.ANVIL, lang.text("menus.enchant.plan"), listOf(lang.text("menus.enchant.plan_hint"))))
  }

  private fun drawPlan(plan: Plan) {
    val steps = plan.result.steps
    steps.drop(page * ROWS).take(ROWS).forEachIndexed { row, step ->
      val n = page * ROWS + row + 1
      val start = row * 9
      set(start, piece(step.left, plan))
      set(start + 1, icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE, lang.text("menus.enchant.plus")))
      set(start + 2, piece(step.right, plan))
      set(start + 3, icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE, lang.text("menus.enchant.result")))
      set(start + 4, piece(step.left + step.right, plan))
      val lore = listOfNotNull(lang.text("menus.enchant.too_expensive").takeIf { step.cost >= Mechanics.TOO_EXPENSIVE })
      set(start + 6, icon(Material.EXPERIENCE_BOTTLE, lang.text("menus.enchant.step", "n" to n, "cost" to step.cost), lore, amount = step.cost.coerceAtLeast(1)))
    }
    set(BACK, icon(Material.ARROW, lang.text("menus.back")))
    if (steps.size > ROWS) {
      set(PREVIOUS, icon(Material.SPECTRAL_ARROW, lang.text("menus.previous")))
      set(NEXT, icon(Material.SPECTRAL_ARROW, lang.text("menus.next")))
    }
    val lore = listOfNotNull(lang.text("menus.enchant.too_expensive").takeIf { plan.result.tooExpensive })
    set(PLAN, icon(Material.ANVIL, lang.text("menus.enchant.total", "total" to plan.result.total), lore))
  }

  /** What a set of pieces looks like: the item with the books' enchantments on it, or one book with all of them. */
  private fun piece(pieces: List<Int>, plan: Plan): ItemStack {
    val enchantments = pieces.filter { it > 0 }.map { plan.books[it - 1] }
    if (0 !in pieces) return book(enchantments, shine = true)
    return item.clone().apply {
      enchantments.forEach { addUnsafeEnchantment(it, it.maxLevel) }
    }.lore(null, listOf(lang.text("menus.enchant.item")))
  }

  override fun click(slot: Int) {
    when {
      slot == CLOSE -> player.closeInventory()
      plan != null -> when (slot) {
        BACK -> { plan = null; page = 0; draw() }
        PREVIOUS -> if (page > 0) { page--; draw() }
        NEXT -> if ((page + 1) * ROWS < plan!!.result.steps.size) { page++; draw() }
      }
      slot == PLAN -> {
        if (selected.isEmpty()) {
          player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f)
          player.sendMessage(Legacy.color(lang.text("menus.enchant.plan_empty")))
          return
        }
        val books = selected.toList()
        val result = Mechanics.anvilPlan(penalty, books.map { Mechanics.Book(it.key.key, it.maxLevel, Mechanics.bookWeight(it.key.key)) })
        plan = Plan(books, result)
        player.playSound(player.location, Sound.BLOCK_ANVIL_USE, 0.4f, 1.2f)
        draw()
      }
      slot - FIRST in options.indices.take(36) -> {
        val enchantment = options[slot - FIRST]
        when {
          enchantment in selected -> selected.remove(enchantment)
          // Ten books is where the planner stops; nobody needs more on one item.
          selected.size < 10 && !conflicts(enchantment) && item.getEnchantmentLevel(enchantment) < enchantment.maxLevel -> selected.add(enchantment)
          else -> {
            player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f)
            return
          }
        }
        player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.4f, 1.4f)
        draw()
      }
    }
  }

  /** Whether an enchantment clashes with what's picked or already on the item; the same enchantment never clashes with itself. */
  private fun conflicts(enchantment: Enchantment): Boolean =
    (selected + item.enchantments.keys).any { it != enchantment && (it.conflictsWith(enchantment) || enchantment.conflictsWith(it)) }

  private fun book(enchantments: List<Enchantment>, shine: Boolean): ItemStack = ItemStack(Material.ENCHANTED_BOOK).apply {
    val meta = itemMeta as EnchantmentStorageMeta
    enchantments.forEach { meta.addStoredEnchant(it, it.maxLevel, true) }
    if (!shine) meta.setEnchantmentGlintOverride(false)
    itemMeta = meta
  }

  companion object {
    private const val ITEM = 4
    private const val FIRST = 9
    private const val ROWS = 5
    private const val BACK = 45
    private const val PREVIOUS = 48
    private const val PLAN = 49
    private const val NEXT = 50
    private const val CLOSE = 53

    /** Enchantments the game allows on this item, curses last. */
    fun applicable(item: ItemStack): List<Enchantment> = Registry.ENCHANTMENT.filter { it.canEnchantItem(item) }
      .sortedWith(compareBy<Enchantment> { it.isCursed }.thenBy { it.key.key })
  }
}
