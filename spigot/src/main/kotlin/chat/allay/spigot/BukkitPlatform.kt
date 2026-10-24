package chat.allay.spigot

import chat.allay.core.*
import chat.allay.core.tools.Dimensions
import chat.allay.spigot.tools.id

import net.md_5.bungee.api.ChatMessageType
import net.md_5.bungee.api.chat.BaseComponent
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.HoverEvent
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.chat.hover.content.Text
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A Bukkit sender as the core sees it. */
open class BukkitSender(open val sender: CommandSender) : Audience {
  override val name: String get() = sender.name
  override val uuid: UUID? get() = null
  override val operator: Boolean get() = sender.isOp
  override fun can(permission: String) = sender.hasPermission(permission)
  // Bukkit gives a permission nobody declared to operators only; an expansion's own goes to everyone unless set.
  override fun can(permission: String, byDefault: Boolean) =
    if (sender.isPermissionSet(permission) || org.bukkit.Bukkit.getPluginManager().getPermission(permission) != null) sender.hasPermission(permission) else byDefault || sender.isOp
  override fun <T> unwrap(type: Class<T>): T? = type.takeIf { it.isInstance(sender) }?.cast(sender)
  override fun send(text: String) = sender.sendMessage(text)
  override fun send(line: Line) {
    if (!line.empty) sender.spigot().sendMessage(*components(line))
  }

  override fun equals(other: Any?) = other is BukkitSender && other.sender == sender
  override fun hashCode() = sender.hashCode()
}

/** A Bukkit player as the core sees them. */
class BukkitPlayer(val player: Player) : BukkitSender(player), PlayerAudience {
  override val uuid: UUID get() = player.uniqueId
  override val online: Boolean get() = player.isOnline
  override val locale: String get() = player.locale
  override val address get() = player.address?.address
  override val position: Protocol.Position get() = player.location.let { at -> Protocol.Position(at.blockX, at.blockY, at.blockZ, Dimensions.id(player.world), player.world.name) }
  override fun actionBar(text: String) = player.spigot().sendMessage(ChatMessageType.ACTION_BAR, *TextComponent.fromLegacyText(text))
  override fun show(window: Window) = Windows.show(player, window)
  override fun closeWindow() = Windows.close(player)
  override fun chat(text: String) = player.chat(text)
}

/** Whoever sent a command, for the core. */
val CommandSender.audience: Audience get() = if (this is Player) BukkitPlayer(this) else BukkitSender(this)
val Player.audience: BukkitPlayer get() = BukkitPlayer(this)

/** The Bukkit sender behind an audience the core hands back. */
val Audience.sender: CommandSender get() = (this as BukkitSender).sender
val PlayerAudience.player: Player get() = (this as BukkitPlayer).player

/** The core's lines as BungeeCord's components. */
fun components(line: Line): Array<BaseComponent> = line.parts.flatMap { part ->
  when (part) {
    is Line.Text -> if (part.links) TextComponent.fromLegacyText(part.text).toList() else plain(part.text).toList()
    is Line.Button -> listOf(TextComponent(*plain(part.label)).apply {
      clickEvent = when (val click = part.click) {
        is Line.Click.Run -> ClickEvent(ClickEvent.Action.RUN_COMMAND, click.command)
        is Line.Click.Open -> ClickEvent(ClickEvent.Action.OPEN_URL, click.url)
        is Line.Click.Copy -> ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, click.text)
      }
      hoverEvent = HoverEvent(HoverEvent.Action.SHOW_TEXT, Text(plain(part.hover)))
    })
  }
}.toTypedArray()

/**
 * Legacy text as components without the links the game guesses: Bukkit makes `license.key` or `provider.mode`
 * a link to http://license.key.
 */
internal fun plain(text: String): Array<out BaseComponent> = TextComponent.fromLegacyText(text).onEach { it.clickEvent = null }

/** The core's languages for Bukkit's senders. */
fun Locales.of(sender: CommandSender?): Lang = of(sender?.audience)
fun Locales.code(player: Player): String = code(player.audience)
fun Locales.explain(player: Player): Locales.Choice = explain(player.audience)

/** Bukkit's scheduler for the core: the server thread is the main one. */
class BukkitScheduler(private val plugin: JavaPlugin) : Scheduler {
  override fun main(block: () -> Unit) {
    if (plugin.isEnabled) plugin.server.scheduler.runTask(plugin, Runnable(block))
  }

  override fun later(ticks: Long, block: () -> Unit): AutoCloseable =
    plugin.server.scheduler.runTaskLater(plugin, Runnable(block), ticks).let { task -> AutoCloseable(task::cancel) }

