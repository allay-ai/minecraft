package chat.allay.modcore.menus

import chat.allay.core.Languages
import chat.allay.core.Locales
import chat.allay.modcore.audience
import chat.allay.modcore.of

import net.minecraft.core.component.DataComponents
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/** The languages Allay speaks, for the player to pick one, or Automatic to have Allay pick by IP and game language. */
class LanguageMenu(
  player: ServerPlayer,
  private val locales: Locales,
  private val languages: Languages,
  private val back: () -> Unit,
) : Menu(player, 27, locales.of(player).text("menus.language.title")) {
  private val codes = languages.codes().take(SLOTS.size)

  override fun draw() {
    clear()
    val lang = locales.of(player)
    val chosen = languages.chosen(player.audience)
    val current = lang.text("menus.language.current")
    val pick = lang.text("menus.language.pick")
    set(AUTO, icon(Items.COMPASS, lang.text("menus.language.auto"), listOf(lang.text("menus.language.auto_what"), "", if (chosen == null) current else pick)).shine(chosen == null))
    codes.forEachIndexed { index, code ->
      set(SLOTS[index], icon(Items.PAPER, "&e" + languages.name(code), listOf("&8$code", "", if (code == chosen) current else pick)).shine(code == chosen))
    }
    set(BACK, icon(Items.ARROW, lang.text("menus.back")))
  }

  private fun ItemStack.shine(on: Boolean) = apply { set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, on) }

  override fun click(slot: Int) {
    when (slot) {
      AUTO -> picked(null)
      BACK -> { press(); back() }
      in SLOTS -> codes.getOrNull(SLOTS.indexOf(slot))?.let(::picked)
    }
  }

  /** Back to the settings, already in the new language. */
  private fun picked(code: String?) {
    languages.choose(player.audience, code)
    press()
    back()
  }

  private companion object {
    const val AUTO = 10
    const val BACK = 22
    val SLOTS = listOf(12, 13, 14, 15, 16, 19, 20, 21, 23, 24, 25)
  }
}
