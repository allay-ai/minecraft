package chat.allay.core

import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.util.UUID
import java.util.logging.Logger

/**
 * A chat line as Allay builds it: `§`-colored text and buttons. Each platform turns it into its own components, so the
 * rules of what a line says and where its buttons lead live here once.
 */
data class Line(val parts: List<Part>) {
  constructor(vararg parts: Part) : this(parts.toList())

  sealed interface Part

  /**
   * Colored text. With [links], addresses in it open in the browser, as the game's legacy parser guesses them; without,
   * `license.key` or `provider.mode` stay words.
   */
  data class Text(val text: String, val links: Boolean = false) : Part

  /** A piece of text that does something when clicked, with [hover] as its tooltip. */
  data class Button(val label: String, val hover: String, val click: Click) : Part

  sealed interface Click {
    /** Runs the command as the player who clicks, with its `/`. */
    data class Run(val command: String) : Click
    data class Open(val url: String) : Click
    data class Copy(val text: String) : Click
  }

  val empty get() = parts.all { it is Text && it.text.isEmpty() }

  /** What a reader without buttons, like the console, sees. */
  fun plain(): String = parts.joinToString("") { if (it is Text) it.text else (it as Button).label }

  companion object {
    fun text(text: String, links: Boolean = false) = Line(Text(text, links))
    val SPACE = Text(" ")
  }
}

/**
 * Whoever Allay talks to: a player, the console, or a command block. Each platform wraps its own sender in one. Texts
 * arrive colored with `§` codes.
 */
interface Audience {
  val name: String

  /** The player's id; null for the console and command blocks. */
  val uuid: UUID?

  val operator: Boolean

  fun can(permission: String): Boolean

  /**
   * Whether they have a permission nobody declared to Allay, like an expansion's own; [byDefault] answers where no
   * permissions plugin or mod does. Operators have it either way.
   */
  fun can(permission: String, byDefault: Boolean): Boolean = can(permission)

  /** The platform's own sender or player, for an expansion's tool; null when it isn't of [type]. */
  fun <T> unwrap(type: Class<T>): T? = null

  fun send(text: String)

  fun send(line: Line)
}

/** A player online. Calls that touch the world happen on the server thread. */
interface PlayerAudience : Audience {
  override val uuid: UUID

  /** Still on the server: a run outlives a quit. */
  val online: Boolean

  /** The language the player's game is set to, like `en_us`. */
  val locale: String

  /** The address the player connected from, for the country GeoIP finds. */
  val address: InetAddress?

  /** Where the player stands now, with the world's name. */
  val position: Protocol.Position

  fun actionBar(text: String)

  /** Draws a [Window] over the screen; [closeWindow] takes it away. */
  fun show(window: Window)

  fun closeWindow()

  /** Says [text] in the public chat as the player, through every chat plugin, as if they typed it. */
  fun chat(text: String)
}

/** The sounds Allay plays; each platform picks its own sound for each, from `config.yml` or its default. */
enum class Cue { THINKING, ANSWER, ERROR, REMINDER }

/** The display's sounds and particles, resolved from `config.yml` once a load; an unknown name warns once and falls back. */
interface Effects {
  fun play(player: PlayerAudience, cue: Cue, volume: Float, pitch: Float)

  fun particles(player: PlayerAudience)
}

/** Work on the server thread, now, later, or again and again. Ticks are the game's, 20 a second. */
interface Scheduler {
  /** Runs [block] on the server thread soon, unless Allay was switched off by then. */
  fun main(block: () -> Unit)

  fun later(ticks: Long, block: () -> Unit): AutoCloseable

  /** Runs [block] after [delay] ticks and then every [period]; [async] keeps it off the server thread. */
  fun every(delay: Long, period: Long, async: Boolean = false, block: () -> Unit): AutoCloseable

  /** Runs [block] on the server thread and waits at most [seconds] for it; throws a `TimeoutException` when it takes longer. */
  fun <T> call(seconds: Long, block: () -> T): T

  val onMain: Boolean
}

/**
 * What Allay needs from a game platform to run: Bukkit, Fabric, or NeoForge. Everything the core does with players,
 * chat, and the clock goes through here; the tools reach the world through their own context.
 */
interface Platform {
  val log: Logger

  /** Allay's own version, like `6.9.0`, or `dev`. */
  val version: String

  /** Where the server's id lives, which names its shared thread of public questions: one per world in singleplayer. */
  val folder: File

  /**
   * Where everything that goes with the license lives: `config.yml`, `lang/`, `license.key`, what operators heard about
   * the subscription, `geoip/`, the sealed lists of who agreed and who was too young, the spool, and the quota book.
   * The same as [folder] on a server, shared by every world of a singleplayer game, as the backend keeps players'
   * agreements and data by license.
   */
  val shared: File get() = folder

  /** A file the jar ships, like `lang/en.yml`. */
  fun resource(path: String): InputStream?

  val scheduler: Scheduler

  /** Allay is on: work queued for later checks this before it starts. */
  val enabled: Boolean

  val players: Collection<PlayerAudience>

  val console: Audience

  fun player(id: UUID): PlayerAudience?

  /** An online player by their exact name. */
  fun player(name: String): PlayerAudience?

  /**
   * A player this server has seen, by UUID or by name, with the name it knows; never one it hasn't, to keep a typo
   * from erasing a stranger.
   */
  fun known(who: String): Pair<UUID, String?>?

  /** Runs [command] (without its `/`) as [who], as if they typed it; true when the platform ran it. */
  fun dispatch(who: Audience, command: String): Boolean

  /**
   * Whether [command] (without its `/`) would run for [who] now: it exists, they may run it, and, where the platform
   * can parse it beforehand, its arguments fit. Null where the platform can't tell. Server thread.
   */
  fun check(who: Audience, command: String): chat.allay.core.tools.CommandCatalog.Check? = null

  /** Whether a vanish plugin or mod hides [player] from the others; false where there's none. */
  fun vanished(player: PlayerAudience): Boolean = false

  /** Whether [viewer] may learn about [other] through Allay: vanished players stay hidden from everyone but themself and operators. */
  fun visible(viewer: Audience?, other: PlayerAudience): Boolean =
    other.uuid == viewer?.uuid || viewer?.operator == true || !vanished(other)

  /** What fills `{papi:…}` for [about]; null where nothing does. See [Lang.about]. */
  fun placeholders(about: Audience?): ((String) -> String?)? = null

  /** The server's message of the day, with its codes. */
  val motd: String

  /**
   * [who] as the backend sees whoever asked, with [language], the one Allay speaks to them in; read on the server
   * thread, since the request is built off it.
   */
  fun asker(who: Audience, language: String?): Asker
}

/** Sends the message unless the server turned it off with an empty text. */
fun Lang.send(to: Audience, key: String, vararg values: Pair<String, Any?>) {
  val text = text(key, *values)
  if (text.isNotEmpty()) to.send(Legacy.color(text))
}

/** Whether Allay may treat this sender as an admin: an operator, or anyone with `allay.admin`. */
val Audience.admin get() = operator || can(Permissions.ADMIN)
