package chat.allay.spigot.menus

import chat.allay.core.*
import chat.allay.core.tools.*

import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import chat.allay.core.Lang
import chat.allay.spigot.menus.Menu.Companion.lore
import chat.allay.spigot.tools.BrewingRecipes

/**
 * How to brew one potion, a step a row: the bottle, the reagent, and what comes out, from a water bottle on. The
 * steps come from the game's own recipes ([BrewingRecipes]); like every [Menu], it only shows pictures.
 */
class BrewingMenu(player: Player, private val lang: Lang, private val target: Brewing.Bottle, private val chain: List<Brewing.Brew>) :
  Menu(player, 54, lang.text("menus.brewing.title")) {
  private var page = 0

  override fun draw() {
    clear()
    chain.drop(page * ROWS).take(ROWS).forEachIndexed { row, brew ->
      val n = page * ROWS + row + 1
      val start = row * 9 + 1
      set(start, BrewingRecipes.bottle(brew.from).lore(null, listOf(lang.text("menus.brewing.bottle"))))
      set(start + 1, icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE, lang.text("menus.enchant.plus")))
      set(start + 2, reagent(brew.reagent))
      set(start + 3, icon(Material.LIGHT_GRAY_STAINED_GLASS_PANE, lang.text("menus.enchant.result")))
      set(start + 4, BrewingRecipes.bottle(brew.to).lore(null, listOf(lang.text("menus.brewing.step", "n" to n, "total" to chain.size))))
    }
    set(FUEL, icon(Material.BLAZE_POWDER, lang.text("menus.brewing.fuel"), listOf(lang.text("menus.brewing.fuel_hint"))))
    set(TARGET, BrewingRecipes.bottle(target).lore(null, listOf(lang.text("menus.brewing.target"))))
    if (chain.size > ROWS) {
      set(PREVIOUS, icon(Material.SPECTRAL_ARROW, lang.text("menus.previous")))
      set(NEXT, icon(Material.SPECTRAL_ARROW, lang.text("menus.next")))
    }
    set(CLOSE, icon(Material.BARRIER, lang.text("menus.close")))
  }

  private fun reagent(id: String): ItemStack = icon(Material.matchMaterial(id) ?: Material.BARRIER)

  override fun click(slot: Int) {
    when (slot) {
      CLOSE -> { press(); player.closeInventory() }
      PREVIOUS -> if (page > 0) { page--; press(); draw() }
      NEXT -> if ((page + 1) * ROWS < chain.size) { page++; press(); draw() }
    }
  }

  private companion object {
    const val ROWS = 5
    const val FUEL = 45
    const val PREVIOUS = 48
    const val TARGET = 49
    const val NEXT = 50
    const val CLOSE = 53
  }
}
