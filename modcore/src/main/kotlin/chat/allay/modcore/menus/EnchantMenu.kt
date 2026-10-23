package chat.allay.modcore.menus

import chat.allay.core.*
import chat.allay.core.tools.*
import chat.allay.modcore.Sounds
import chat.allay.modcore.audience
import chat.allay.modcore.menus.Menu.Companion.lore
import chat.allay.modcore.tools.Anvils

import net.minecraft.core.Holder
import net.minecraft.core.component.DataComponents
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.tags.EnchantmentTags
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.Enchantment
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.item.enchantment.ItemEnchantments

/**
 * Enchantments for the item in hand. The player picks the ones they want, and the window shows the cheapest order
 * to combine the books on an anvil, step by step, from [Mechanics.anvilPlan].
 */
class EnchantMenu(player: ServerPlayer, private val lang: Lang, private val item: ItemStack) : Menu(player, 54, lang.text("menus.enchant.title")) {
  private val options: List<Holder<Enchantment>> = applicable(player, item)
  private val selected = linkedSetOf<Holder<Enchantment>>()
  private var plan: Plan? = null
  private var page = 0

  private class Plan(val books: List<Holder<Enchantment>>, val result: Mechanics.AnvilPlan)

  private val penalty get() = item.get(DataComponents.REPAIR_COST) ?: 0

  override fun draw() {
    clear()
    val plan = plan
    if (plan == null) drawChoice() else drawPlan(plan)
    set(CLOSE, icon(Items.BARRIER, lang.text("menus.close")))
  }

  private fun drawChoice() {
    set(ITEM, item.copy().lore(null, listOf(lang.text("menus.enchant.penalty", "penalty" to penalty))))
    options.take(36).forEachIndexed { index, enchantment ->
      val has = level(enchantment)
      val max = enchantment.value().maxLevel
      val conflict = conflicts(enchantment)
      val status = when {
        has >= max -> lang.text("menus.enchant.has")
        enchantment in selected -> lang.text("menus.enchant.selected")
        conflict -> lang.text("menus.enchant.conflict")
        else -> lang.text("menus.enchant.pick")
      }
      val lore = listOfNotNull(
        status,
        lang.text("menus.enchant.treasure").takeIf { treasure(enchantment) && !cursed(enchantment) },
        lang.text("menus.enchant.curse").takeIf { cursed(enchantment) },
        lang.text("menus.enchant.price", "cost" to Mechanics.bookWeight(key(enchantment))),
      )
      // Picked and possible ones shine; the rest go dull, so the choice reads at a glance.
      set(FIRST + index, book(listOf(enchantment), shine = enchantment in selected || (!conflict && has < max)).lore(null, lore))
    }
    set(PLAN, icon(Items.ANVIL, lang.text("menus.enchant.plan"), listOf(lang.text("menus.enchant.plan_hint"))))
  }

  private fun drawPlan(plan: Plan) {
    val steps = plan.result.steps
    steps.drop(page * ROWS).take(ROWS).forEachIndexed { row, step ->
      val n = page * ROWS + row + 1
      val start = row * 9
      set(start, piece(step.left, plan))
      set(start + 1, icon(Items.STAINED_GLASS_PANE.lightGray(), lang.text("menus.enchant.plus")))
      set(start + 2, piece(step.right, plan))
      set(start + 3, icon(Items.STAINED_GLASS_PANE.lightGray(), lang.text("menus.enchant.result")))
      set(start + 4, piece(step.left + step.right, plan))
      val lore = listOfNotNull(lang.text("menus.enchant.too_expensive").takeIf { step.cost >= Mechanics.TOO_EXPENSIVE })
      set(start + 6, icon(Items.EXPERIENCE_BOTTLE, lang.text("menus.enchant.step", "n" to n, "cost" to step.cost), lore, amount = step.cost.coerceAtLeast(1)))
    }
    set(BACK, icon(Items.ARROW, lang.text("menus.back")))
    if (steps.size > ROWS) {
      set(PREVIOUS, icon(Items.SPECTRAL_ARROW, lang.text("menus.previous")))
      set(NEXT, icon(Items.SPECTRAL_ARROW, lang.text("menus.next")))
    }
    val lore = listOfNotNull(lang.text("menus.enchant.too_expensive").takeIf { plan.result.tooExpensive })
    set(PLAN, icon(Items.ANVIL, lang.text("menus.enchant.total", "total" to plan.result.total), lore))
  }

