package chat.allay.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger
import kotlin.math.roundToLong

/** The command a question came through, as the charts and the stats name it. */
enum class Asked(val key: String) {
  ASK("ask"), ASKALL("askall"), ASKX("askx");

  companion object {
    fun of(mode: Run.Mode, public: Boolean) = if (mode == Run.Mode.ASKX) ASKX else if (public) ASKALL else ASK
  }
}

/**
 * What Allay counts about its use: [tally] for bStats, drained every 30 minutes, and [book] for WorldAPI and Plan,
 * kept for good in `usage.json`. Both are safe from any thread.
 */
class Usage(folder: File, log: Logger) {
  val tally = Tally()
  val book = UsageBook(folder, log)

  /**
   * A run ended, however it went: [command] for a question (null for an agent's wake, which only spends), [answeredMillis]
   * from asking to the last word when an answer came. The backend's `usage` totals are cumulative, so a run counts once, here.
   */
  fun ran(player: UUID?, run: Run, command: Asked?, answeredMillis: Long?) {
    val input = run.input.get()
    val output = run.reportedOutput
    tally.ran(command?.key, input, output, run.cost, answeredMillis)
    book.ran(player, command?.key, input, output, run.cost, answeredMillis)
  }

  /** Allay picked a player's language: [source] is `ip`, `client`, `default`, or `choice` (the player's own). */
  fun picked(code: String, source: String) = tally.picked(code, source)

  /** The player's data was erased or their agreement withdrawn: their numbers go too. */
  fun forget(player: UUID) = book.forget(player)
}

/**
 * Numbers between two sends of bStats. Line charts add up across servers per period, so everything here is a count
 * that the chart's callback takes and zeroes; tokens and cents keep what doesn't make a whole unit for the next period.
 */
class Tally {
  private val questions = AtomicLong()
  private val commands = ConcurrentHashMap<String, AtomicLong>()
  private val input = AtomicLong()
  private val output = AtomicLong()
  /** Millionths of a US dollar. */
  private val micros = AtomicLong()
  private val answerTimes = ConcurrentHashMap<String, AtomicLong>()
  private val languages = ConcurrentHashMap<String, AtomicLong>()
  private val sources = ConcurrentHashMap<String, AtomicLong>()

  fun ran(command: String?, inputTokens: Long, outputTokens: Long, usd: Double, answeredMillis: Long?) {
    if (command != null) {
      questions.incrementAndGet()
      add(commands, command)
    }
    input.addAndGet(inputTokens.coerceAtLeast(0))
    output.addAndGet(outputTokens.coerceAtLeast(0))
    if (usd > 0) micros.addAndGet((usd * 1_000_000).roundToLong())
    if (answeredMillis != null) add(answerTimes, bucket(answeredMillis))
  }

  fun picked(code: String, source: String) {
    add(languages, code)
    add(sources, source)
  }

  fun questions(): Int = take(questions, 1)
  fun inputThousands(): Int = take(input, 1000)
  fun outputThousands(): Int = take(output, 1000)
  fun cents(): Int = take(micros, 10_000)
  fun commands(): Map<String, Int> = drain(commands)
  fun answerTimes(): Map<String, Int> = drain(answerTimes)
  fun languages(): Map<String, Int> = drain(languages)
  fun sources(): Map<String, Int> = drain(sources)

  companion object {
    /** The buckets of `answer_time`, in seconds from asking to the last word. */
    val BUCKETS = listOf("<5s", "5–15s", "15–30s", "30–60s", ">60s")

    fun bucket(millis: Long): String = when {
      millis < 5_000 -> BUCKETS[0]
      millis < 15_000 -> BUCKETS[1]
      millis < 30_000 -> BUCKETS[2]
      millis < 60_000 -> BUCKETS[3]
      else -> BUCKETS[4]
    }

    private fun add(counts: ConcurrentHashMap<String, AtomicLong>, key: String) {
      counts.computeIfAbsent(key) { AtomicLong() }.incrementAndGet()
    }

    /** The whole [unit]s in [counter], taken out; the rest stays for the next period. */
    private fun take(counter: AtomicLong, unit: Long): Int {
      while (true) {
        val now = counter.get()
        val whole = now / unit
        if (counter.compareAndSet(now, now - whole * unit)) return whole.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
      }
    }

    private fun drain(counts: ConcurrentHashMap<String, AtomicLong>): Map<String, Int> = buildMap {
      counts.forEach { (key, count) -> count.getAndSet(0).takeIf { it > 0 }?.let { put(key, it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) } }
    }
  }
}

