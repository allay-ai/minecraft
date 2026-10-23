package chat.allay.fabric.menus

import chat.allay.core.Allay
import chat.allay.core.PlayerAudience
import chat.allay.fabric.tools.Features
import chat.allay.fabric.tools.ToolContext

/** Stands in until the chest windows are ported. */
object Menus {
  fun settings(player: PlayerAudience, allay: Allay<ToolContext>, features: Features, languages: () -> Unit) {}
  fun languages(player: PlayerAudience, allay: Allay<ToolContext>, back: () -> Unit) {}
}
