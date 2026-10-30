package chat.allay.spigot.menus

import chat.allay.core.*
import chat.allay.core.tools.*

import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.plugin.Plugin
import chat.allay.core.Lang
import chat.allay.spigot.menus.Menu.Companion.lore
import chat.allay.spigot.tools.Trails
import chat.allay.core.tools.Facts
import chat.allay.spigot.tools.Villagers

/** The villagers around, one row each: who they are and how far, then what they sell. A click shows the way. */
class VillagerMenu(
  player: Player,
  private val plugin: Plugin,
  private val lang: Lang,
  private val found: List<Villagers.Found>,
) : Menu(player, 54, lang.text("menus.villagers.title")) {
  private var page = 0

  override fun draw() {
    clear()
    found.drop(page * ROWS).take(ROWS).forEachIndexed { row, villager ->
      val start = row * 9
      val profession = Villagers.profession(villager.trader)
      val name = lang.text("professions.$profession").takeUnless { it == "professions.$profession" } ?: profession
      val dx = villager.trader.location.x - player.location.x
      val dz = villager.trader.location.z - player.location.z
      val lore = listOfNotNull(
        (villager.trader as? Villager)?.let { lang.text("menus.villagers.level", "level" to it.villagerLevel) },
        lang.text("menus.villagers.where", "distance" to Math.round(villager.distance), "direction" to lang.text("directions.${Facts.direction(dx, dz)}")),
        lang.text("menus.villagers.path"),
      )
      set(start, icon(Villagers.icon(profession), "&e$name", lore))
      villager.trades.take(8).forEachIndexed { index, offer ->
        val left = offer.maxUses - offer.uses
        val prices = Villagers.prices(offer).joinToString(" + ") { Villagers.price(lang, it) }
        val tradeLore = listOf(
          lang.text("menus.villagers.price", "price" to prices),
          if (left > 0) lang.text("menus.villagers.left", "left" to left) else lang.text("menus.villagers.out"),
        )
        set(start + 1 + index, offer.result.clone().lore(null, tradeLore))
      }
    }
    if (found.size > ROWS) {
      set(PREVIOUS, icon(Material.SPECTRAL_ARROW, lang.text("menus.previous")))
      set(NEXT, icon(Material.SPECTRAL_ARROW, lang.text("menus.next")))
    }
    set(CLOSE, icon(Material.BARRIER, lang.text("menus.close")))
  }

  override fun click(slot: Int) {
    when (slot) {
      CLOSE -> { press(); player.closeInventory() }
      PREVIOUS -> if (page > 0) { page--; press(); draw() }
      NEXT -> if ((page + 1) * ROWS < found.size) { page++; press(); draw() }
      else -> {
        val villager = found.getOrNull(page * ROWS + slot / 9)?.takeIf { slot < ROWS * 9 } ?: return
        if (!villager.trader.isValid) return
        player.closeInventory()
        player.playSound(player.location, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.6f, 1.4f)
        Trails.show(plugin, player, villager.trader.location.add(0.0, 1.0, 0.0), TrailClock.fixed(15))
      }
    }
  }

  private companion object {
    const val ROWS = 5
    const val PREVIOUS = 48
    const val NEXT = 50
    const val CLOSE = 53
  }
}
