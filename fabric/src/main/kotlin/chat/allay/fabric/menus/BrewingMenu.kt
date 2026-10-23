package chat.allay.fabric.menus

import chat.allay.core.*
import chat.allay.core.tools.*
import chat.allay.fabric.menus.Menu.Companion.lore
import chat.allay.fabric.tools.BrewingRecipes
import chat.allay.fabric.tools.InteractTools

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * How to brew one potion, a step a row: the bottle, the reagent, and what comes out, from a water bottle on. The
 * steps come from the game's own recipes ([BrewingRecipes]); like every [Menu], it only shows pictures.
 */
class BrewingMenu(player: ServerPlayer, private val lang: Lang, private val target: Brewing.Bottle, private val chain: List<Brewing.Brew>) :
  Menu(player, 54, lang.text("menus.brewing.title")) {
  private var page = 0

  override fun draw() {
    clear()
    chain.drop(page * ROWS).take(ROWS).forEachIndexed { row, brew ->
      val n = page * ROWS + row + 1
      val start = row * 9 + 1
      set(start, BrewingRecipes.bottle(brew.from).lore(null, listOf(lang.text("menus.brewing.bottle"))))
      set(start + 1, icon(Items.STAINED_GLASS_PANE.lightGray(), lang.text("menus.enchant.plus")))
      set(start + 2, reagent(brew.reagent))
      set(start + 3, icon(Items.STAINED_GLASS_PANE.lightGray(), lang.text("menus.enchant.result")))
      set(start + 4, BrewingRecipes.bottle(brew.to).lore(null, listOf(lang.text("menus.brewing.step", "n" to n, "total" to chain.size))))
    }
    set(FUEL, icon(Items.BLAZE_POWDER, lang.text("menus.brewing.fuel"), listOf(lang.text("menus.brewing.fuel_hint"))))
    set(TARGET, BrewingRecipes.bottle(target).lore(null, listOf(lang.text("menus.brewing.target"))))
    if (chain.size > ROWS) {
      set(PREVIOUS, icon(Items.SPECTRAL_ARROW, lang.text("menus.previous")))
      set(NEXT, icon(Items.SPECTRAL_ARROW, lang.text("menus.next")))
    }
    set(CLOSE, icon(Items.BARRIER, lang.text("menus.close")))
  }

  private fun reagent(id: String): ItemStack = icon(InteractTools.known(id) ?: Items.BARRIER)

  override fun click(slot: Int) {
    when (slot) {
      CLOSE -> close()
      PREVIOUS -> if (page > 0) { page--; draw() }
      NEXT -> if ((page + 1) * ROWS < chain.size) { page++; draw() }
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
