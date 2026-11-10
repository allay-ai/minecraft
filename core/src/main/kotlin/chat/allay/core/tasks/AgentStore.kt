package chat.allay.core.tasks

import chat.allay.core.ApiErrorException
import chat.allay.core.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/** Where an agent may take things from, as its player said: the inventory always, the rest only when allowed. */
@Serializable data class SourceScope(
  /** The player's own ender chest, through an ender chest block near, as in the game. */
  val ender_chest: Boolean = false,
  /** The chests it may use, as `world:x,y,z`; null for every chest around that the player may open. */
  val chests: List<String>? = null,
)

/** Items an agent waits for: [item] as the catalog names it, [count] in all. */
@Serializable data class Need(val item: String, val count: Int)

/** An agent that waits for its player to bring [needs]; it holds no slot meanwhile and goes on once they're there. */
@Serializable data class Parked(
  val needs: List<Need>,
  val since: Long,
  /** When it gives up waiting: `park_hours` after [since]. */
  val until: Long,
  /** When the player last heard it still waits. */
  val reminded: Long = since,
  /** The agent's own words on what it waits for, for the player and its state card. */
  val note: String? = null,
)

/** An agent's own bounds: how far its player may go before it pauses, and how long it waits for them. */
@Serializable data class TaskLimits(val pause_distance: Int, val park_hours: Int)

/** Items a task holds at a place, kept with the agent so a restart doesn't hand them to another. */
@Serializable data class HeldItems(val place: String, val item: String, val count: Int)

/**
 * One automation agent as the backend keeps it between runs: everything [Agents] needs to bring it back after its
 * player left or the server stopped. [job] is the platform's own shape for what it left running in the world.
 */
@Serializable data class AgentSnapshot(
  val id: String,
  val name: String,
  val kind: String,
  val depth: Int,
  val parent: String? = null,
  val brief: String,
  val started_at: Long,
  /** Time it spent working, which `automation.max_minutes` counts; pauses and waits don't. */
  val active_ms: Long = 0,
  val wakes: Int = 0,
  val state: String,
  val question: String? = null,
  val wake_at: Long? = null,
  val inbox: List<String> = emptyList(),
  val results: List<String> = emptyList(),
  val anchor: Protocol.Position,
  val scope: SourceScope = SourceScope(),
  val parked: Parked? = null,
  val held: List<HeldItems> = emptyList(),
  val job: JsonObject? = null,
  val limits: TaskLimits? = null,
  /** A wake was out when it stopped: its tools may have run, so it reads the world again first. */
  val broke: String? = null,
)

/**
 * Each player's automation agents on this server, kept by the backend (6.31) so they go on after the player comes back
 * or the server restarts. Nothing goes on disk: a change goes out a moment later, again until the backend takes it, and
 * whatever a shutdown can't send in time ends with this run.
 */
class AgentStore(
  private val backend: () -> Backend,
  private val serverId: String,
  private val scope: CoroutineScope,
  private val log: Logger,
  private val debounceMs: Long = 2_000,
) {
  interface Backend {
    /** The player's agents on [serverId], oldest first; empty when there are none. */
    suspend fun agents(userId: String, serverId: String): List<JsonObject>

    /** Replaces them; an empty list forgets them. */
    suspend fun saveAgents(userId: String, serverId: String, agents: List<JsonObject>)
  }

  private val pending = ConcurrentHashMap<UUID, List<JsonObject>>()
  private val timers = ConcurrentHashMap<UUID, Job>()
  private val failures = ConcurrentHashMap<UUID, Int>()

  /** A backend before 6.31 keeps no agents: they last until the server stops, and the log says so once. */
  @Volatile private var old = false

  /** The player's agents come from the backend; null when it couldn't say, so nothing is brought back yet. */
  suspend fun load(owner: UUID): List<JsonObject>? {
    if (old) return emptyList()
    return try {
      backend().agents(owner.toString(), serverId)
    } catch (error: Exception) {
      if (tooOld(error)) emptyList() else null.also { log.warning("Couldn't load a player's automation agents: ${reason(error)}") }
    }
  }

  /** [agents] replace what the backend keeps for [owner], a moment later; a burst of changes goes as one. */
  fun save(owner: UUID, agents: List<JsonObject>) {
    if (old) return
    pending[owner] = agents
    later(owner, debounceMs)
  }

  /** Sends what waits for [owner] now: they left, and their agents must be there when they come back. */
  fun flush(owner: UUID): Job = scope.launch {
    timers.remove(owner)?.cancel()
    send(owner)
  }

  /** At shutdown: sends everything waiting for at most [millis] on a thread of its own; the rest ends with this run. */
  fun stop(millis: Long) {
    timers.values.forEach(Job::cancel)
    val sender = Thread({ runBlocking { pending.keys.toList().forEach { send(it) } } }, "Allay agents").apply { isDaemon = true; start() }
    sender.join(millis)
    if (pending.isNotEmpty()) log.warning("Couldn't save the automation agents of ${pending.size} players before the server stopped; they end with this run")
  }

  private fun later(owner: UUID, millis: Long) {
    timers.put(owner, scope.launch {
      delay(millis)
      timers.remove(owner)
      send(owner)
    })?.cancel()
  }

  private suspend fun send(owner: UUID) {
    val agents = pending.remove(owner) ?: return
    try {
      backend().saveAgents(owner.toString(), serverId, agents)
      failures.remove(owner)
    } catch (error: Exception) {
      if (tooOld(error)) return
      // A refusal of the list itself (too big, an odd shape) won't pass by trying again.
      if ((error as? ApiErrorException)?.apiError?.statusCode == 400) return log.warning("The backend refused a player's automation agents: ${error.message}")
      // A newer list may have come meanwhile; it goes instead.
      pending.putIfAbsent(owner, agents)
      val tries = failures.merge(owner, 1, Int::plus)!!
      if (tries == 1) log.warning("Couldn't save a player's automation agents (${reason(error)}); trying again")
      later(owner, minOf(60_000L, 2_000L shl minOf(tries, 5)))
    }
  }

  private fun tooOld(error: Exception): Boolean {
    val code = (error as? ApiErrorException)?.apiError?.statusCode
    if (code != 404 && code != 405) return false
    if (!old) log.info("ℹ️ This backend keeps no automation agents (it needs 6.31): they end when their player leaves or the server stops")
    old = true
    pending.clear()
    return true
  }

  private fun reason(error: Exception) = (error as? ApiErrorException)?.apiError?.code ?: error.javaClass.simpleName
}
