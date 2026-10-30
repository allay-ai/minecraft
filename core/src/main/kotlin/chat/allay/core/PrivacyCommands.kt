package chat.allay.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * `/allay privacy`: what Allay keeps about a player and for how long, and their data on request (since backend 6.0).
 * A player exports or erases their own; admins do it for anyone who asked them. An export is a link to the backend,
 * good for a day (6.9), shown only to whoever asked for it, never in the log. Before a player's first question, the
 * notice asks them to agree in the game's own window (a dialog), with buttons; the backend keeps the answer (since 6.1)
 * and the hash of the notice (6.9), so a player is asked again when the notice or the policy changes.
 */
class PrivacyCommands(
  private val platform: Platform,
  private val configuration: Config,
  private val locales: Locales,
  private val service: Service<*>,
  private val consents: Consents,
  private val settings: PlayerSettings,
  /** What the plugin holds about a player while they play; the backend keeps the rest. */
  private val data: PlayerData,
  /** Forgets what the platform's own pieces hold about a player in memory: their last death, their goals' boss bars. */
  private val forgetHere: (UUID) -> Unit,
  /** Who said they're too young to agree, sealed. */
  private val ages: Ages,
  private val scope: CoroutineScope,
  /** Stops the player's automation agents, which ask the backend on their own. */
  private val stopAgents: (PlayerAudience) -> Unit,
) {
  /** The question a player asked before agreeing; it goes on once they agree. [state] is why they were stopped. */
  private class Pending(val at: Long, val question: (() -> Unit)?, var state: Consents.State = Consents.State.MISSING)

  private val pending = ConcurrentHashMap<UUID, Pending>()

  /** Players who just said they're old enough to agree here, and when; it counts for the agreement that follows. */
  private val adults = ConcurrentHashMap<UUID, Long>()

  /**
   * Before a question from a player Allay hasn't seen agree: the backend says whether they did, and if not, the notice
   * asks them. [question] runs on the server thread once they agree, or at once when they already have.
   */
  fun ask(player: PlayerAudience, question: (() -> Unit)?) {
    val lang = locales.of(player)
    // Too young less than a year ago: no question to the backend, and none to the player.
    if (ages.blocked(player.uuid)) return lang.send(player, "consent.age_blocked")
    pending[player.uuid] = Pending(System.currentTimeMillis(), question)
    scope.launch {
      val state = try {
        consents.check(player.uuid)
      } catch (error: Exception) {
        pending.remove(player.uuid)
        platform.log.warning("Couldn't read a player's consent: ${(error as? ApiErrorException)?.apiError?.code ?: error.javaClass.simpleName}")
        onMain { player.send(Legacy.color(ErrorHandler.message(lang, error))) }
        return@launch
      }
      if (state == Consents.State.UNSUPPORTED) platform.log.warning("The backend can't keep players' consent (it's older than 6.1), so Allay asks nobody; update the backend")
      onMain {
        when (state) {
          Consents.State.MISSING, Consents.State.CHANGED, Consents.State.AGE -> {
            pending[player.uuid]?.state = state
            // The age first, unless the backend's agreement already has it, then the notice.
            if (needsAge(player.uuid)) ageQuestion(player) else next(player, state)
            reminder(player)
          }
          else -> pending.remove(player.uuid)?.question?.invoke() ?: lang.send(player, "consent.already")
        }
      }
    }
  }

  /**
   * What Allay does with a player's data, under which law, in a window with the buttons to agree or not. A player who
   * agreed to an older notice first reads that it changed, and when they agreed.
   */
  private fun notice(player: PlayerAudience, changed: Boolean) {
    val lang = locales.of(player)
    val paragraphs = buildList {
      if (changed) {
        val since = consents.previous(player.uuid)?.let { runCatching { java.time.OffsetDateTime.parse(it).toLocalDate().toString() }.getOrNull() }
        add(lang.text(if (since != null) "consent.changed" else "consent.changed_undated", "date" to since))
        add(lang.text("consent.changed_summary"))
      }
      add(lang.text("consent.notice"))
      // Whoever runs the server answers for Allay on it, named by the server's name in config.yml when it has one.
      val server = configuration.server.name.takeIf { it.isNotEmpty() }?.let { lang.text("consent.server_named", "name" to it) } ?: lang.text("consent.server_this")
      add(lang.text(if (configuration.server.token.isEmpty()) "consent.hosted" else "consent.self", "server" to server))
      add(lang.text("consent.keeps"))
    }
    val buttons = listOfNotNull(
      button(lang, "consent.agree", "consent.agree_hover", command = "/allay privacy agree"),
      button(lang, "consent.decline", "consent.decline_hover", command = "/allay privacy decline"),
      configuration.privacy.policyUrl.takeIf { it.isNotEmpty() }
        ?.let { button(lang, "consent.policy", "consent.policy_hover", url = it, values = arrayOf("url" to it), width = 2 * BUTTON_WIDTH + 4) },
    )
    show(player, lang.text("consent.title"), paragraphs, buttons, columns = 2)
  }

  /** After the age question, or without one: the notice, or for a player who only owed their age, the agreement itself. */
  private fun next(player: PlayerAudience, state: Consents.State) {
    if (state == Consents.State.AGE) agree(player) else notice(player, changed = state == Consents.State.CHANGED)
  }

  /**
   * “How old are you?” in a window with five neutral bands, and above them why Allay asks: the law lets only players
   * above an age, which depends on the country, agree to their data being used.
   */
  private fun ageQuestion(player: PlayerAudience) {
    val lang = locales.of(player)
    val bands = AgeGate.Band.entries.map { band ->
      val key = "consent.age_" + band.wire.replace('-', '_')
      button(lang, key, "consent.age_hover", command = "/allay privacy age ${band.wire}", width = AGE_WIDTH)
    }
    show(player, lang.text("consent.age_question"), listOf(lang.text("consent.age_why")), bands, columns = 1)
  }

  /** The answer closes the window or opens the next one; Escape closes it, and the question waits for [PENDING_MS]. */
  private fun show(player: PlayerAudience, title: String, paragraphs: List<String>, buttons: List<Window.Button>, columns: Int) =
    player.show(Window(title, paragraphs, buttons, columns, Window.BODY, links = true))

  /** One line in chat under the window, to open it again after Escape. */
  private fun reminder(player: PlayerAudience) {
    val lang = locales.of(player)
    val button = Line.Button(Legacy.color(lang.text("consent.reopen_button")), Legacy.color(lang.text("consent.reopen_hover")), Line.Click.Run("/allay privacy review"))
    player.send(Line(Line.Text(Legacy.color(lang.text("consent.reopen"))), Line.SPACE, button))
  }

  /** `/allay privacy review`, from the line under the window: the window again, where the player left it. */
  fun review(sender: Audience) {
    val player = sender as? PlayerAudience ?: return locales.of(sender).send(sender, "messages.players_only")
    if (ages.blocked(player.uuid)) return locales.of(sender).send(sender, "consent.age_blocked")
    val waiting = pending[player.uuid]?.takeIf { System.currentTimeMillis() - it.at < PENDING_MS }
      ?: return ask(player, null)
    if (needsAge(player.uuid)) ageQuestion(player) else next(player, waiting.state)
  }

  /**
   * `/allay privacy age <band>`, from the age question's buttons. Old enough for the player's country goes on to the
   * notice; too young keeps nothing but a sealed note that they said so, so the question waits a year.
   */
  fun age(sender: Audience, answer: String) {
    val player = sender as? PlayerAudience ?: return locales.of(sender).send(sender, "messages.players_only")
    val band = AgeGate.Band.parse(answer) ?: return locales.of(sender).send(sender, "privacy.usage")
    if (AgeGate.allows(band, locales.country(player.uuid))) {
      player.closeWindow()
      adults[player.uuid] = System.currentTimeMillis()
      next(player, pending[player.uuid]?.state ?: Consents.State.MISSING)
      return
    }
    player.closeWindow()
    adults.remove(player.uuid)
    pending.remove(player.uuid)
    consents.declined(player.uuid)
    ages.block(player.uuid)
    locales.of(sender).send(sender, "consent.age_under")
  }

  /**
   * Whether the player still has to say their age: only where the law sets an age of consent, and not just now or on
   * their agreement with the backend.
   */
  private fun needsAge(player: UUID) = AgeGate.asks(locales.country(player)) && !adult(player) && !consents.ageKnown(player)

  private fun adult(player: UUID) = adults[player]?.let { System.currentTimeMillis() - it < PENDING_MS } == true

  private fun button(lang: Lang, text: String, hover: String, command: String? = null, url: String? = null, values: Array<Pair<String, Any>> = emptyArray(), width: Int = BUTTON_WIDTH) =
    Window.Button(Legacy.color(lang.text(text)), Legacy.color(lang.text(hover, *values)), width, command, url)

  /**
   * `/allay privacy agree`: the backend records it, and the question that brought up the notice goes on. A player who
   * hasn't said their age gets the age question first.
   */
  fun agree(sender: Audience) {
    val player = sender as? PlayerAudience ?: return locales.of(sender).send(sender, "messages.players_only")
    if (ages.blocked(player.uuid)) return locales.of(sender).send(sender, "consent.age_blocked")
    if (needsAge(player.uuid)) return ageQuestion(player)
    player.closeWindow()
    var question: (() -> Unit)? = null
    request(sender, after = { question?.invoke() }) { lang ->
      consents.give(player.uuid, ageConfirmed = adults.remove(player.uuid) != null || consents.ageKnown(player.uuid))
      // What the player set before agreeing goes to the backend now, and what it kept comes back.
      data.consented(player.uuid)
      question = pending.remove(player.uuid)?.takeIf { System.currentTimeMillis() - it.at < PENDING_MS }?.question
      lang.text(if (question != null) "consent.given" else "consent.given_idle")
    }
  }

  /** `/allay privacy decline`: nothing goes to the backend, and the question is dropped. */
  fun decline(sender: Audience) {
    val player = sender as? PlayerAudience ?: return locales.of(sender).send(sender, "messages.players_only")
    player.closeWindow()
    pending.remove(player.uuid)
    adults.remove(player.uuid)
    consents.declined(player.uuid)
    locales.of(sender).send(sender, "consent.declined")
  }

  /**
   * `/allay privacy withdraw`: Allay stops answering, the player's agents stop, and what the agreement covered goes: the
   * backend erases memories, places, deaths, settings, and the conversation (6.9), and nothing of theirs stays here.
   */
  fun withdraw(sender: Audience) {
    val player = sender as? PlayerAudience ?: return locales.of(sender).send(sender, "messages.players_only")
    player.closeWindow()
    pending.remove(player.uuid)
    forget(player.uuid)
    request(sender, after = { stopAgents(player) }) { lang ->
      lang.text(if (consents.withdraw(player.uuid)) "consent.withdrawn" else "consent.not_given")
    }
  }

  /**
   * `/allay privacy full [on|off]`: the backend's admin panel knows the player by pseudonym only, without their name,
   * UUID, or face. The switch lives here and goes with every question; the backend hears it at once only from a player
   * who agreed to the notice, since nothing about anyone else reaches it.
   */
  fun full(sender: Audience, word: String?) {
    val lang = locales.of(sender)
    val player = sender as? PlayerAudience ?: return lang.send(sender, "messages.players_only")
    val now = settings.of(player.uuid).full_privacy
    val on = when (word?.lowercase()) {
      null -> return lang.send(player, if (now) "privacy.full_is_on" else "privacy.full_is_off")
      "on" -> true
      "off" -> false
      "toggle" -> !now
      else -> return lang.send(player, "privacy.usage")
    }
    setFull(player, on)
  }

  /** Flips the switch and tells the backend; the settings window calls it too. */
  fun setFull(player: PlayerAudience, on: Boolean) {
    settings.update(player.uuid) { it.copy(full_privacy = on) }
    request(player) { lang ->
      if (consents.known(player.uuid) || consents.check(player.uuid) == Consents.State.GIVEN) {
        try {
          service.privacy(player.uuid.toString(), on)
        } catch (error: ApiErrorException) {
          // A backend before 6.5 has no switch: it shows the name either way, and the player should know.
          if (error.apiError.statusCode in setOf(404, 405)) return@request lang.text("privacy.full_unsupported") else throw error
        }
      }
      lang.text(if (on) "privacy.full_on" else "privacy.full_off")
    }
  }

  /** The switch in the privacy window: flips it and shows the window again, without a line in chat. */
  fun toggleFull(player: PlayerAudience) {
    val on = !settings.of(player.uuid).full_privacy
    settings.update(player.uuid) { it.copy(full_privacy = on) }
    scope.launch {
      try {
        if (consents.known(player.uuid) || consents.check(player.uuid) == Consents.State.GIVEN) service.privacy(player.uuid.toString(), on)
      } catch (error: Exception) {
        platform.log.warning("Couldn't send full privacy to the backend: ${error.javaClass.simpleName}")
      }
      onMain { info(player) }
    }
  }

  /**
   * `/allay privacy`: a player's data in a window: what Allay keeps, full privacy as a switch, and buttons to download,
   * withdraw, or erase it all. The console reads it in lines.
   */
  fun info(sender: Audience) {
    val player = sender as? PlayerAudience ?: return infoLines(sender)
    val lang = locales.of(player)
    val full = settings.of(player.uuid).full_privacy
    val paragraphs = buildList {
      addAll(lang.text("privacy.info").split('\n'))
      add(lang.text(if (full) "privacy.window.full_is_on" else "privacy.window.full_is_off"))
      if (player.can(Permissions.ADMIN)) add(lang.text("privacy.admin"))
    }
    val buttons = listOf(
      Window.button(lang, if (full) "privacy.window.full_off" else "privacy.window.full_on", "privacy.window.full_hover", "/allay privacy full toggle"),
      Window.button(lang, "privacy.window.export", "privacy.window.export_hover", "/allay privacy export"),
      Window.button(lang, "privacy.window.withdraw", "privacy.window.withdraw_hover", "/allay privacy withdraw"),
      Window.button(lang, "privacy.window.erase", "privacy.erase_hover", "/allay privacy erase"),
    )
    player.show(Window(lang.text("privacy.window.title"), paragraphs, buttons, 1, Window.BODY, exit = Window.button(lang, "windows.back", "windows.back_hover", "/allay")))
  }

  /** `/allay privacy withdraw` from a player: it erases what the agreement covered, so the window asks once more. */
  fun confirmWithdraw(sender: Audience) {
    val player = sender as? PlayerAudience ?: return withdraw(sender)
    val lang = locales.of(player)
    player.show(Window(
      lang.text("privacy.window.withdraw_title"), listOf(lang.text("privacy.window.withdraw_about")),
      listOf(Window.button(lang, "privacy.window.withdraw_yes", "privacy.window.withdraw_hover", "/allay privacy withdraw $CONFIRM")),
      1, Window.BODY, exit = Window.button(lang, "windows.back", "windows.back_hover", "/allay privacy"),
    ))
  }

  private fun infoLines(sender: Audience) {
    val lang = locales.of(sender)
    lang.send(sender, "privacy.info")
    (sender as? PlayerAudience)?.let { lang.send(it, if (settings.of(it.uuid).full_privacy) "privacy.full_is_on" else "privacy.full_is_off") }
    lang.send(sender, "privacy.commands")
    if (sender.can(Permissions.ADMIN)) lang.send(sender, "privacy.admin")
  }

  /** `/allay privacy erase`: asks once more in a window, since nothing brings the data back. */
  fun confirmSelf(sender: Audience) {
    val lang = locales.of(sender)
    val player = sender as? PlayerAudience ?: return lang.send(sender, "privacy.admin")
    player.show(Window(
      lang.text("privacy.window.erase_title"), listOf(lang.text("privacy.erase_confirm")),
      listOf(Window.button(lang, "privacy.window.erase_yes", "privacy.erase_hover", "/allay privacy erase $CONFIRM")),
      1, Window.BODY, exit = Window.button(lang, "windows.back", "windows.back_hover", "/allay privacy"),
    ))
  }

  fun eraseSelf(sender: Audience) {
    val player = sender as? PlayerAudience ?: return locales.of(sender).send(sender, "messages.players_only")
    player.closeWindow()
    // The agents first: a wake still out would keep something about the player after the erasure.
    stopAgents(player)
    forget(player.uuid)
    request(sender) { lang ->
      val erased = service.erase(player.uuid.toString())
      consents.forget(player.uuid)
      // Without the name: the log shouldn't keep who asked to be forgotten.
      platform.log.info("A player erased what Allay kept about them")
      lang.text("privacy.erased_self", "memories" to erased.memories)
    }
  }

  /** `/allay privacy export`: a link to everything kept about the player, for them alone, in chat, where it can be copied. */
  fun exportSelf(sender: Audience) {
    val player = sender as? PlayerAudience ?: return locales.of(sender).send(sender, "privacy.admin")
    player.closeWindow()
    link(sender, player.uuid, null)
  }

  /** `/allay privacy export <player|uuid>`: the same link for an admin to hand to a player who asked. */
  fun export(sender: Audience, who: String) {
    val target = playerOf(who) ?: return locales.of(sender).send(sender, "privacy.unknown_player", "player" to who)
    link(sender, target.first, target.second ?: who)
    platform.log.info("${sender.name} asked for a link to what Allay keeps about a player")
  }

  /**
   * Asks the backend for the link and shows it, clickable, to [sender] alone: whoever holds it can download the data
   * for 24 hours. The console gets it as text, since the console asked.
   */
  private fun link(sender: Audience, uuid: UUID, name: String?) {
    val lang = locales.of(sender)
    scope.launch {
      val line: () -> Unit = try {
        val link = service.exportLink(uuid.toString())
        val until = link.expires_at?.let { runCatching { java.time.OffsetDateTime.parse(it).atZoneSameInstant(java.time.ZoneId.systemDefault()).format(UNTIL) }.getOrNull() } ?: "24 h"
        val intro = if (name == null) lang.text("privacy.export_ready", "until" to until) else lang.text("privacy.export_ready_for", "player" to name, "until" to until)
        ({
          if (sender is PlayerAudience) {
            val button = Line.Button(Legacy.color(lang.text("privacy.export_button")), Legacy.color(lang.text("privacy.export_hover")), Line.Click.Open(link.url))
            sender.send(Line(Line.Text(Legacy.color(intro)), Line.SPACE, button))
          } else sender.send(Legacy.color(intro) + " " + link.url)
        })
      } catch (error: ApiErrorException) {
        // A backend before 6.9 has no links.
        val text = if (error.apiError.statusCode in setOf(404, 405)) lang.text("privacy.export_unsupported") else ErrorHandler.message(lang, error.apiError)
        ({ sender.send(Legacy.color(text)) })
      } catch (error: Exception) {
        platform.log.warning("Privacy request failed: ${error.javaClass.simpleName}")
        val text = ErrorHandler.message(lang, error)
        ({ sender.send(Legacy.color(text)) })
      }
      onMain(line)
    }
  }

  /** `/allay privacy erase <player|uuid>`: for a player who asked an admin to be forgotten. */
  fun erase(sender: Audience, who: String) {
    val target = playerOf(who) ?: return locales.of(sender).send(sender, "privacy.unknown_player", "player" to who)
    // A player offline has no agents: they end on quit.
    platform.player(target.first)?.let(stopAgents)
    forget(target.first)
    request(sender) { lang ->
      val erased = service.erase(target.first.toString())
      consents.forget(target.first)
      platform.log.info("${sender.name} erased what Allay kept about a player")
      lang.text("privacy.erased", "player" to (target.second ?: who), "memories" to erased.memories, "runs" to erased.runs)
    }
  }

  /**
   * What the plugin holds in memory goes on the server thread, where boss bars live, before the backend's copy, so
   * nothing waits here to be sent again. Settings, time zone, goals, and the last death start over.
   */
  private fun forget(uuid: UUID) {
    forgetHere(uuid)
    data.forget(uuid)
  }

  /** A UUID as typed, or a player this server has seen by that name; never one it hasn't, to keep typos from erasing a stranger. */
  private fun playerOf(who: String): Pair<UUID, String?>? = platform.known(who)

  /** Runs [block] off the server thread; it returns the line to show, colored on the way. [after] runs next, on the server thread. */
  private fun request(sender: Audience, after: () -> Unit = {}, block: suspend (Lang) -> String) {
    val lang = locales.of(sender)
    scope.launch {
      val line = try {
        block(lang)
      } catch (error: ApiErrorException) {
        // A backend before 6.0 has no such route.
        if (error.apiError.statusCode in setOf(404, 405)) lang.text("privacy.unsupported") else ErrorHandler.message(lang, error.apiError)
      } catch (error: Exception) {
        platform.log.warning("Privacy request failed: ${error.javaClass.simpleName}")
        ErrorHandler.message(lang, error)
      }
      onMain {
        sender.send(Legacy.color(line))
        after()
      }
    }
  }

  private fun onMain(block: () -> Unit) = platform.scheduler.main(block)

  companion object {
    /** The word the confirmation button adds, so a typed `/allay privacy erase` never erases at once. */
    const val CONFIRM = "confirm"
    private val UNTIL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    /** A question older than this doesn't go on when its asker agrees: they've moved on. */
    private const val PENDING_MS = 10 * 60_000L
    /** Widths in the window, in pixels: the notice's buttons and the age bands. */
    private const val BUTTON_WIDTH = 150
    private const val AGE_WIDTH = 200
  }
}