/** All-time numbers for the server and for each player, in `usage.json` beside `quota.json`. */
class UsageBook(private val folder: File, private val log: Logger) {
  /**
   * Questions by command, tokens (output with reasoning), cost in US dollars, and answers: how many, their time in all,
   * and how many took each whole second (up to [MAX_SECONDS]), for the median.
   */
  @Serializable data class Totals(
    val questions: Map<String, Long> = emptyMap(),
    val input_tokens: Long = 0,
    val output_tokens: Long = 0,
    val cost: Double = 0.0,
    val answers: Long = 0,
    val answer_ms: Long = 0,
    val answer_seconds: Map<Int, Long> = emptyMap(),
  ) {
    val asked: Long get() = questions.values.sum()

    /** Seconds from asking to the last word, on average; null before the first answer. */
    val averageSeconds: Double? get() = if (answers == 0L) null else answer_ms / 1000.0 / answers

    /** The median of the same, to the second; null before the first answer. */
    val medianSeconds: Double? get() {
      val count = answer_seconds.values.sum()
      if (count == 0L) return null
      val sorted = answer_seconds.toSortedMap()
      fun at(index: Long): Int {
        var seen = 0L
        for ((second, n) in sorted) {
          seen += n
          if (index < seen) return second
        }
        return sorted.lastKey()
      }
      return (at((count - 1) / 2) + at(count / 2)) / 2.0
    }

    fun plus(command: String?, input: Long, output: Long, usd: Double, answeredMillis: Long?): Totals = copy(
      questions = if (command == null) questions else questions + (command to (questions[command] ?: 0) + 1),
      input_tokens = input_tokens + input.coerceAtLeast(0),
      output_tokens = output_tokens + output.coerceAtLeast(0),
      cost = cost + usd.coerceAtLeast(0.0),
      answers = answers + if (answeredMillis != null) 1 else 0,
      answer_ms = answer_ms + (answeredMillis ?: 0),
      answer_seconds = answeredMillis?.let { (it / 1000).coerceAtMost(MAX_SECONDS.toLong()).toInt() }
        ?.let { answer_seconds + (it to (answer_seconds[it] ?: 0) + 1) } ?: answer_seconds,
    )
  }

  @Serializable private data class Book(val total: Totals = Totals(), val players: Map<String, Totals> = emptyMap())

  private val file = File(folder, FILE)
  private val json = Json { ignoreUnknownKeys = true }
  private var data = load()

  @Synchronized fun ran(player: UUID?, command: String?, input: Long, output: Long, usd: Double, answeredMillis: Long?) {
    if (command == null && input <= 0 && output <= 0 && usd <= 0) return
    val players = if (player == null) data.players else {
      val id = player.toString()
      data.players + (id to (data.players[id] ?: Totals()).plus(command, input, output, usd, answeredMillis))
    }
    data = Book(data.total.plus(command, input, output, usd, answeredMillis), players)
    save()
  }

  @Synchronized fun total(): Totals = data.total

  @Synchronized fun player(id: UUID): Totals? = data.players[id.toString()]

  /** Every player with numbers, by UUID. */
  @Synchronized fun players(): Map<UUID, Totals> = data.players.mapNotNull { (id, totals) -> runCatching { UUID.fromString(id) }.getOrNull()?.let { it to totals } }.toMap()

  @Synchronized fun forget(player: UUID) {
    if (data.players.containsKey(player.toString())) {
      data = data.copy(players = data.players - player.toString())
      save()
    }
  }

  @Synchronized private fun save() = runCatching {
    folder.mkdirs()
    val temp = File(folder, "$FILE.tmp")
    temp.writeText(json.encodeToString(data))
    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
  }.onFailure { log.warning("Couldn't save $FILE: ${it.message}") }

  private fun load(): Book = if (!file.exists()) Book() else runCatching { json.decodeFromString<Book>(file.readText()) }.getOrElse {
    log.warning("Couldn't read $FILE: ${it.message}; counting from zero")
    Book()
  }

  companion object {
    const val FILE = "usage.json"
    /** Longer answers count as this many seconds in the median. */
    const val MAX_SECONDS = 3600
  }
}
