package chat.allay.spigot.menus

import chat.allay.core.Languages
import chat.allay.core.Locales
import chat.allay.spigot.audience
import chat.allay.spigot.of

import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/** The languages Allay speaks, for the player to pick one, or Automatic to have Allay pick by IP and game language. */
class LanguageMenu(
  player: Player,
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
    set(AUTO, icon(Material.COMPASS, lang.text("menus.language.auto"), listOf(lang.text("menus.language.auto_what"), "", if (chosen == null) current else pick)).shine(chosen == null))
    codes.forEachIndexed { index, code ->
      set(SLOTS[index], icon(Material.PAPER, "&e" + languages.name(code), listOf("&8$code", "", if (code == chosen) current else pick)).shine(code == chosen))
    }
    set(BACK, icon(Material.ARROW, lang.text("menus.back")))
  }

  private fun ItemStack.shine(on: Boolean) = apply { itemMeta = itemMeta?.apply { setEnchantmentGlintOverride(on) } }

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
