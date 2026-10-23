package chat.allay.fabric

import chat.allay.core.Clicks
import chat.allay.core.PlayerAudience
import chat.allay.core.Run
import chat.allay.core.tools.Button
import chat.allay.core.tools.InlineButtons
import chat.allay.core.tools.ToolError
import chat.allay.fabric.tools.Features

/** Stands in until the buttons that reach into the world are ported: every such tag stays a label. */
class GameClicks(private val features: Features) : Clicks.Game {
  override fun check(run: Run, player: PlayerAudience, tag: InlineButtons.Part.Tag): Button = throw ToolError("buttons don't work here yet")
  override fun press(player: PlayerAudience, button: Button) {}
}