  override fun every(delay: Long, period: Long, async: Boolean, block: () -> Unit): AutoCloseable {
    val scheduler = plugin.server.scheduler
    val task = if (async) scheduler.runTaskTimerAsynchronously(plugin, Runnable(block), delay, period) else scheduler.runTaskTimer(plugin, Runnable(block), delay, period)
    return AutoCloseable(task::cancel)
  }

  override fun <T> call(seconds: Long, block: () -> T): T = plugin.server.scheduler.callSyncMethod(plugin) { block() }.get(seconds, TimeUnit.SECONDS)

  override val onMain: Boolean get() = plugin.server.isPrimaryThread
}

/** Bukkit as Allay's platform. */
class BukkitPlatform(private val plugin: JavaPlugin) : Platform {
  private val server get() = plugin.server
  override val log get() = plugin.logger
  @Suppress("DEPRECATION") override val version: String get() = plugin.description.version
  override val folder get() = plugin.dataFolder
  override fun resource(path: String) = plugin.getResource(path)
  override val scheduler = BukkitScheduler(plugin)
  override val enabled get() = plugin.isEnabled
  override val players: Collection<PlayerAudience> get() = server.onlinePlayers.map(::BukkitPlayer)
  override val console: Audience get() = BukkitSender(server.consoleSender)
  override fun player(id: UUID) = server.getPlayer(id)?.let(::BukkitPlayer)
  override fun player(name: String) = server.getPlayerExact(name)?.let(::BukkitPlayer)

  @Suppress("DEPRECATION")
  override fun known(who: String): Pair<UUID, String?>? {
    runCatching { UUID.fromString(who) }.getOrNull()?.let { id -> return id to server.getOfflinePlayer(id).name }
    server.getPlayerExact(who)?.let { return it.uniqueId to it.name }
    return server.offlinePlayers.firstOrNull { it.name.equals(who, ignoreCase = true) }?.let { it.uniqueId to it.name }
  }

  override fun dispatch(who: Audience, command: String) = server.dispatchCommand(who.sender, command)
  override fun check(who: Audience, command: String) = chat.allay.spigot.tools.CommandTools.check(who.sender, command)
  override fun vanished(player: PlayerAudience) = Vanish.vanished(player.player)
  override fun placeholders(about: Audience?) = Placeholders.about((about as? BukkitSender)?.sender)
  @Suppress("DEPRECATION") override val motd: String get() = server.motd
  override fun asker(who: Audience, language: String?): Asker = SenderAsker(who.sender, language)
}

/** The display's sounds and particles as Bukkit's, from `config.yml`; an unknown name falls back to the default with a warning. */
class BukkitEffects(private val plugin: JavaPlugin, config: Config) : Effects {
  private val ui = config.ui
  private val particle = value("ui.particles.type", ui.particleType, Particle.ENCHANT) { Particle.valueOf(it) }
  private val sounds = mapOf(
    Cue.ANSWER to sound(ui.successSound, Sound.ENTITY_ALLAY_ITEM_GIVEN),
    Cue.ERROR to sound(ui.errorSound, Sound.ENTITY_ALLAY_AMBIENT_WITHOUT_ITEM),
    Cue.THINKING to sound(ui.thinkingSound, Sound.ENTITY_ALLAY_AMBIENT_WITH_ITEM),
    Cue.REMINDER to Sound.BLOCK_NOTE_BLOCK_BELL,
  )

  /** By Bukkit's name (`ENTITY_ALLAY_ITEM_GIVEN`) or the game's id (`entity.allay.item_given`), as the mods read it. */
  private fun sound(setting: Config.Sound, default: Sound) = value(setting.key, setting.name, default) { name ->
    if ('.' in name) org.bukkit.Registry.SOUNDS.get(org.bukkit.NamespacedKey.fromString(name.lowercase())!!)!! else Sound.valueOf(name)
  }

  /** A name from the config as Bukkit's value; an unknown name falls back to [default] with a warning. */
  private fun <T> value(key: String, name: String?, default: T, parse: (String) -> T): T {
    name ?: return default
    return runCatching { parse(name.uppercase()) }.getOrElse {
      plugin.logger.warning("$key: there is no such value as $name; using the default")
      default
    }
  }

  override fun play(player: PlayerAudience, cue: Cue, volume: Float, pitch: Float) {
    val bukkit = player.player
    bukkit.playSound(bukkit.location, sounds.getValue(cue), volume, pitch)
  }

  override fun particles(player: PlayerAudience) {
    val bukkit = player.player
    bukkit.world.spawnParticle(particle, bukkit.location.add(0.0, ui.particleHeightOffset, 0.0), ui.particleCount, 0.3, 0.3, 0.3, 0.01)
  }
}
