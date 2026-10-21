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
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * `/allay privacy`: what Allay keeps about a player and for how long, and their data on request (since backend 6.0).
 * A player erases their own; admins export or erase anyone's, for a player who asked them. An export is personal data,
 * so it goes into an owner-only file, never into chat or the log. Before a player's first question, the notice asks
 * them to agree, with buttons; the backend keeps the answer (since 6.1).
 */
class PrivacyCommands(
  private val plugin: JavaPlugin,
  private val configuration: Config,
  private val locales: Locales,
  private val service: Service<*>,
  private val consents: Consents,
  private val settings: PlayerSettings,
  private val scope: CoroutineScope,
  /** Stops the player's automation agents, which ask the backend on their own. */
  private val stopAgents: (Player) -> Unit,
) {
  /** The question a player asked before agreeing; it goes on once they agree. */
  private class Pending(val at: Long, val question: () -> Unit)

  private val pending = ConcurrentHashMap<UUID, Pending>()

  /**
   * Before a question from a player Allay hasn't seen agree: the backend says whether they did, and if not, the notice
   * asks them. [question] runs on the server thread once they agree, or at once when they already have.
   */
  fun ask(player: Player, question: () -> Unit) {
    val lang = locales.of(player)
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
        if (state == Consents.State.MISSING) notice(player) else pending.remove(player.uniqueId)?.question?.invoke()
      }
    }
  }

  /** What Allay does with a player's data, under which law, and the buttons to agree or not. */
  private fun notice(player: Player) {
    val lang = locales.of(player)
    lang.send(player, "consent.notice")
    lang.send(player, if (configuration.server.token.isEmpty()) "consent.hosted" else "consent.self")
    lang.send(player, "consent.keeps")
    val agree = button(lang, "consent.agree", "consent.agree_hover", ClickEvent(ClickEvent.Action.RUN_COMMAND, "/allay privacy agree"))
    val decline = button(lang, "consent.decline", "consent.decline_hover", ClickEvent(ClickEvent.Action.RUN_COMMAND, "/allay privacy decline"))
    val policy = configuration.privacy.policyUrl.takeIf { it.isNotEmpty() }
      ?.let { listOf(TextComponent(" "), button(lang, "consent.policy", "consent.policy_hover", ClickEvent(ClickEvent.Action.OPEN_URL, it), "url" to it)) }
      .orEmpty()
    player.spigot().sendMessage(*plain(formatMessage(lang.text("consent.question"))), TextComponent(" "), agree, TextComponent(" "), decline, *policy.toTypedArray())
  }

  private fun button(lang: Lang, text: String, hover: String, click: ClickEvent, vararg values: Pair<String, Any>) =
    TextComponent(*plain(formatMessage(lang.text(text)))).apply {
      clickEvent = click
      hoverEvent = HoverEvent(HoverEvent.Action.SHOW_TEXT, Text(plain(formatMessage(lang.text(hover, *values)))))
    }

  /** `/allay privacy agree`: the backend records it, and the question that brought up the notice goes on. */
  fun agree(sender: CommandSender) {
    val player = sender as? Player ?: return locales.of(sender).send(sender, "messages.players_only")
    var question: (() -> Unit)? = null
    request(sender, after = { question?.invoke() }) { lang ->
      consents.give(player.uniqueId)
      question = pending.remove(player.uniqueId)?.takeIf { System.currentTimeMillis() - it.at < PENDING_MS }?.question
      lang.text(if (question != null) "consent.given" else "consent.given_idle")
    }
  }

  /** `/allay privacy decline`: nothing goes to the backend, and the question is dropped. */
  fun decline(sender: CommandSender) {
    val player = sender as? Player ?: return locales.of(sender).send(sender, "messages.players_only")
    pending.remove(player.uniqueId)
    locales.of(sender).send(sender, "consent.declined")
  }

  /** `/allay privacy withdraw`: Allay stops answering and the player's agents stop; what's kept stays until erased. */
  fun withdraw(sender: CommandSender) {
    val player = sender as? Player ?: return locales.of(sender).send(sender, "messages.players_only")
    pending.remove(player.uniqueId)
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
    request(sender) { lang ->
      val erased = service.erase(player.uniqueId.toString())
      consents.forget(player.uniqueId)
      // Without the name: the log shouldn't keep who asked to be forgotten.
      plugin.logger.info("A player erased what Allay kept about them")
      lang.text("privacy.erased_self", "memories" to erased.memories)
    }
  }

  /** `/allay privacy export <player|uuid>`: everything the backend keeps about them, into `exports/` for the admin to hand over. */
  fun export(sender: CommandSender, who: String) {
    val target = playerOf(who) ?: return locales.of(sender).send(sender, "privacy.unknown_player", "player" to who)
    request(sender) { lang ->
      val json = service.export(target.uniqueId.toString())
      val file = save(target.uniqueId, json)
      plugin.logger.info("${sender.name} exported what Allay keeps about a player to exports/${file.name}")
      lang.text("privacy.exported", "player" to (target.name ?: who), "file" to "plugins/${plugin.name}/exports/${file.name}")
    }
  }

  /** `/allay privacy erase <player|uuid>`: for a player who asked an admin to be forgotten. */
  fun erase(sender: CommandSender, who: String) {
    val target = playerOf(who) ?: return locales.of(sender).send(sender, "privacy.unknown_player", "player" to who)
    request(sender) { lang ->
      val erased = service.erase(target.uniqueId.toString())
      consents.forget(target.uniqueId)
      plugin.logger.info("${sender.name} erased what Allay kept about a player")
      lang.text("privacy.erased", "player" to (target.name ?: who), "memories" to erased.memories, "runs" to erased.runs)
    }
  }

  /** A UUID as typed, or a player this server has seen by that name; never one it hasn't, to keep typos from erasing a stranger. */
  @Suppress("DEPRECATION")
  private fun playerOf(who: String): OfflinePlayer? {
    runCatching { UUID.fromString(who) }.getOrNull()?.let { return plugin.server.getOfflinePlayer(it) }
    plugin.server.getPlayerExact(who)?.let { return it }
    return plugin.server.offlinePlayers.firstOrNull { it.name.equals(who, ignoreCase = true) }
  }

  private fun save(uuid: UUID, json: String): File {
    val folder = File(plugin.dataFolder, "exports").apply { mkdirs() }
    val file = File(folder, "$uuid-${LocalDateTime.now().format(STAMP)}.json")
    file.createNewFile()
    try {
      Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
    } catch (_: UnsupportedOperationException) {
      file.setReadable(false, false)
      file.setReadable(true, true)
    }
    file.writeText(json)
    return file
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
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    /** A question older than this doesn't go on when its asker agrees: they've moved on. */
    private const val PENDING_MS = 10 * 60_000L
  }
}
