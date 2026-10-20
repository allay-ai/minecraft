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

/**
 * `/allay privacy`: what Allay keeps about a player and for how long, and their data on request (since backend 6.0).
 * A player erases their own; admins export or erase anyone's, for a player who asked them. An export is personal data,
 * so it goes into an owner-only file, never into chat or the log.
 */
class PrivacyCommands(
  private val plugin: JavaPlugin,
  private val locales: Locales,
  private val service: Service<*>,
  private val scope: CoroutineScope,
) {
  fun info(sender: CommandSender) {
    val lang = locales.of(sender)
    lang.send(sender, "privacy.info")
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

  /** Runs [block] off the server thread; it returns the line to show, colored on the way. */
  private fun request(sender: CommandSender, block: suspend (Lang) -> String) {
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
      if (plugin.isEnabled) plugin.server.scheduler.runTask(plugin, Runnable { sender.sendMessage(formatMessage(line)) })
    }
  }

  companion object {
    /** The word the confirmation button adds, so a typed `/allay privacy erase` never erases at once. */
    const val CONFIRM = "confirm"
    private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
  }
}
