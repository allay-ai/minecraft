package chat.allay.core

import okhttp3.Call
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * One question in flight, or one wake of an automation agent ([task]): what the display shows and what cancelling
 * stops. Touched from several threads.
 */
class Run(val asker: Asker, val mode: Mode, val lang: Lang, verbSeconds: Long, val public: Boolean = false, val task: chat.allay.core.tasks.Task? = null) {
  enum class Mode(val wire: String) { ASK("ask"), ASKX("askx"), TASK("task") }

  val startedAt = System.currentTimeMillis()
  val verbs = Verbs(lang.list(if (mode == Mode.ASK) "status.verbs" else "status.askx_verbs"), verbSeconds * 1000, startedAt.toInt())

  @Volatile var phase = Phase.THINKING
  @Volatile var runId: String? = null
  @Volatile var call: Call? = null
  @Volatile var cancelled = false
  /** Put the chat mode tip under the answer. */
  @Volatile var tip = false
  /** The model the asker's tier picks (`models` in config.yml); null for the default. */
  @Volatile var model: String? = null
  /** What the run cost in US dollars so far, from the backend's `usage` events; quotas count it. */
  @Volatile var cost = 0.0

  /** Tokens streamed so far, estimated from the deltas. */
  private val estimated = AtomicLong()

  /** Totals from the latest `usage` event; the backend sends one after every model step. */
  val input = AtomicLong()
  private val reported = AtomicLong()
  private val answer = StringBuilder()

  /** Other players as the model reads them in this run. */
  val others = OtherPlayers()

  /** Buttons the tools asked for; they go under the answer. */
  val buttons: MutableList<chat.allay.core.tools.Button> = java.util.Collections.synchronizedList(mutableListOf())

  /** Calls whose `⏺` line is already in chat, so a `tool_status` and its `tool_call` don't print twice. */
  val announced: MutableSet<String> = ConcurrentHashMap.newKeySet()

  val elapsedMillis get() = System.currentTimeMillis() - startedAt

  fun addReasoning(delta: String) {
    estimated.addAndGet(estimate(delta))
  }

  fun addText(delta: String) = synchronized(answer) {
    answer.append(delta)
    phase = Phase.ANSWERING
    estimated.addAndGet(estimate(delta))
  }

  fun usage(inputTokens: Long, outputTokens: Long) {
    input.set(inputTokens)
    reported.set(outputTokens)
  }

  /** Tokens written so far: the live estimate until the backend's count catches up or passes it. */
  val output get() = maxOf(estimated.get(), reported.get())

  fun answer(): String = synchronized(answer) { answer.toString() }

  fun cancel() {
    cancelled = true
    call?.cancel()
  }

  private companion object {
    /** About four characters a token: enough for a live counter until the real usage arrives. */
    fun estimate(text: String) = ((text.length + 3) / 4).toLong()
  }
}
