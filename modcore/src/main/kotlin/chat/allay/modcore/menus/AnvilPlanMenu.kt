package chat.allay.modcore.menus

import chat.allay.core.*
import chat.allay.core.tools.*
import chat.allay.modcore.menus.Menu.Companion.lore
import chat.allay.modcore.tools.Anvils

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * The anvil plan for what the player has, a use a row: the left piece, the right one, what comes out, and its price.
 * Pieces from the inventory show as they are, with their slot; pieces an earlier step made show as that step's result.
 */
class AnvilPlanMenu(player: ServerPlayer, private val lang: Lang, private val planned: Anvils.Planned) : Menu(player, 54, lang.text("menus.anvil.title")) {
  private val steps = planned.plan.steps
  private val type = planned.sources.first().item.item
  private var page = 0

  override fun draw() {
    clear()
    steps.drop(page * ROWS).take(ROWS).forEachIndexed { row, step ->
      val n = page * ROWS + row + 1
      val start = row * 9
      set(start, piece(step.left))
      set(start + 1, icon(Items.STAINED_GLASS_PANE.lightGray(), lang.text("menus.enchant.plus")))
      set(start + 2, piece(step.right))
      set(start + 3, icon(Items.STAINED_GLASS_PANE.lightGray(), lang.text("menus.enchant.result")))
      set(start + 4, Anvils.render(player, type, step.result))
      val lore = listOfNotNull(lang.text("menus.enchant.too_expensive").takeIf { step.cost >= Mechanics.TOO_EXPENSIVE })
      set(start + 6, icon(Items.EXPERIENCE_BOTTLE, lang.text("menus.enchant.step", "n" to n, "cost" to step.cost), lore, amount = step.cost.coerceAtLeast(1)))
    }
    if (steps.size > ROWS) {
      set(PREVIOUS, icon(Items.SPECTRAL_ARROW, lang.text("menus.previous")))
      set(NEXT, icon(Items.SPECTRAL_ARROW, lang.text("menus.next")))
    }
    val lore = listOfNotNull(lang.text("menus.enchant.too_expensive").takeIf { planned.plan.tooExpensive })
    set(TOTAL, icon(Items.ANVIL, lang.text("menus.enchant.total", "total" to planned.plan.total), lore))
    set(CLOSE, icon(Items.BARRIER, lang.text("menus.close")))
  }

  /** One original piece as the player has it, or what the earlier step that joined these pieces made. */
  private fun piece(pieces: List<Int>): ItemStack {
    if (pieces.size == 1) {
      val source = planned.sources[pieces.single()]
      val where = when (val slot = source.slot) {
        planned.hand -> lang.text("menus.anvil.hand")
        in 0..8 -> lang.text("menus.anvil.hotbar", "n" to slot + 1)
        in AnvilTargets.ARMOR -> lang.text("menus.anvil.worn")
        AnvilTargets.OFFHAND -> lang.text("menus.anvil.offhand")
        else -> lang.text("menus.anvil.inventory", "row" to (slot - 9) / 9 + 1, "column" to (slot - 9) % 9 + 1)
      }
      return source.item.copyWithCount(1).lore(null, listOf(where))
    }
    val made = steps.first { (it.left + it.right).sorted() == pieces.sorted() }
    return Anvils.render(player, type, made.result).lore(null, listOf(lang.text("menus.anvil.made")))
  }

  override fun click(slot: Int) {
    when (slot) {
      CLOSE -> { press(); close() }
      PREVIOUS -> if (page > 0) { page--; press(); draw() }
      NEXT -> if ((page + 1) * ROWS < steps.size) { page++; press(); draw() }
    }
  }

  private companion object {
    const val ROWS = 5
    const val PREVIOUS = 48
    const val TOTAL = 49
    const val NEXT = 50
    const val CLOSE = 53
  }
}
