package chat.allay.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.logging.Logger

/**
 * The server's chat for `chat_history` (since 6.38): what everyone read, what a local chat showed to the players near,
 * and private messages, handed to the backend every few seconds off the server thread. The backend seals each line and
 * forgets it after 72 hours; a reader gets back only what they saw or wrote. The chat is everyone's on the server, so a
 * line needs nobody's agreement (the owner settled it on 2026-11-11). Nothing goes to disk: what can't be sent before
 * the server stops is lost. Off with `tools.enabled.chat_history: false`.
 */
class ChatHistory(
  private val platform: Platform,
  private val scope: CoroutineScope,
  private val log: Logger,
  private val config: () -> Config,
  /** Sends a batch; a failure keeps it for the next round. */
  private val backend: suspend (List<Line>) -> Unit,
  /** Forgets everything the backend keeps of this server's chat; answers how many lines went. */
  private val erase: suspend () -> Int = { 0 },
  private val clock: () -> Long = System::currentTimeMillis,
  private val flushMs: Long = 5_000,
) {
  @Serializable data class Person(val uuid: String, val name: String)

  /** A line as the backend takes it; colors stay as § codes. */
  @Serializable data class Line(
    val at: Long,
    val channel: String,
    val from: Person,
    val to: Person? = null,
    val audience: List<String>? = null,
    val text: String,
  )

  private val queue = ConcurrentLinkedDeque<Line>()
  private val sending = Mutex()
  /** Whom each player last wrote to or heard from privately: where `/r` goes. */
  private val partners = ConcurrentHashMap<UUID, UUID>()
  /** A line Allay sends for a player, while it goes out: the thread that sends it, and whether a hook heard it there. */
  private class Pending(val thread: Thread) {
    @Volatile var heard = false
  }

  /** Players Allay is sending a line for right now. */
  private val own = ConcurrentHashMap<UUID, Pending>()
  /** The last moment taken per sender: the backend keeps one line per sender and millisecond. */
  private val moments = ConcurrentHashMap<UUID, Long>()
  @Volatile private var loop: Job? = null
  @Volatile private var failing = false
  /** A backend before 6.34 has no such route: nothing is sent until the next start. */
  @Volatile var old = false
    private set

  val on: Boolean get() = config().tools.enabled("chat_history")

  /**
   * A chat line [sender] wrote, from any thread. [readers] are the players the chat showed it to, when the platform
   * knows them (Bukkit's recipients): everyone online makes it public, fewer a local line only they may read again.
   */
  fun chat(sender: PlayerAudience, text: String, readers: Collection<UUID>? = null) {
    heard(sender.uuid)
    if (!on || text.isBlank()) return
    val others = readers?.toSet()?.minus(sender.uuid)
    val local = others != null && platform.players.any { it.uuid != sender.uuid && it.uuid !in others }
    add(Line(moment(sender.uuid), if (local) LOCAL else PUBLIC, person(sender), audience = others?.takeIf { local }?.take(MAX_AUDIENCE)?.map(UUID::toString), text = text.take(MAX_TEXT)))
  }

  /** A private message from [sender] to [to]. */
  fun whisper(sender: PlayerAudience, to: PlayerAudience, text: String) {
    heard(sender.uuid)
    if (!on || text.isBlank()) return
    partners[sender.uuid] = to.uuid
    partners[to.uuid] = sender.uuid
    add(Line(moment(sender.uuid), PRIVATE, person(sender), person(to), text = text.take(MAX_TEXT)))
  }

  /**
   * A command [sender] typed, without its `/`, on the server thread: a private message (`/msg Steve hi`) or a reply to
   * the last one (`/r hi`) goes in, anything else is none of the chat's business.
   */
  fun command(sender: PlayerAudience, line: String) {
    // Any command a hook lets through counts as heard: one Allay types for a player went out.
    heard(sender.uuid)
    if (!on) return
    val words = line.trim().split(' ', limit = 3)
    val name = words[0].substringAfter(':').lowercase()
    val settings = config().chatHistory
    when (name) {
      in settings.privateCommands -> {
        if (words.size < 3) return
        val to = platform.player(words[1])?.takeIf { it.uuid != sender.uuid } ?: return
        whisper(sender, to, words[2])
      }
      in settings.replyCommands -> {
        val text = words.drop(1).joinToString(" ")
        val to = partners[sender.uuid]?.let(platform::player) ?: return
        whisper(sender, to, text)
      }
    }
  }

  /**
   * A line Allay sends for [sender] through [send] (a say button, `send_message`); true when it went out. The platform's
   * hooks hear it on the thread that sends it, so a line the player typed meanwhile on another thread never counts.
   * Where they hear whatever [send] does ([hooked]), a line they didn't hear was refused (a mute, a chat filter, an
   * anti-spam) and isn't kept. Where they may not (a mod's own `/msg`), [record] keeps it once [send] says it ran.
   */
  fun sent(sender: UUID, hooked: Boolean, record: () -> Unit = {}, send: () -> Boolean): Boolean {
    val pending = Pending(Thread.currentThread())
    own[sender] = pending
    val ran = try {
      send()
    } finally {
      own.remove(sender, pending)
    }
    if (pending.heard) return true
    if (hooked || !ran) return false
    record()
    return true
  }

  fun quit(uuid: UUID) {
    partners.remove(uuid)
    moments.remove(uuid)
  }

  fun start() {
    loop?.cancel()
    loop = scope.launch {
      while (isActive) {
        delay(flushMs)
        flush()
      }
    }
  }

  /** At shutdown: what's waiting goes out for at most [millis]; the rest is dropped. */
  fun stop(millis: Long) {
    loop?.cancel()
    if (queue.isEmpty() || old) return
    Thread({ runBlocking { flush() } }, "Allay chat history").apply { isDaemon = true; start() }.join(millis)
  }

  /** Sends what's waiting in batches; on a failure the batch goes back to the front for the next round. */
  suspend fun flush() = sending.withLock {
    while (!old) {
      val batch = generateSequence { queue.pollFirst() }.take(BATCH).toList()
      if (batch.isEmpty()) return@withLock
      try {
        backend(batch)
        if (failing) log.info("Chat history reaches the backend again")
        failing = false
      } catch (error: Exception) {
        val status = (error as? ApiErrorException)?.apiError?.statusCode
        if (status in OLD_BACKEND) {
          old = true
          queue.clear()
          log.warning("The backend keeps no chat history (it's older than Allay Server 6.34): chat_history stays off until it's updated")
          return@withLock
        }
        // The backend refused the batch itself: sent again, it would hold up every line after it for good. Anything
        // else (a key being fixed, a backend down, too many lines a minute) waits for the next round.
        if (status in REFUSED) {
          log.warning("The backend refused ${batch.size} lines of the chat history (${(error as ApiErrorException).apiError.code}: ${error.apiError.message}); they're dropped")
          continue
        }
        batch.asReversed().forEach(queue::addFirst)
        trim()
        if (!failing) log.warning("Couldn't send the chat history to the backend (${(error as? ApiErrorException)?.apiError?.code ?: error.javaClass.simpleName}); trying again")
        failing = true
        return@withLock
      }
    }
  }

  /**
   * `/allay chathistory reset confirm`: what waits here goes, and the backend forgets the server's whole chat; [done]
   * hears how many lines went, off the server thread.
   */
  fun reset(done: (Result<Int>) -> Unit) {
    scope.launch {
      val result = sending.withLock {
        queue.clear()
        runCatching { erase() }
      }
      done(result)
    }
  }

  /** Lines waiting, for tests. */
  val waiting: List<Line> get() = queue.toList()

  private fun add(line: Line) {
    if (old) return
    queue.addLast(line)
    trim()
  }

  /** A backend out of reach for long keeps the newest lines only. */
  private fun trim() {
    while (queue.size > MAX_WAITING) queue.pollFirst()
  }

  private fun heard(sender: UUID) {
    own[sender]?.takeIf { it.thread === Thread.currentThread() }?.heard = true
  }

  /** Now, or a millisecond after the sender's last line when two come at once (a /msg to several players). */
  private fun moment(sender: UUID): Long = moments.merge(sender, clock()) { last, now -> if (now > last) now else last + 1 }!!

  private fun person(player: PlayerAudience) = Person(player.uuid.toString(), player.name)

  companion object {
    const val PUBLIC = "public"
    const val LOCAL = "local"
    const val PRIVATE = "private"
    /** What the backend takes at once. */
    const val BATCH = 200
    const val MAX_TEXT = 1024
    const val MAX_AUDIENCE = 200
    const val MAX_WAITING = 2000
    private val OLD_BACKEND = setOf(404, 405)
    /** Answers about the batch itself: malformed, too big, or lines the backend can't take. */
    private val REFUSED = setOf(400, 413, 422)
  }
}
