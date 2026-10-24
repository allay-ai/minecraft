package chat.allay.core

import chat.allay.core.tools.InlineButtons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs `/ask`, `/askall`, `/askx`, and chat mode's questions: one stream per sender, a live display while it runs,
 * then the answer or the commands. A public question and its answer go to every player online; the rest of the
 * display stays with the asker.
 */
class Handler(
  private val platform: Platform,
  private val configuration: Config,
  private val locales: Locales,
  private val service: Service<*>,
  private val animator: Animator,
  private val executor: Executor,
  private val scope: CoroutineScope,
  /** The hint operators get under an error a payment or the config fixes. */
  private val billing: () -> BillingCommands,
  private val clicks: () -> Clicks,
  private val quota: QuotaGate,
  private val consents: Consents,
  /** Asks a player to agree before their first question. */
  private val privacy: () -> PrivacyCommands,
  /** What `/allay cancel` takes away when no question runs: the game's ways, marks, drafts, and agents. */
  private val shown: Shown,
  /** Other plugins' and mods' expansions, whose tools a run may offer. */
  private val expansions: Expansions? = null,
) {
  /** What a platform shows for a player outside a question, which `/allay cancel` hides. */
  fun interface Shown {
    /**
     * Hides everything at once and says which went: the agents tell the player themselves, so [Hidden.AGENTS] means
     * nothing more to say.
     */
    fun hide(player: PlayerAudience): Hidden
  }

  enum class Hidden(val key: String?) {
    NOTHING("messages.nothing_to_cancel"), AGENTS(null), DRAFT("messages.draft_cleared"), MARKS("messages.marks_cleared"), TRAIL("messages.trail_stopped")
  }

  private val runs = ConcurrentHashMap<String, Run>()
  /** When each player last asked everyone, for `askall.cooldown_seconds`. */
  private val lastPublic = ConcurrentHashMap<String, Long>()

  /** `/allay cancel`: stops the sender's run, like Esc in Claude Code, or with no run, the way trail. */
  fun cancel(sender: Audience): Boolean {
    val run = runs.remove(key(sender))
    if (run == null) {
      val hidden = (sender as? PlayerAudience)?.let(shown::hide) ?: Hidden.NOTHING
      hidden.key?.let { locales.of(sender).send(sender, it) }
      return true
    }
    run.cancel()
    (sender as? PlayerAudience)?.let(animator::stop)
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
  fun ask(sender: Audience, message: String, mode: Run.Mode, public: Boolean = false, tip: Boolean = false): Boolean {
    val lang = locales.of(sender)
    val player = sender as? PlayerAudience
    // A question everyone sees would show a vanished player, so it stays theirs, with a line saying why.
    if (public && message.isNotBlank() && player != null && platform.vanished(player)) {
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
    // Nothing about a player reaches the backend before they agree; the question goes on once they do.
    if (player != null && configuration.privacy.asks && !consents.known(player.uuid)) {
      privacy().ask(player) { ask(sender, message, mode, public, tip) }
      return true
    }
    if (public && !sender.can(Permissions.ASKALL_NO_COOLDOWN)) {
      val now = System.currentTimeMillis()
      val wait = configuration.askall.cooldownSeconds * 1000 - (now - (lastPublic[key(sender)] ?: 0))
      if (wait > 0) {
        lang.send(sender, "public.cooldown", "seconds" to (wait + 999) / 1000)
        return true
      }
      lastPublic[key(sender)] = now
    }
    if (!quota.allows(sender)) return true
    platform.log.info("🔄 Handling ${if (public) "public " else ""}${mode.wire} question")

    if (public) broadcast("public.question", sender, message)
    if (configuration.ui.chatStatusEnabled) {
      lang.send(sender, if (mode == Run.Mode.ASK) "messages.thinking" else "messages.askx_thinking")
    }

    // A new question replaces the one still running.
    runs.remove(key(sender))?.cancel()
    val run = Run(platform.asker(sender, player?.let(locales::code)), mode, lang, configuration.ui.verbSeconds, public)
    run.tip = tip
    run.model = quota.model(sender)
    // Rights are read here, on the server thread; the request goes out from another.
    run.expansionTools = expansions?.offered(sender, mode, public, task = false).orEmpty()
    run.expansionNames = expansions?.names(run.expansionTools).orEmpty()
    runs[key(sender)] = run
    animator.start(run)

    val listener = object : Service.Listener {
      override fun toolStarted(callId: String, name: String, args: JsonObject?) = platform.scheduler.main { animator.toolStarted(run, sender, name, args) }
      override fun toolFinished(callId: String, summary: String?, failed: Boolean) = platform.scheduler.main { animator.toolFinished(run, sender, summary, failed) }
    }

    scope.launch {
      try {
        val outcome = withRetries(run, sender, lang) { service.chat(run, message, listener) }
        if (!run.cancelled) platform.scheduler.main { finish(run, sender, outcome) }
      } catch (e: Exception) {
        if (run.cancelled || e is CancellationException) return@launch
        platform.log.warning("❌ Error when requesting server: $e")
        platform.scheduler.main {
          player?.let {
            animator.stop(it)
            animator.error(it)
          }
          sender.send(Legacy.color(ErrorHandler.message(lang, e, billing().zone(sender))))
          billing().hint(sender, e)
          if (public) everyoneBut(sender).forEach { locales.of(it).send(it, "public.failed") }
        }
      } finally {
        runs.remove(key(sender), run)
        quota.spent(sender.uuid, run.cost)
      }
    }
    return true
  }

  private fun finish(run: Run, sender: Audience, outcome: Service.Outcome) {
    val player = sender as? PlayerAudience
    player?.let(animator::stop)

    if (run.mode == Run.Mode.ASK) {
      player?.let(animator::success)
      // The buttons sit in the answer where Allay wrote them; each reader gets their own ids. A book's buttons and an
      // agent's still come under it.
      val pieces = clicks().pieces(run, outcome.answer)
      if (run.public) {
        broadcastAnswer(sender, pieces, outcome.answer)
        everyoneBut(sender).forEach(animator::publicAnswer)
      } else if (player != null) {
        player.send(clicks().answer(player, run.lang.text("messages.answer", "text" to Legacy.SLOT), pieces, public = false, asker = player))
      } else {
        sender.send(labeled(run, InlineButtons.plain(outcome.answer)))
      }
      val buttons = run.buttons.toList()
      if (run.public) {
        platform.players.forEach { reader -> clicks().show(reader, buttons) }
      } else {
        player?.let { clicks().show(it, buttons) }
      }
      if (run.tip) player?.let { clicks().tip(it) }
      platform.log.info("✅ Question answered")
      return
    }

    val commands = outcome.commands?.commands.orEmpty().map { it.trim().removePrefix("/") }.filter { it.isNotEmpty() }
    if (commands.isEmpty()) {
      player?.let(animator::error)
      if (outcome.answer.isNotBlank()) sender.send(labeled(run, outcome.answer)) else run.lang.send(sender, "messages.no_commands")
      platform.log.info("❌ No commands to execute")
      return
    }
    player?.let(animator::success)
    if (outcome.answer.isNotBlank()) sender.send(labeled(run, outcome.answer))
    val sequential = outcome.commands?.is_sequence == true
    run.lang.send(sender, if (sequential) "messages.commands_in_order" else "messages.commands_at_once", "count" to commands.size)
    if (sequential) executor.executeCommandsSequentially(commands, sender) else executor.executeCommandsParallel(commands, sender)
    platform.log.info("✅ Askx command handled")
  }

  /**
   * A line every player online reads in their own language, and the console too. The question is the player's
   * own text, so its `&` stays as typed; the answer is Allay's and may use colors. `{papi:…}` is the asker's.
   */
  private fun broadcast(key: String, asker: Audience, text: String) {
    val readers = platform.players + platform.console
    readers.forEach { reader ->
      val template = locales.of(reader).about(platform.placeholders(asker)).text(key, "player" to asker.name, "text" to Legacy.SLOT)
      reader.send(Legacy.withText(template, text))
    }
  }

  /** The answer everyone reads, with its buttons, in each reader's language; the console reads the labels. */
  private fun broadcastAnswer(asker: Audience, pieces: List<Clicks.Piece>, answer: String) {
    platform.players.forEach { reader ->
      val template = locales.of(reader).about(platform.placeholders(asker)).text("public.answer", "player" to asker.name, "text" to Legacy.SLOT)
      reader.send(clicks().answer(reader, template, pieces, public = true, asker = asker as? PlayerAudience))
    }
    val console = platform.console
    val template = locales.of(console).about(platform.placeholders(asker)).text("public.answer", "player" to asker.name, "text" to Legacy.SLOT)
    console.send(Legacy.color(template.replace(Legacy.SLOT, InlineButtons.plain(answer))))
  }

  /** Allay's answer under its name, as public answers have it. The answer is Allay's own text, so its colors stay. */
  private fun labeled(run: Run, answer: String) =
    Legacy.color(run.lang.text("messages.answer", "text" to Legacy.SLOT).replace(Legacy.SLOT, answer))

  private fun everyoneBut(sender: Audience) = platform.players.filter { it.uuid != sender.uuid }

  private fun key(sender: Audience) = sender.uuid?.toString() ?: "console:${sender.name}"

  /** Runs [block] again while the backend is restarting or busy, telling the asker once; then the error goes through. */
  private suspend fun <T> withRetries(run: Run, sender: Audience, lang: Lang, block: suspend () -> T): T {
    var tries = 0
    while (true) {
      try {
        return block()
      } catch (e: Exception) {
        if (run.cancelled || e is CancellationException) throw e
        val seconds = Retry.after(e)?.takeIf { tries < Retry.TIMES } ?: throw e
        if (tries++ == 0) platform.scheduler.main { lang.send(sender, "messages.retrying", "seconds" to seconds) }
        platform.log.info("⏳ The backend couldn't take the question ($e); trying again in $seconds s")
        delay(seconds * 1000)
      }
    }
  }
}
