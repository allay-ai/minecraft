package chat.allay.core.tasks

import chat.allay.core.tools.ReminderBook
import chat.allay.core.tools.ToolError
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ConcurrentLinkedQueue

/** What an automation agent works on; each has its own switch and permission. */
enum class Kind(val wire: String) {
  CHESTS("chests"), FURNACES("furnaces"), BREWING("brewing"), CRAFTING("crafting");

  companion object {
    fun parse(name: String?): Kind = entries.firstOrNull { it.wire.equals(name?.trim(), ignoreCase = true) }
      ?: throw ToolError("kind must be one of ${entries.joinToString { it.wire }}")
  }
}

/**
 * One automation agent: “Automation agent: chests”. It starts with no history but its [brief], and runs in wakes:
 * each wake is one backend run that ends with `wait` or `finish`. What wakes it next lands in [inbox]: the
 * player's answer, a button, its job's end, or its timer. Touched from the server thread and the network threads.
 */
class Task(
  val id: String,
  val owner: UUID,
  val name: String,
  val kind: Kind,
  /** 1 when the main chat started it, 2 when another agent did; deeper is refused. */
  val depth: Int,
  val parent: String?,
  val brief: String,
  val startedAt: Long,
) {
  enum class State(val wire: String) { WORKING("working"), WAITING("waiting"), ASKING("asking"), DONE("done") }

  @Volatile var state = State.WORKING
  /** What an asking agent wants to know, for the main chat's prompt; open until the player answers. */
  @Volatile var question: String? = null
  /** When the timer of its last `wait` wakes it; null without one. */
  @Volatile var wakeAt: Long? = null
  /** Set by `wait` and `finish` during a wake, so the runner knows how the wake ended. */
  @Volatile var waited = false
  @Volatile var finished: String? = null
  /** Wakes so far; `tools.automation.max_wakes` stops a task that loops. */
  @Volatile var wakes = 0
  /** A wake is out at the backend now; what arrives meanwhile waits in [inbox]. */
  @Volatile var running = false

  val inbox = ConcurrentLinkedDeque<String>()

  /** Wakes in a row the backend couldn't take (busy, rate-limited, unreachable); a wake that runs resets it. */
  @Volatile var retries = 0

  /** What its tools reported doing (crafted, smelted, moved), newest last, for the agent that started it. */
  private val results = ConcurrentLinkedQueue<String>()

  val done get() = state == State.DONE

  /** What woke it, oldest first, up to [limit] characters; what doesn't fit stays in [inbox] for the next wake. */
  fun drain(limit: Int = MESSAGE_LIMIT): String {
    val parts = mutableListOf<String>()
    var size = 0
    while (true) {
      val next = inbox.peek() ?: break
      val cost = next.length + if (parts.isEmpty()) 0 else 1
      if (parts.isNotEmpty() && size + cost > limit) break
      inbox.poll()
      parts += next.take(limit)
      size += cost
    }
    return parts.joinToString("\n").take(limit)
  }

  /** A wake starts with [message]: the player's answer, a click, or the end of picking closes the open question. */
  fun begin(message: String) {
    if (message.lineSequence().any { line -> ANSWERS.any(line::startsWith) }) question = null
    waited = false
    state = State.WORKING
  }

  /**
   * How a wake that didn't finish leaves the agent. It asks when it showed buttons or a picking mode ([prompted]), said
   * something with a question mark, or said something with nothing else to wake it ([busy]: no job and no sub-agent
   * running). A line while a job runs (“smelting takes 3 minutes”) is news, not a question. A wake that ended without
   * wait counts as asking unless something else will wake it. An earlier question stays open until the player answers.
   */
  fun settle(said: String?, prompted: Boolean, busy: Boolean) {
    val asks = prompted || (said != null && ('?' in said || !busy))
    if (asks && said != null) question = said.take(200)
    state = if (asks || question != null || (!waited && !busy)) State.ASKING else State.WAITING
  }

  fun report(fact: String) {
    results += fact.take(300)
    while (results.size > MAX_RESULTS) results.poll()
  }

  fun results(): List<String> = results.toList()

  /** The line the agent that started it wakes with: how it ended, and what its tools did, so it can act on it. */
  fun outcome(how: String): String = buildString {
    append("[sub-agent] «$name» (${kind.wire}, $id) $how")
    val done = results.toList()
    if (done.isNotEmpty()) append("\nWhat its tools reported: ").append(done.joinToString("; "))
  }.take(1000)

  /** The chat label: “Automation agent: chests”. */
  fun title(prefix: String) = "$prefix: $name"

  companion object {
    /** The backend takes up to this many characters in a wake's message. */
    const val MESSAGE_LIMIT = 2000
    private const val MAX_RESULTS = 6
    private val ANSWERS = listOf("[player]", "[button]", "[job] picking")
  }
}

