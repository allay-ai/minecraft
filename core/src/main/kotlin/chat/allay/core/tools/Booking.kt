package chat.allay.core.tools

import chat.allay.core.tasks.Reservations

/**
 * What one call of a tool reserves for [task], so a refusal gives back only that: `Reservations.release` would also
 * drop what a running job of the same task still holds for its refills. Opened before the call reserves anything;
 * [undo] drops whatever the task holds beyond what it held then, place by place and item by item.
 */
class Booking(private val holds: Reservations, private val task: String) {
  private val before = holds.of(task)

  fun undo() {
    holds.of(task).forEach { (place, items) ->
      items.forEach { (item, count) ->
        val extra = count - (before[place]?.get(item) ?: 0)
        if (extra > 0) holds.take(task, place, item, extra)
      }
    }
  }
}
