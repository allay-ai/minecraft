package chat.allay.core

import java.util.Locale
import kotlin.random.Random

/** What a run is doing right now, as the action bar shows it. */
enum class Phase { THINKING, ANSWERING }

/** Claude Code's spinner: the glyphs grow, then shrink back, so the motion never jumps. */
class Spinner(frames: String) {
  private val sequence: List<String> = frames.codePoints().toArray().map { String(Character.toChars(it)) }.let { forward ->
    if (forward.size < 3) forward else forward + forward.drop(1).dropLast(1).reversed()
  }.ifEmpty { listOf("*") }

  fun frame(tick: Long): String = sequence[(tick % sequence.size).toInt()]

  val size get() = sequence.size
}

/** Builds the status line from the reader's language; `status.line` holds the template. */
class StatusFormat(private val lang: Lang) {
  /** [input] is what the model has read so far, [output] what it has written, both in tokens. */
  fun line(spinner: String, verb: String, phase: Phase, elapsedSeconds: Long, input: Long, output: Long, reconnecting: Boolean = false): String = lang.text(
    "status.line",
    "spinner" to spinner,
    "verb" to when {
      reconnecting -> lang.text("status.reconnecting")
      phase == Phase.ANSWERING -> lang.text("status.answering")
      else -> verb
    },
    "seconds" to elapsedSeconds,
    "input" to compact(input),
    "output" to compact(output),
    // "1.2k" reads as many, whatever the exact number ends with.
    "tokens" to if (output >= 1_000) 1_000 else output,
  )

  companion object {
    fun compact(n: Long): String = when {
      n < 1_000 -> n.toString()
      n < 10_000 -> String.format(Locale.ROOT, "%.1fk", n / 1_000.0)
      else -> "${n / 1_000}k"
    }
  }
}

/** Picks the verb for a moment in time; it changes every few seconds, never twice in a row the same. */
class Verbs(private val verbs: List<String>, private val intervalMillis: Long, seed: Int) {
  private val order = verbs.indices.shuffled(Random(seed))

  fun at(elapsedMillis: Long): String {
    if (verbs.isEmpty()) return "…"
    val step = (elapsedMillis / intervalMillis.coerceAtLeast(500)).toInt()
    return verbs[order[step % order.size]]
  }
}