  /** What a set of pieces looks like: the item with the books' enchantments on it, or one book with all of them. */
  private fun piece(pieces: List<Int>, plan: Plan): ItemStack {
    val enchantments = pieces.filter { it > 0 }.map { plan.books[it - 1] }
    if (0 !in pieces) return book(enchantments, shine = true)
    return item.copy().apply {
      // Set, not upgraded: the level the book brings, as Bukkit's `addUnsafeEnchantment`.
      EnchantmentHelper.updateEnchantments(this) { mutable -> enchantments.forEach { mutable.set(it, it.value().maxLevel) } }
    }.lore(null, listOf(lang.text("menus.enchant.item")))
  }

  override fun click(slot: Int) {
    when {
      slot == CLOSE -> close()
      plan != null -> when (slot) {
        BACK -> { plan = null; page = 0; draw() }
        PREVIOUS -> if (page > 0) { page--; draw() }
        NEXT -> if ((page + 1) * ROWS < plan!!.result.steps.size) { page++; draw() }
      }
      slot == PLAN -> {
        if (selected.isEmpty()) {
          Sounds.play(player, SoundEvents.NOTE_BLOCK_BASS, 0.6f, 0.8f)
          player.audience.send(Legacy.color(lang.text("menus.enchant.plan_empty")))
          return
        }
        val books = selected.toList()
        val result = Mechanics.anvilPlan(penalty, books.map { Mechanics.Book(key(it), it.value().maxLevel, Mechanics.bookWeight(key(it))) })
        plan = Plan(books, result)
        Sounds.play(player, SoundEvents.ANVIL_USE, 0.4f, 1.2f)
        draw()
      }
      slot - FIRST in options.indices.take(36) -> {
        val enchantment = options[slot - FIRST]
        when {
          enchantment in selected -> selected.remove(enchantment)
          // Ten books is where the planner stops; nobody needs more on one item.
          selected.size < 10 && !conflicts(enchantment) && level(enchantment) < enchantment.value().maxLevel -> selected.add(enchantment)
          else -> {
            Sounds.play(player, SoundEvents.NOTE_BLOCK_BASS, 0.6f, 0.8f)
            return
          }
        }
        Sounds.play(player, SoundEvents.UI_BUTTON_CLICK, 0.4f, 1.4f)
        draw()
      }
    }
  }

  private fun level(enchantment: Holder<Enchantment>) = EnchantmentHelper.getItemEnchantmentLevel(enchantment, item)

  /** Whether an enchantment clashes with what's picked or already on the item; the same enchantment never clashes with itself. */
  private fun conflicts(enchantment: Holder<Enchantment>): Boolean =
    (selected + item.enchantments.keySet()).any { it != enchantment && !Enchantment.areCompatible(it, enchantment) }

  private fun book(enchantments: List<Holder<Enchantment>>, shine: Boolean): ItemStack = ItemStack(Items.ENCHANTED_BOOK).apply {
    val stored = ItemEnchantments.Mutable(ItemEnchantments.EMPTY)
    enchantments.forEach { stored.set(it, it.value().maxLevel) }
    set(DataComponents.STORED_ENCHANTMENTS, stored.toImmutable())
    if (!shine) set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, false)
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

    /** The enchantment's name without its namespace, as Bukkit's `key.key`. */
    private fun key(enchantment: Holder<Enchantment>) = enchantment.unwrapKey().map { it.identifier().path }.orElse("")

    /** Not offered by the enchanting table, as Bukkit's `isTreasure`. */
    private fun treasure(enchantment: Holder<Enchantment>) = !enchantment.`is`(EnchantmentTags.IN_ENCHANTING_TABLE)

    private fun cursed(enchantment: Holder<Enchantment>) = enchantment.`is`(EnchantmentTags.CURSE)

    /** Enchantments the game allows on this item, curses last. */
    fun applicable(player: ServerPlayer, item: ItemStack): List<Holder<Enchantment>> = Anvils.registry(player).listElements().toList()
      .filter { it.value().canEnchant(item) }
      .sortedWith(compareBy<Holder<Enchantment>> { cursed(it) }.thenBy { key(it) })
  }
}
