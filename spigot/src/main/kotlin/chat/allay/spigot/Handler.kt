package chat.allay.spigot

import chat.allay.core.*
import chat.allay.core.tools.*
import chat.allay.spigot.tools.ToolContext
import chat.allay.spigot.tools.Drafts
import chat.allay.spigot.tools.Marks
import chat.allay.spigot.tools.Trails

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import chat.allay.spigot.Minecraft.formatMessage
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs `/ask`, `/askall`, `/askx`, and chat mode's questions: one stream per sender, a live display while it runs,
 * then the answer or the commands. A public question and its answer go to every player online; the rest of the
 * display stays with the asker.
 */
class Handler(
  private val plugin: JavaPlugin,
  private val configuration: Config,
  private val locales: Locales,
  private val service: Service<ToolContext>,
  private val animator: Animator,
  private val executor: Executor,
  private val scope: CoroutineScope,
  /** The hint operators get under an error a payment or the config fixes. */
  private val billing: () -> BillingCommands,
  private val clicks: () -> Clicks,
  private val quota: QuotaGate,
  private val automation: chat.allay.spigot.automation.Automation,
) {
  private val runs = ConcurrentHashMap<String, Run>()
  /** When each player last asked everyone, for `askall.cooldown_seconds`. */
  private val lastPublic = ConcurrentHashMap<String, Long>()

  /** `/allay cancel`: stops the sender's run, like Esc in Claude Code, or with no run, the way trail. */
  fun cancel(sender: CommandSender): Boolean {
    val run = runs.remove(key(sender))
    if (run == null) {
      if (sender is Player) {
        // Several may be out at once; one /allay cancel hides them all.
        val trail = Trails.stop(sender)
        val marks = Marks.clear(sender)
        val draft = Drafts.clear(sender)
        // Agents say themselves that they stopped.
        if (automation.cancel(sender)) return true
        if (trail || marks || draft) {
          locales.of(sender).send(sender, if (draft) "messages.draft_cleared" else if (marks) "messages.marks_cleared" else "messages.trail_stopped")
          return true
        }
      }
      locales.of(sender).send(sender, "messages.nothing_to_cancel")
      return true
    }
    run.cancel()
    (sender as? Player)?.let(animator::stopPlayerAnimation)
    locales.of(sender).send(sender, "messages.cancelled")
    return true
  }

  /** Whether Allay is answering this player right now; safe from any thread. */
  fun busy(player: UUID) = runs.containsKey(player.toString())

  fun cancelAll() {
    runs.values.forEach(Run::cancel)
    runs.clear()
  }

  /**
   * Asks Allay. [public] shows the question and the answer to everyone online; [tip] puts the chat mode tip
   * under the answer.
   */
  fun ask(sender: CommandSender, message: String, mode: Run.Mode, public: Boolean = false, tip: Boolean = false): Boolean {
    val lang = locales.of(sender)
    // A question everyone sees would show a vanished player, so it stays theirs, with a line saying why.
    if (public && message.isNotBlank() && sender is Player && Vanish.vanished(sender)) {
      lang.send(sender, "public.vanished")
      return ask(sender, message, mode, public = false, tip = tip)
    }
    if (message.isBlank()) {
      lang.send(sender, when {
        mode == Run.Mode.ASKX -> "messages.askx_usage"
        public -> "messages.askall_usage"
        else -> "messages.usage"
      })
      return true
    }
    if (public && !sender.hasPermission(Permissions.ASKALL_NO_COOLDOWN)) {
      val now = System.currentTimeMillis()
      val wait = configuration.askall.cooldownSeconds * 1000 - (now - (lastPublic[key(sender)] ?: 0))
      if (wait > 0) {
        lang.send(sender, "public.cooldown", "seconds" to (wait + 999) / 1000)
        return true
      }
      lastPublic[key(sender)] = now
    }
    if (!quota.allows(sender)) return true
    plugin.logger.info("🔄 Handling ${if (public) "public " else ""}${mode.wire} question")

    if (public) broadcast("public.question", sender, message)
    if (configuration.ui.chatStatusEnabled) {
      lang.send(sender, if (mode == Run.Mode.ASK) "messages.thinking" else "messages.askx_thinking")
    }

    // A new question replaces the one still running.
    runs.remove(key(sender))?.cancel()
    val run = Run(SenderAsker(sender, (sender as? Player)?.let { locales.choice(it.uniqueId) }), mode, lang, configuration.ui.verbSeconds, public)
    run.tip = tip
    run.model = quota.model(sender)
    runs[key(sender)] = run
    animator.start(run)

    val listener = object : Service.Listener {
      override fun toolStarted(callId: String, name: String, args: JsonObject?) = onMain { animator.toolStarted(run, name, args) }
      override fun toolFinished(callId: String, summary: String?, failed: Boolean) = onMain { animator.toolFinished(run, summary, failed) }
    }

    scope.launch {
      try {
        val outcome = service.chat(run, message, listener)
        if (!run.cancelled) onMain { finish(run, outcome) }
      } catch (e: Exception) {
        if (run.cancelled || e is CancellationException) return@launch
        plugin.logger.warning("❌ Error when requesting server: $e")
        onMain {
          (sender as? Player)?.let {
            animator.stopPlayerAnimation(it)
            animator.playErrorSound(it)
          }
          sender.sendMessage(formatMessage(ErrorHandler.message(lang, e, billing().zone(sender))))
          billing().hint(sender, e)
          if (public) everyoneBut(sender).forEach { locales.of(it).send(it, "public.failed") }
        }
      } finally {
        runs.remove(key(sender), run)
        quota.spent((sender as? Player)?.uniqueId, run.cost)
      }
    }
    return true
  }

  private fun finish(run: Run, outcome: Service.Outcome) {
    val sender = run.sender
    val player = sender as? Player
    player?.let(animator::stopPlayerAnimation)

    if (run.mode == Run.Mode.ASK) {
      player?.let(animator::playSuccessSound)
      if (run.public) {
        broadcast("public.answer", sender, outcome.answer, colored = true)
        everyoneBut(sender).forEach(animator::playPublicAnswerSound)
      } else {
        sender.sendMessage(labeled(run, outcome.answer))
      }
      // Under an answer everyone reads, everyone gets the buttons, each with their own ids, and a question
      // button asks everyone again, so the shared thread goes on. The anvil plan reads the asker's inventory: theirs alone.
      // Nobody gets the way to themself.
      val buttons = run.buttons.toList()
      if (run.public) {
        val shared = buttons.map { if (it is Button.Ask) it.copy(public = true) else it }
        plugin.server.onlinePlayers.forEach { reader ->
          val own = shared.filterNot { it is Button.Follow && it.player.equals(reader.name, ignoreCase = true) }
          clicks().show(reader, if (reader == player) own else own.filterNot { it is Button.Anvil })
        }
      } else {
        player?.let { clicks().show(it, buttons) }
      }
      if (run.tip) player?.let { clicks().tip(it) }
      plugin.logger.info("✅ Question answered")
      return
    }

    val commands = outcome.commands?.commands.orEmpty().map { it.trim().removePrefix("/") }.filter { it.isNotEmpty() }
    if (commands.isEmpty()) {
      player?.let(animator::playErrorSound)
      if (outcome.answer.isNotBlank()) sender.sendMessage(labeled(run, outcome.answer)) else run.lang.send(sender, "messages.no_commands")
      plugin.logger.info("❌ No commands to execute")
      return
    }
    player?.let(animator::playSuccessSound)
    if (outcome.answer.isNotBlank()) sender.sendMessage(labeled(run, outcome.answer))
    val sequential = outcome.commands?.is_sequence == true
    run.lang.send(sender, if (sequential) "messages.commands_in_order" else "messages.commands_at_once", "count" to commands.size)
    if (sequential) executor.executeCommandsSequentially(commands, sender) else executor.executeCommandsParallel(commands, sender)
    plugin.logger.info("✅ Askx command handled")
  }

  /**
   * A line every player online reads in their own language, and the console too. The question is the player's
   * own text, so its `&` stays as typed; the answer is Allay's and may use colors. `{papi:…}` is the asker's.
   */
  private fun broadcast(key: String, asker: CommandSender, text: String, colored: Boolean = false) {
    val readers = plugin.server.onlinePlayers + plugin.server.consoleSender
    readers.forEach { reader ->
      val template = locales.of(reader).about(Placeholders.about(asker)).text(key, "player" to asker.name, "text" to Minecraft.SLOT)
      reader.sendMessage(if (colored) formatMessage(template.replace(Minecraft.SLOT, text)) else Minecraft.withText(template, text))
    }
  }

  /** Allay's answer under its name, as public answers have it. The answer is Allay's own text, so its colors stay. */
  private fun labeled(run: Run, answer: String) =
    formatMessage(run.lang.text("messages.answer", "text" to Minecraft.SLOT).replace(Minecraft.SLOT, answer))

  private fun everyoneBut(sender: CommandSender) = plugin.server.onlinePlayers.filter { it != sender }

  private fun onMain(block: () -> Unit) {
    if (plugin.isEnabled) plugin.server.scheduler.runTask(plugin, Runnable(block))
  }

  private fun key(sender: CommandSender) = (sender as? Player)?.uniqueId?.toString() ?: "console:${sender.name}"
}
