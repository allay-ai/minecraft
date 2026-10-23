package chat.allay.modcore.menus

import chat.allay.core.*
import chat.allay.core.tools.*
import chat.allay.modcore.Sounds
import chat.allay.modcore.menus.Menu.Companion.lore
import chat.allay.modcore.tools.Trails
import chat.allay.modcore.tools.Villagers

import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.item.Items

/** The villagers around, one row each: who they are and how far, then what they sell. A click shows the way. */
class VillagerMenu(
  player: ServerPlayer,
  private val scheduler: Scheduler,
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
      val dx = villager.trader.x - player.x
      val dz = villager.trader.z - player.z
      val lore = listOfNotNull(
        (villager.trader as? Villager)?.let { lang.text("menus.villagers.level", "level" to it.villagerData.level()) },
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
        set(start + 1 + index, offer.result.copy().lore(null, tradeLore))
      }
    }
    if (found.size > ROWS) {
      set(PREVIOUS, icon(Items.SPECTRAL_ARROW, lang.text("menus.previous")))
      set(NEXT, icon(Items.SPECTRAL_ARROW, lang.text("menus.next")))
    }
    set(CLOSE, icon(Items.BARRIER, lang.text("menus.close")))
  }

  override fun click(slot: Int) {
    when (slot) {
      CLOSE -> close()
      PREVIOUS -> if (page > 0) { page--; draw() }
      NEXT -> if ((page + 1) * ROWS < found.size) { page++; draw() }
      else -> {
        val villager = found.getOrNull(page * ROWS + slot / 9)?.takeIf { slot < ROWS * 9 } ?: return
        val trader = villager.trader
        if (!trader.isAlive) return
        close()
        Sounds.play(player, SoundEvents.NOTE_BLOCK_CHIME, 0.6f, 1.4f)
        Trails.show(scheduler, player, Trails.Target(trader.level().dimension(), trader.position().add(0.0, 1.0, 0.0)), 15)
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
