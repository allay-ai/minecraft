package chat.allay.core

import chat.allay.core.tools.CommandCatalog

/**
 * Runs `/askx` commands as the player who asked, minus `blocked_commands`. Nothing runs unless every command exists
 * and the player may run it, where the platform can tell (since 6.16): half a request done is worse than none.
 */
class Executor(private val platform: Platform, private val config: Config, private val locales: Locales, private val animator: Animator) {
  fun executeCommandsSequentially(commands: List<String>, sender: Audience) {
    if (blocked(commands, sender)) return
    platform.scheduler.main {
      if (refused(commands, sender)) return@main
      var delay = 0L
      for (command in commands) {
        platform.scheduler.later(delay) { execute(command, sender) }
        delay += 5L // A quarter of a second between commands, so each sees what the last one did.
      }
    }
  }

  fun executeCommandsParallel(commands: List<String>, sender: Audience) {
    if (blocked(commands, sender)) return
    platform.scheduler.main {
      if (refused(commands, sender)) return@main
      for (command in commands) execute(command, sender)
    }
  }

  /** True when a command doesn't exist, isn't the player's to run, or doesn't parse; the sender has heard which. Server thread. */
  private fun refused(commands: List<String>, sender: Audience): Boolean {
    for (command in commands) {
      val check = runCatching { platform.check(sender, command) }.getOrNull() ?: continue
      val key = when (check.verdict) {
        CommandCatalog.Verdict.OK -> continue
        CommandCatalog.Verdict.UNKNOWN -> "messages.command_unknown"
        CommandCatalog.Verdict.FORBIDDEN -> "messages.command_forbidden"
        CommandCatalog.Verdict.SYNTAX -> "messages.command_syntax"
      }
      (sender as? PlayerAudience)?.let(animator::error)
      locales.of(sender).send(sender, key, "command" to command)
      return true
    }
    return false
  }

  /** True when a command is blocked; the sender has heard so. */
  private fun blocked(commands: List<String>, sender: Audience): Boolean {
    val blocked = commands.any { command -> command.split(" ")[0].lowercase() in config.blockedCommands }
    if (blocked) {
      (sender as? PlayerAudience)?.let(animator::error)
      locales.of(sender).send(sender, "messages.blocked_command")
    }
    return blocked
  }

  private fun execute(command: String, sender: Audience) {
    try {
      val result = platform.dispatch(sender, command)
      locales.of(sender).send(sender, if (result) "messages.command_done" else "messages.command_failed", "command" to command)
    } catch (e: Exception) {
      locales.of(sender).send(sender, "messages.command_error", "command" to command)
      platform.log.warning("❌ Error executing command '$command': ${e.message}")
    }
  }
}
