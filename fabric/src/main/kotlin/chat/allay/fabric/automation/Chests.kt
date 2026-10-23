package chat.allay.fabric.automation

import chat.allay.core.tools.ToolError
import chat.allay.fabric.tools.Tool

import net.minecraft.server.level.ServerPlayer

/** Stands in until automation's chests are ported. */
object Chests {
  val all = emptyList<Tool>()
}

/** Stands in until automation's undo is ported: nothing to put back. */
class Undo {
  /** What [restore] put back: how many chests, and whether the inventory. */
  data class Restored(val chests: Int, val inventory: Boolean)

  fun restore(player: ServerPlayer, minutes: Int): Restored = throw ToolError("nothing to put back")
}
