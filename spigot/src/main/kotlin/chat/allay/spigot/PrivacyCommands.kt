package chat.allay.spigot

import chat.allay.core.*
import chat.allay.core.tools.*

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.HoverEvent
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.chat.hover.content.Text
import org.bukkit.OfflinePlayer
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import chat.allay.spigot.Minecraft.formatMessage
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
  private val plugin: JavaPlugin,
  private val configuration: Config,
  private val locales: Locales,
  private val service: Service<*>,
  private val consents: Consents,
  private val settings: PlayerSettings,
  /** What the plugin holds about a player while they play; the backend keeps the rest. */
  private val data: PlayerData,
  private val deaths: chat.allay.spigot.tools.Deaths,
  private val goals: chat.allay.spigot.tools.Goals,
  /** Who said they're too young to agree, sealed. */
  private val ages: Ages,
  private val scope: CoroutineScope,
  /** Stops the player's automation agents, which ask the backend on their own. */
  private val stopAgents: (Player) -> Unit,
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
  fun ask(player: Player, question: (() -> Unit)?) {
    val lang = locales.of(player)
    // Too young less than a year ago: no question to the backend, and none to the player.
    if (ages.blocked(player.uniqueId)) return lang.send(player, "consent.age_blocked")
    pending[player.uniqueId] = Pending(System.currentTimeMillis(), question)
    scope.launch {
      val state = try {
        consents.check(player.uniqueId)
      } catch (error: Exception) {
        pending.remove(player.uniqueId)
        plugin.logger.warning("Couldn't read a player's consent: ${(error as? ApiErrorException)?.apiError?.code ?: error.javaClass.simpleName}")
        onMain { player.sendMessage(formatMessage(ErrorHandler.message(lang, error))) }
        return@launch
      }
      if (state == Consents.State.UNSUPPORTED) plugin.logger.warning("The backend can't keep players' consent (it's older than 6.1), so Allay asks nobody; update the backend")
      onMain {
        when (state) {
          Consents.State.MISSING, Consents.State.CHANGED, Consents.State.AGE -> {
            pending[player.uniqueId]?.state = state
            // The age first, unless the backend's agreement already has it, then the notice.
            if (needsAge(player.uniqueId)) ageQuestion(player) else next(player, state)
            reminder(player)
          }
          else -> pending.remove(player.uniqueId)?.question?.invoke() ?: lang.send(player, "consent.already")
        }
      }
    }
  }

  /**
   * What Allay does with a player's data, under which law, in a window with the buttons to agree or not. A player who
   * agreed to an older notice first reads that it changed, and when they agreed.
   */
  private fun notice(player: Player, changed: Boolean) {
    val lang = locales.of(player)
    val paragraphs = buildList {
      if (changed) {
        val since = consents.previous(player.uniqueId)?.let { runCatching { java.time.OffsetDateTime.parse(it).toLocalDate().toString() }.getOrNull() }
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
  private fun next(player: Player, state: Consents.State) {
    if (state == Consents.State.AGE) agree(player) else notice(player, changed = state == Consents.State.CHANGED)
  }

  /**
   * “How old are you?” in a window with five neutral bands, and above them why Allay asks: the law lets only players
   * above an age, which depends on the country, agree to their data being used.
   */
  private fun ageQuestion(player: Player) {
    val lang = locales.of(player)
    val bands = AgeGate.Band.entries.map { band ->
      val key = "consent.age_" + band.wire.replace('-', '_')
      button(lang, key, "consent.age_hover", command = "/allay privacy age ${band.wire}", width = AGE_WIDTH)
    }
    show(player, lang.text("consent.age_question"), listOf(lang.text("consent.age_why")), bands, columns = 1)
  }

  /**
   * The answer closes the window or opens the next one; Escape closes it, and the question waits for [PENDING_MS]. Gray
   * reads poorly over the blurred game, so the window's text is white and what chat shows in white turns yellow.
   */
  private fun show(player: Player, title: String, paragraphs: List<String>, buttons: List<Window.Button>, columns: Int) =
    Windows.show(player, Window(formatMessage(title), paragraphs.map { brighter(formatMessage(it)) }, buttons, columns, BODY_WIDTH))

  private fun brighter(text: String) = text.replace("§f", "§e").replace("§7", "§f")

  /** One line in chat under the window, to open it again after Escape. */
  private fun reminder(player: Player) {
    val lang = locales.of(player)
    val button = TextComponent(*plain(formatMessage(lang.text("consent.reopen_button")))).apply {
      clickEvent = ClickEvent(ClickEvent.Action.RUN_COMMAND, "/allay privacy review")
      hoverEvent = HoverEvent(HoverEvent.Action.SHOW_TEXT, Text(plain(formatMessage(lang.text("consent.reopen_hover")))))
    }
    player.spigot().sendMessage(*plain(formatMessage(lang.text("consent.reopen"))), TextComponent(" "), button)
  }

  /** `/allay privacy review`, from the line under the window: the window again, where the player left it. */
  fun review(sender: CommandSender) {
    val player = sender as? Player ?: return locales.of(sender).send(sender, "messages.players_only")
    if (ages.blocked(player.uniqueId)) return locales.of(sender).send(sender, "consent.age_blocked")
    val waiting = pending[player.uniqueId]?.takeIf { System.currentTimeMillis() - it.at < PENDING_MS }
      ?: return ask(player, null)
    if (needsAge(player.uniqueId)) ageQuestion(player) else next(player, waiting.state)
  }

  /**
   * `/allay privacy age <band>`, from the age question's buttons. Old enough for the player's country goes on to the
   * notice; too young keeps nothing but a sealed note that they said so, so the question waits a year.
   */
  fun age(sender: CommandSender, answer: String) {
    val player = sender as? Player ?: return locales.of(sender).send(sender, "messages.players_only")
    val band = AgeGate.Band.parse(answer) ?: return locales.of(sender).send(sender, "privacy.usage")
    if (AgeGate.allows(band, locales.country(player.uniqueId))) {
      Windows.close(player)
      adults[player.uniqueId] = System.currentTimeMillis()
      next(player, pending[player.uniqueId]?.state ?: Consents.State.MISSING)
      return
    }
    Windows.close(player)
    adults.remove(player.uniqueId)
    pending.remove(player.uniqueId)
    consents.declined(player.uniqueId)
    ages.block(player.uniqueId)
    locales.of(sender).send(sender, "consent.age_under")
  }

  /**
   * Whether the player still has to say their age: only where the law sets an age of consent, and not just now or on
   * their agreement with the backend.
   */
  private fun needsAge(player: UUID) = AgeGate.asks(locales.country(player)) && !adult(player) && !consents.ageKnown(player)

  private fun adult(player: UUID) = adults[player]?.let { System.currentTimeMillis() - it < PENDING_MS } == true

  private fun button(lang: Lang, text: String, hover: String, command: String? = null, url: String? = null, values: Array<Pair<String, Any>> = emptyArray(), width: Int = BUTTON_WIDTH) =
    Window.Button(formatMessage(lang.text(text)), formatMessage(lang.text(hover, *values)), width, command, url)

  /**
   * `/allay privacy agree`: the backend records it, and the question that brought up the notice goes on. A player who
   * hasn't said their age gets the age question first.
   */
  fun agree(sender: CommandSender) {
    val player = sender as? Player ?: return locales.of(sender).send(sender, "messages.players_only")
    if (ages.blocked(player.uniqueId)) return locales.of(sender).send(sender, "consent.age_blocked")
    if (needsAge(player.uniqueId)) return ageQuestion(player)
    Windows.close(player)
    var question: (() -> Unit)? = null
    request(sender, after = { question?.invoke() }) { lang ->
      consents.give(player.uniqueId, ageConfirmed = adults.remove(player.uniqueId) != null || consents.ageKnown(player.uniqueId))
      // What the player set before agreeing goes to the backend now, and what it kept comes back.
      data.consented(player.uniqueId)
      question = pending.remove(player.uniqueId)?.takeIf { System.currentTimeMillis() - it.at < PENDING_MS }?.question
      lang.text(if (question != null) "consent.given" else "consent.given_idle")
    }
  }

  /** `/allay privacy decline`: nothing goes to the backend, and the question is dropped. */
  fun decline(sender: CommandSender) {
    val player = sender as? Player ?: return locales.of(sender).send(sender, "messages.players_only")
    Windows.close(player)
    pending.remove(player.uniqueId)
    adults.remove(player.uniqueId)
    consents.declined(player.uniqueId)
    locales.of(sender).send(sender, "consent.declined")
  }

  /**
   * `/allay privacy withdraw`: Allay stops answering, the player's agents stop, and what the agreement covered goes: the
   * backend erases memories, places, deaths, settings, and the conversation (6.9), and nothing of theirs stays here.
   */
  fun withdraw(sender: CommandSender) {
    val player = sender as? Player ?: return locales.of(sender).send(sender, "messages.players_only")
    pending.remove(player.uniqueId)
    forgetHere(player.uniqueId)
    request(sender, after = { stopAgents(player) }) { lang ->
      lang.text(if (consents.withdraw(player.uniqueId)) "consent.withdrawn" else "consent.not_given")
    }
  }

  /**
   * `/allay privacy full [on|off]`: the backend's admin panel knows the player by pseudonym only, without their name,
   * UUID, or face. The switch lives here and goes with every question; the backend hears it at once only from a player
   * who agreed to the notice, since nothing about anyone else reaches it.
   */
  fun full(sender: CommandSender, word: String?) {
    val lang = locales.of(sender)
    val player = sender as? Player ?: return lang.send(sender, "messages.players_only")
    val now = settings.of(player.uniqueId).full_privacy
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
  fun setFull(player: Player, on: Boolean) {
    settings.update(player.uniqueId) { it.copy(full_privacy = on) }
    request(player) { lang ->
      if (consents.known(player.uniqueId) || consents.check(player.uniqueId) == Consents.State.GIVEN) {
        try {
          service.privacy(player.uniqueId.toString(), on)
        } catch (error: ApiErrorException) {
          // A backend before 6.5 has no switch: it shows the name either way, and the player should know.
          if (error.apiError.statusCode in setOf(404, 405)) return@request lang.text("privacy.full_unsupported") else throw error
        }
      }
      lang.text(if (on) "privacy.full_on" else "privacy.full_off")
    }
  }

  fun info(sender: CommandSender) {
    val lang = locales.of(sender)
    lang.send(sender, "privacy.info")
    (sender as? Player)?.let { lang.send(it, if (settings.of(it.uniqueId).full_privacy) "privacy.full_is_on" else "privacy.full_is_off") }
    lang.send(sender, "privacy.commands")
    if (sender.hasPermission(Permissions.ADMIN)) lang.send(sender, "privacy.admin")
  }

  /** `/allay privacy erase`: asks once more, with a button, since nothing brings the data back. */
  fun confirmSelf(sender: CommandSender) {
    val lang = locales.of(sender)
    val player = sender as? Player ?: return lang.send(sender, "privacy.admin")
    val button = TextComponent(*plain(formatMessage(lang.text("privacy.erase_button")))).apply {
      clickEvent = ClickEvent(ClickEvent.Action.RUN_COMMAND, "/allay privacy erase $CONFIRM")
      hoverEvent = HoverEvent(HoverEvent.Action.SHOW_TEXT, Text(plain(formatMessage(lang.text("privacy.erase_hover")))))
    }
    player.spigot().sendMessage(*plain(formatMessage(lang.text("privacy.erase_confirm"))), TextComponent(" "), button)
  }

  fun eraseSelf(sender: CommandSender) {
    val player = sender as? Player ?: return locales.of(sender).send(sender, "messages.players_only")
    forgetHere(player.uniqueId)
    request(sender) { lang ->
      val erased = service.erase(player.uniqueId.toString())
      consents.forget(player.uniqueId)
      // Without the name: the log shouldn't keep who asked to be forgotten.
      plugin.logger.info("A player erased what Allay kept about them")
      lang.text("privacy.erased_self", "memories" to erased.memories)
    }
  }

  /** `/allay privacy export`: a link to everything kept about the player, for them alone. */
  fun exportSelf(sender: CommandSender) {
    val player = sender as? Player ?: return locales.of(sender).send(sender, "privacy.admin")
    link(sender, player.uniqueId, null)
  }

  /** `/allay privacy export <player|uuid>`: the same link for an admin to hand to a player who asked. */
  fun export(sender: CommandSender, who: String) {
    val target = playerOf(who) ?: return locales.of(sender).send(sender, "privacy.unknown_player", "player" to who)
    link(sender, target.uniqueId, target.name ?: who)
    plugin.logger.info("${sender.name} asked for a link to what Allay keeps about a player")
  }

  /**
   * Asks the backend for the link and shows it, clickable, to [sender] alone: whoever holds it can download the data
   * for 24 hours. The console gets it as text, since the console asked.
   */
  private fun link(sender: CommandSender, uuid: UUID, name: String?) {
    val lang = locales.of(sender)
    scope.launch {
      val line: () -> Unit = try {
        val link = service.exportLink(uuid.toString())
        val until = link.expires_at?.let { runCatching { java.time.OffsetDateTime.parse(it).atZoneSameInstant(java.time.ZoneId.systemDefault()).format(UNTIL) }.getOrNull() } ?: "24 h"
        val intro = if (name == null) lang.text("privacy.export_ready", "until" to until) else lang.text("privacy.export_ready_for", "player" to name, "until" to until)
        ({
          if (sender is Player) {
            val button = TextComponent(*plain(formatMessage(lang.text("privacy.export_button")))).apply {
              clickEvent = ClickEvent(ClickEvent.Action.OPEN_URL, link.url)
              hoverEvent = HoverEvent(HoverEvent.Action.SHOW_TEXT, Text(plain(formatMessage(lang.text("privacy.export_hover")))))
            }
            sender.spigot().sendMessage(*plain(formatMessage(intro)), TextComponent(" "), button)
          } else sender.sendMessage(formatMessage(intro) + " " + link.url)
        })
      } catch (error: ApiErrorException) {
        // A backend before 6.9 has no links.
        val text = if (error.apiError.statusCode in setOf(404, 405)) lang.text("privacy.export_unsupported") else ErrorHandler.message(lang, error.apiError)
        ({ sender.sendMessage(formatMessage(text)) })
      } catch (error: Exception) {
        plugin.logger.warning("Privacy request failed: ${error.javaClass.simpleName}")
        val text = ErrorHandler.message(lang, error)
        ({ sender.sendMessage(formatMessage(text)) })
      }
      onMain(line)
    }
  }

  /** `/allay privacy erase <player|uuid>`: for a player who asked an admin to be forgotten. */
  fun erase(sender: CommandSender, who: String) {
    val target = playerOf(who) ?: return locales.of(sender).send(sender, "privacy.unknown_player", "player" to who)
    forgetHere(target.uniqueId)
    request(sender) { lang ->
      val erased = service.erase(target.uniqueId.toString())
      consents.forget(target.uniqueId)
      plugin.logger.info("${sender.name} erased what Allay kept about a player")
      lang.text("privacy.erased", "player" to (target.name ?: who), "memories" to erased.memories, "runs" to erased.runs)
    }
  }

  /**
   * What the plugin holds in memory goes on the server thread, where boss bars live, before the backend's copy, so
   * nothing waits here to be sent again. Settings, time zone, goals, and the last death start over.
   */
  private fun forgetHere(uuid: UUID) {
    deaths.forget(uuid)
    goals.forget(uuid)
    data.forget(uuid)
  }

  /** A UUID as typed, or a player this server has seen by that name; never one it hasn't, to keep typos from erasing a stranger. */
  @Suppress("DEPRECATION")
  private fun playerOf(who: String): OfflinePlayer? {
    runCatching { UUID.fromString(who) }.getOrNull()?.let { return plugin.server.getOfflinePlayer(it) }
    plugin.server.getPlayerExact(who)?.let { return it }
    return plugin.server.offlinePlayers.firstOrNull { it.name.equals(who, ignoreCase = true) }
  }

  /** Runs [block] off the server thread; it returns the line to show, colored on the way. [after] runs next, on the server thread. */
  private fun request(sender: CommandSender, after: () -> Unit = {}, block: suspend (Lang) -> String) {
    val lang = locales.of(sender)
    scope.launch {
      val line = try {
        block(lang)
      } catch (error: ApiErrorException) {
        // A backend before 6.0 has no such route.
        if (error.apiError.statusCode in setOf(404, 405)) lang.text("privacy.unsupported") else ErrorHandler.message(lang, error.apiError)
      } catch (error: Exception) {
        plugin.logger.warning("Privacy request failed: ${error.javaClass.simpleName}")
        ErrorHandler.message(lang, error)
      }
      onMain {
        sender.sendMessage(formatMessage(line))
        after()
      }
    }
  }

  private fun onMain(block: () -> Unit) {
    if (plugin.isEnabled) plugin.server.scheduler.runTask(plugin, Runnable(block))
  }

  companion object {
    /** The word the confirmation button adds, so a typed `/allay privacy erase` never erases at once. */
    const val CONFIRM = "confirm"
    private val UNTIL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    /** A question older than this doesn't go on when its asker agrees: they've moved on. */
    private const val PENDING_MS = 10 * 60_000L
    /** Widths in the window, in pixels: the text, the notice's buttons, and the age bands. */
    private const val BODY_WIDTH = 400
    private const val BUTTON_WIDTH = 150
    private const val AGE_WIDTH = 200
  }
}
