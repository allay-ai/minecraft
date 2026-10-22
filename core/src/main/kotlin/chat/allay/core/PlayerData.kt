package chat.allay.core

import chat.allay.core.tools.Death
import chat.allay.core.tools.Goal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.logging.Logger

/** What the backend keeps for the plugin about one player (server 6.9): the time zone they named, their settings, and their goals. */
@Serializable data class PlayerState(
  val timezone: String? = null,
  val settings: Settings? = null,
  val goals: List<Goal>? = null,
)

/**
 * Each player's state, kept by the backend instead of files on the server (since 6.7). The plugin loads it when a
 * player who agreed to the notice joins, keeps it in memory while they play, and sends a change a few seconds later,
 * when they leave, and at shutdown, never on the server thread. Deaths go the same way, one request each.
 *
 * Nothing about a player reaches the backend before they agree ([agreed]); until then their changes stay in memory.
 * When the backend can't be reached, sending goes on with growing pauses, and what is still unsent at shutdown waits
 * in the sealed [Spool] for the next start. A backend before 6.9 keeps nothing: the state then lasts until the server
 * stops, and the log says so once.
 */
class PlayerData(
  private val backend: Backend,
  private val scope: CoroutineScope,
  private val spool: Spool?,
  private val log: Logger,
  /** Whether the player agreed to the notice; may ask the backend. */
  private val agreed: suspend (UUID) -> Boolean,
  /** The same without a request, for the shutdown, when there's no time to ask. */
  private val agreedNow: (UUID) -> Boolean,
  private val online: (UUID) -> Boolean,
  private val debounceMs: Long = 3_000,
) {
  interface Backend {
    /** The player's state, or null when the backend keeps none. */
    suspend fun state(userId: String): PlayerState?
    suspend fun saveState(userId: String, state: PlayerState)
    suspend fun death(userId: String, death: Death)
  }

  /** Called off the server thread with a player's state once the backend sent it; the platform puts goals back where they live. */
  @Volatile var loaded: (UUID, PlayerState) -> Unit = { _, _ -> }

  private val states = ConcurrentHashMap<UUID, PlayerState>()
  /** Players whose state changed since the backend last got it. */
  private val dirty = ConcurrentHashMap.newKeySet<UUID>()
  /** Players whose state came from the backend, so sending theirs can't overwrite what this run never saw. */
  private val fetched = ConcurrentHashMap.newKeySet<UUID>()
  private val deaths = ConcurrentLinkedQueue<Pair<UUID, Death>>()
  private val timers = ConcurrentHashMap<UUID, Job>()
  private val draining = Mutex()
  @Volatile private var retry: Job? = null
  @Volatile private var pause = FIRST_PAUSE_MS

  /** A backend before 6.9: it has no such routes, so nothing is sent until the next start. */
  @Volatile var old = false
    private set

  fun of(uuid: UUID): PlayerState = states[uuid] ?: PlayerState()

  /** Changes the player's state in memory at once and sends it a few seconds later; a burst of changes goes as one. */
  fun update(uuid: UUID, change: (PlayerState) -> PlayerState): PlayerState {
    val state = states.compute(uuid) { _, old -> change(old ?: PlayerState()) }!!
    dirty += uuid
    later(uuid)
    return state
  }

  /** Every state in memory, for counts that must not wait for the network. */
  fun all(): Collection<PlayerState> = states.values

  /** A player joined: their state comes from the backend, when they agreed to the notice. */
  fun join(uuid: UUID): Job = scope.launch { load(uuid) }

  /** After a player agreed: what they did before goes out, and what the backend kept comes in. */
  fun consented(uuid: UUID): Job = scope.launch {
    load(uuid)
    if (uuid in dirty) send(uuid)
  }

  /** True once the backend's copy is here, merged under what the player changed meanwhile. */
  private suspend fun load(uuid: UUID): Boolean {
    if (old || !safely { agreed(uuid) }) return false
    val remote = try {
      backend.state(uuid.toString())
    } catch (error: Exception) {
      if (!tooOld(error)) log.warning("Couldn't load a player's Allay data: ${reason(error)}")
      return false
    }
    fetched += uuid
    if (remote == null) return true
    val state = states.compute(uuid) { _, local -> if (local == null || uuid !in dirty) remote else merge(local, remote) }!!
    loaded(uuid, state)
    return true
  }

  /** What the player set before the backend's copy arrived wins; the rest comes from the backend. */
  private fun merge(local: PlayerState, remote: PlayerState) =
    PlayerState(local.timezone ?: remote.timezone, local.settings ?: remote.settings, local.goals ?: remote.goals)

  /**
   * The player left: a change goes out now, and the state leaves memory once nothing is waiting. A player who never
   * agreed leaves nothing behind.
   */
  fun quit(uuid: UUID): Job = scope.launch {
    timers.remove(uuid)?.cancel()
    val sent = send(uuid)
    if (!online(uuid) && (sent || !safely { agreed(uuid) })) {
      dirty -= uuid
      fetched -= uuid
      states.remove(uuid)
    }
  }

  /** A death goes to the backend as it happens; one of a player who didn't agree goes nowhere. */
  fun died(uuid: UUID, death: Death) {
    deaths += uuid to death
    scope.launch { drain() }
  }

  /** Erasure: nothing of the player stays here or waits to be sent. The backend forgets its own copy. */
  fun forget(uuid: UUID) {
    timers.remove(uuid)?.cancel()
    dirty -= uuid
    fetched -= uuid
    states.remove(uuid)
    deaths.removeIf { it.first == uuid }
  }

  /** At start: what the last shutdown couldn't send goes out first. */
  fun start() {
    val pending = spool?.read() ?: return
    pending.states.forEach { (id, state) ->
      val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return@forEach
      states.putIfAbsent(uuid, state)
      // Written from a copy the backend had sent, so it's whole.
      fetched += uuid
      dirty += uuid
    }
    pending.deaths.forEach { entry -> runCatching { UUID.fromString(entry.user_id) }.getOrNull()?.let { deaths += it to entry.death } }
    spool.delete()
    scope.launch { flush() }
  }

  /**
   * At shutdown: sends what is waiting for at most [millis], on a thread of its own, since a hung request can't be cut
   * short; what is still unsent then goes into the spool, sealed.
   */
  fun stop(millis: Long) {
    timers.values.forEach(Job::cancel)
    retry?.cancel()
    val sender = Thread({ runBlocking { flush() } }, "Allay player data").apply { isDaemon = true; start() }
    sender.join(millis)
    // Only players who agreed: anyone else's settings were never meant to outlive this run.
    val unsent = dirty.filter(agreedNow).associate { it.toString() to of(it) }
    val waiting = deaths.filter { agreedNow(it.first) }.map { Spool.Entry(it.first.toString(), it.second) }
    if (unsent.isEmpty() && waiting.isEmpty()) return
    if (spool == null) return log.warning("Couldn't send ${unsent.size + waiting.size} changes of players' Allay data, and without a license or server.token there is no key to keep them until the next start")
    spool.write(Spool.Pending(unsent, waiting))
    log.info("Keeping ${unsent.size + waiting.size} unsent changes of players' Allay data, sealed, until the next start")
  }

  private fun later(uuid: UUID) {
    timers.put(uuid, scope.launch {
      delay(debounceMs)
      timers.remove(uuid)
      send(uuid)
    })?.cancel()
  }

  /** Everything waiting: changed states and deaths. */
  private suspend fun flush() {
    dirty.toList().forEach { send(it) }
    drain()
  }

  /** True when the backend has the player's state, or there was nothing to send. */
  private suspend fun send(uuid: UUID): Boolean {
    if (uuid !in dirty) return true
    if (old || !safely { agreed(uuid) }) return false
    // A state that never met the backend's copy would overwrite it with defaults: merge first.
    if (uuid !in fetched && !load(uuid)) return false
    val state = of(uuid)
    // A change while the request runs marks the player again, so it goes out next time.
    dirty -= uuid
    return try {
      backend.saveState(uuid.toString(), state)
      pause = FIRST_PAUSE_MS
      if (uuid !in dirty && !online(uuid)) {
        fetched -= uuid
        states.remove(uuid)
      }
      true
    } catch (error: Exception) {
      dirty += uuid
      if (!tooOld(error)) failed(error)
      false
    }
  }

  private suspend fun drain(): Unit = draining.withLock {
    while (true) {
      val (uuid, death) = deaths.peek() ?: return
      if (old) return
      if (!safely { agreed(uuid) }) {
        deaths.poll()
        continue
      }
      try {
        backend.death(uuid.toString(), death)
        deaths.poll()
        pause = FIRST_PAUSE_MS
      } catch (error: Exception) {
        if (!tooOld(error)) failed(error)
        return
      }
    }
  }

  /** The backend didn't take it: try again after a pause that doubles up to ten minutes. */
  private fun failed(error: Exception) {
    if (retry?.isActive == true) return
    log.warning("Couldn't send players' Allay data to the backend (${reason(error)}); trying again in ${pause / 1000} s")
    val wait = pause
    pause = (pause * 2).coerceAtMost(LAST_PAUSE_MS)
    retry = scope.launch {
      delay(wait)
      flush()
    }
  }

  /** True for a backend before 6.9, which has no such routes; logged once. */
  private fun tooOld(error: Exception): Boolean {
    if ((error as? ApiErrorException)?.apiError?.statusCode !in OLD_BACKEND) return false
    if (!old) log.warning("The backend keeps no player data (it's older than Allay Server 6.9): settings, goals, and time zones last until the server stops, and deaths aren't kept. Update the backend")
    old = true
    return true
  }

  private fun reason(error: Exception) = (error as? ApiErrorException)?.apiError?.code ?: error.javaClass.simpleName

  private suspend fun safely(check: suspend () -> Boolean) = try {
    check()
  } catch (_: Exception) {
    false
  }

  companion object {
    private val OLD_BACKEND = setOf(404, 405)
    private const val FIRST_PAUSE_MS = 30_000L
    private const val LAST_PAUSE_MS = 600_000L
  }
}

/** What a shutdown couldn't send, in `pending.bin`, sealed ([SealedFile]) and gone once read. */
class Spool(file: File, secret: () -> String?, log: Logger) {
  @Serializable data class Entry(val user_id: String, val death: Death)
  @Serializable data class Pending(val states: Map<String, PlayerState> = emptyMap(), val deaths: List<Entry> = emptyList())

  private val sealed = SealedFile(file, secret, "allay pending v1", log)
  private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

  fun write(pending: Pending) {
    sealed.write(json.encodeToString(Pending.serializer(), pending))
  }

  /** What waits, or null when nothing does. */
  fun read(): Pending? = sealed.read()?.let { runCatching { json.decodeFromString(Pending.serializer(), it) }.getOrNull() }

  fun delete() = sealed.delete()

  companion object {
    const val FILE = "pending.bin"
  }
}
