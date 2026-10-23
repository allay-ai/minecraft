package chat.allay.fabric.automation

import chat.allay.core.Scheduler
import chat.allay.fabric.tools.Tool

/** Stands in until automation's jobs are ported: no furnace or brewing jobs run. */
object Jobs {
  lateinit var automation: Automation

  fun active(job: chat.allay.core.tasks.Agents.Job) = false

  fun start(scheduler: Scheduler) {}

  val all = emptyList<Tool>()
}
