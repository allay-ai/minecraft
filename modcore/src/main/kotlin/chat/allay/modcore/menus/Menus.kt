package chat.allay.modcore.menus

import chat.allay.core.Allay
import chat.allay.core.PlayerAudience
import chat.allay.modcore.of
import chat.allay.modcore.player
import chat.allay.modcore.tools.Features
import chat.allay.modcore.tools.ToolContext

/** `/allay` and `/allay language`: the settings window, with the admins' row, and the languages to pick from. */
object Menus {
  fun settings(player: PlayerAudience, allay: Allay<ToolContext>, features: Features, languages: () -> Unit) {
    val game = player.player
    SettingsMenu(game, allay.locales.of(game), features, allay.memory, allay.privacy, allay.platform.version, allay.languages, SettingsMenu.Admin(
      reload = { allay.reload(player) },
      status = { allay.billing.status(player) },
      // Nothing to pay with a key of your own, a backend without billing, or a subscription that runs.
      pay = { !allay.config.provider.byok && allay.watch?.status?.let { it.billing && it.active } != true },
      link = { allay.billing.link(player) },
    ), languages).open()
  }

  fun languages(player: PlayerAudience, allay: Allay<ToolContext>, back: () -> Unit): Unit =
    LanguageMenu(player.player, allay.locales, allay.languages, back).open()
}
