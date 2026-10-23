package chat.allay.fabric.automation

import chat.allay.core.Locales
import chat.allay.core.Scheduler
import chat.allay.core.tasks.Task

import net.minecraft.server.level.ServerPlayer

/** Stands in until automation's picking is ported: nobody picks anything. */
class Picking(private val scheduler: Scheduler, private val locales: Locales, private val automation: Automation) {
  fun stop(player: ServerPlayer, task: Task?) {}
  fun tick(now: Long) {}
}
