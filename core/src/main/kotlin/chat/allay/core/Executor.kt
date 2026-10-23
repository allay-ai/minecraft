package chat.allay.core

/** Runs `/askx` commands as the player who asked, minus `blocked_commands`. */
class Executor(private val platform: Platform, private val config: Config, private val locales: Locales, private val animator: Animator) {
  fun executeCommandsSequentially(commands: List<String>, sender: Audience) {
    if (blocked(commands, sender)) return
    var delay = 0L
    for (command in commands) {
      platform.scheduler.later(delay) { execute(command, sender) }
      delay += 5L // A quarter of a second between commands, so each sees what the last one did.
    }
  }

  fun executeCommandsParallel(commands: List<String>, sender: Audience) {
    if (blocked(commands, sender)) return
    for (command in commands) platform.scheduler.main { execute(command, sender) }
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