/** Every running automation agent, by owner. A player has at most [maxTasks] at once, of all depths together. */
class TaskBook(private val maxTasks: () -> Int, private val newId: () -> String = ReminderBook::shortId) {
  private val tasks = ConcurrentHashMap<UUID, MutableList<Task>>()

  fun start(owner: UUID, kind: Kind, name: String, brief: String, depth: Int, parent: String?, now: Long = System.currentTimeMillis()): Task {
    if (depth > MAX_DEPTH) throw ToolError("an agent this deep may not start another; do it yourself")
    // Reservations go by id, so no two players' running tasks share one.
    val taken = all().mapTo(HashSet()) { it.id }
    val list = tasks.computeIfAbsent(owner) { mutableListOf() }
    synchronized(list) {
      list.removeIf(Task::done)
      if (list.size >= maxTasks()) {
        val mine = list.filter { parent != null && it.parent == parent }
        if (mine.isNotEmpty()) {
          throw ToolError("the player may run ${maxTasks()} automations at once, and your sub-agent ${mine.joinToString { "«${it.name}» (${it.id})" }} still runs: wait for its [sub-agent] report, then start this one, or do this part yourself")
        }
        throw ToolError("the player already has ${list.size} automations running, the most this server allows; wait for one to finish or cancel one")
      }
      val id = generateSequence(newId).first { candidate -> candidate !in taken && list.none { it.id == candidate } }
      val clean = name.trim().take(40).ifEmpty { kind.wire }
      return Task(id, owner, clean, kind, depth, parent, brief, now).also { list += it }
    }
  }

  /** The owner's running tasks, oldest first. */
  fun of(owner: UUID): List<Task> = tasks[owner]?.let { list -> synchronized(list) { list.filterNot(Task::done) } }.orEmpty()

  fun find(owner: UUID, id: String): Task? = of(owner).firstOrNull { it.id.equals(id.trim(), ignoreCase = true) }

  fun all(): List<Task> = tasks.values.flatMap { list -> synchronized(list) { list.filterNot(Task::done) } }

  /** The running sub-agents [task] started. */
  fun children(task: Task): List<Task> = of(task.owner).filter { it.parent == task.id }

  fun end(task: Task) {
    task.state = Task.State.DONE
    tasks[task.owner]?.let { list -> synchronized(list) { list.remove(task) } }
  }

  companion object {
    /** The main chat is depth 0; its agents 1; theirs 2, the last. */
    const val MAX_DEPTH = 2
  }
}

/**
 * Items an agent means to take later, so another agent doesn't plan with them: “the spider eye in this chest is
 * spoken for”. A reservation doesn't lock anything; when the items are gone at the agent's next step, the
 * agent stops and says who got in the way. [place] names a container (`world:x,y,z`) or a player's inventory.
 */
class Reservations {
  private data class Hold(val task: String, val place: String, val item: String, val count: Int)

  private val holds = ConcurrentLinkedQueue<Hold>()

  fun reserve(task: String, place: String, item: String, count: Int) {
    if (count > 0) holds += Hold(task, place, item, count)
  }

  /** How many of [item] at [place] other tasks than [except] hold. */
  fun held(place: String, item: String, except: String? = null): Int = holds.filter { it.place == place && it.item == item && it.task != except }.sumOf { it.count }

  /** Whether any task but [except] holds something at [place]. */
  fun heldAt(place: String, except: String? = null): Boolean = holds.any { it.place == place && it.task != except }

  /** What [task] holds, as place to item to count. */
  fun of(task: String): Map<String, Map<String, Int>> = holds.filter { it.task == task }
    .groupBy { it.place }.mapValues { (_, list) -> list.groupBy { it.item }.mapValues { (_, same) -> same.sumOf { it.count } } }

  /** Drops [count] of what [task] holds of [item] at [place], as it takes them. */
  fun take(task: String, place: String, item: String, count: Int) {
    var left = count
    for (hold in holds.filter { it.task == task && it.place == place && it.item == item }) {
      if (left <= 0) break
      holds.remove(hold)
      if (hold.count > left) holds += hold.copy(count = hold.count - left)
      left -= hold.count
    }
  }

  fun release(task: String) {
    holds.removeIf { it.task == task }
  }
}
