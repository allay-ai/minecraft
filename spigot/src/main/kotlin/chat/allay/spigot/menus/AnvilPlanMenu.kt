package chat.allay.spigot.menus

import chat.allay.core.*
import chat.allay.core.tools.*

import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import chat.allay.core.Lang
import chat.allay.spigot.menus.Menu.Companion.lore
import chat.allay.spigot.tools.Anvils

/**
 * The anvil plan for what the player has, a use a row: the left piece, the right one, what comes out, and its price.
 * Pieces from the inventory show as they are, with their slot; pieces an earlier step made show as that step's result.
 */
class AnvilPlanMenu(player: Player, private val lang: Lang, private val planned: Anvils.Planned) : Menu(player, 54, lang.text("menus.anvil.title")) {
  private val steps = planned.plan.steps
  private val type = planned.sources.first().item.type
  private var page = 0

  override fun draw() {
    clear()
    steps.drop(page * ROWS).take(ROWS).forEachIndexed { row, step ->
      val n = page * ROWS + row + 1
      val start = row * 9
      set(start, piece(step.left))
      set(start + 1, icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE, lang.text("menus.enchant.plus")))
      set(start + 2, piece(step.right))
      set(start + 3, icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE, lang.text("menus.enchant.result")))
      set(start + 4, Anvils.render(type, step.result))
      val lore = listOfNotNull(lang.text("menus.enchant.too_expensive").takeIf { step.cost >= Mechanics.TOO_EXPENSIVE })
      set(start + 6, icon(Material.EXPERIENCE_BOTTLE, lang.text("menus.enchant.step", "n" to n, "cost" to step.cost), lore, amount = step.cost.coerceAtLeast(1)))
    }
    if (steps.size > ROWS) {
      set(PREVIOUS, icon(Material.SPECTRAL_ARROW, lang.text("menus.previous")))
      set(NEXT, icon(Material.SPECTRAL_ARROW, lang.text("menus.next")))
    }
    val lore = listOfNotNull(lang.text("menus.enchant.too_expensive").takeIf { planned.plan.tooExpensive })
    set(TOTAL, icon(Material.ANVIL, lang.text("menus.enchant.total", "total" to planned.plan.total), lore))
    set(CLOSE, icon(Material.BARRIER, lang.text("menus.close")))
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
      return source.item.clone().apply { amount = 1 }.lore(null, listOf(where))
    }
    val made = steps.first { (it.left + it.right).sorted() == pieces.sorted() }
    return Anvils.render(type, made.result).lore(null, listOf(lang.text("menus.anvil.made")))
  }

  override fun click(slot: Int) {
    when (slot) {
      CLOSE -> player.closeInventory()
      PREVIOUS -> if (page > 0) { page--; draw() }
      NEXT -> if ((page + 1) * ROWS < steps.size) { page++; draw() }
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
