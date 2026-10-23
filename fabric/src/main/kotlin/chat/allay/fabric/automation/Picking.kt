package chat.allay.fabric.automation

import chat.allay.core.Locales
import chat.allay.core.Scheduler
import chat.allay.core.tasks.Kind
import chat.allay.core.tasks.Task
import chat.allay.fabric.tools.BlockAt

import net.minecraft.server.level.ServerPlayer

/** Stands in until automation's picking is ported: nobody picks anything. */
class Picking(private val scheduler: Scheduler, private val locales: Locales, private val automation: Automation) {
  fun start(player: ServerPlayer, task: Task, kind: Kind, looking: Boolean): Int = 0
  fun picked(player: ServerPlayer, task: Task): List<BlockAt> = emptyList()
  fun stop(player: ServerPlayer, task: Task?) {}
  fun button(player: ServerPlayer, action: String) {}
  fun tick(now: Long) {}
}
