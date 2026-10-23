package chat.allay.core.tools

import chat.allay.core.Cue
import chat.allay.core.Effects
import chat.allay.core.Locales
import chat.allay.core.Platform
import chat.allay.core.PlayerAudience
import chat.allay.core.send
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One player's pending reminders, apart from the platform so tests can reach it. The cap is read on every add, so `/allay reload` applies it. */
class ReminderBook(private val max: () -> Int, private val newId: () -> String = ::shortId) {
  class Reminder(val id: String, val text: String, val dueAt: Long, val cancel: () -> Unit)

  private val reminders = mutableListOf<Reminder>()

  @Synchronized fun add(text: String, dueAt: Long, schedule: (String) -> () -> Unit): Reminder {
    val cap = max()
    if (reminders.size >= cap) throw ToolError("this player already has $cap reminders pending; cancel one first")
    val id = generateSequence(newId).first { candidate -> reminders.none { it.id == candidate } }
    return Reminder(id, text, dueAt, schedule(id)).also { reminders += it }
  }

  /** Soonest first. */
  @Synchronized fun list(): List<Reminder> = reminders.sortedBy { it.dueAt }

  /** Drops a reminder that just fired, without cancelling its timer. */
  @Synchronized fun fired(id: String) {
    reminders.removeIf { it.id == id }
  }

  @Synchronized fun cancel(id: String): Int {
    val reminder = reminders.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: return 0
    reminders.remove(reminder)
    reminder.cancel()
    return 1
  }

  @Synchronized fun cancelAll(): Int {
    val count = reminders.size
    reminders.forEach { it.cancel() }
    reminders.clear()
    return count
  }

  companion object {
    private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"

    /** Four characters without look-alikes (l, 1, o, 0, i): easy to read back from chat. */
    fun shortId() = (1..4).map { ALPHABET.random() }.joinToString("")
  }
}

/** Reminders the assistant set for players. They live in memory: a restart or the player leaving drops them. */
class Reminders(private val platform: Platform, private val locales: Locales, private val effects: () -> Effects, private val maxPending: () -> Int) {
  private val books = ConcurrentHashMap<UUID, ReminderBook>()

  fun of(player: UUID): ReminderBook = books.computeIfAbsent(player) { ReminderBook(maxPending) }

  fun add(player: PlayerAudience, minutes: Int, text: String): ReminderBook.Reminder {
    val book = of(player.uuid)
    return book.add(text, System.currentTimeMillis() + minutes * 60_000L) { id ->
      val task = platform.scheduler.later(minutes * 1_200L) {
        book.fired(id)
        if (!player.online) return@later
        locales.of(player).send(player, "messages.reminder", "text" to text)
        effects().play(player, Cue.REMINDER, 0.8f, 1.2f)
      }
      task::close
    }
  }

  /** How many reminders a player has waiting; they live only while the player is online. Safe from any thread. */
  fun pending(uuid: UUID): Int = books[uuid]?.list()?.size ?: 0

  fun cancelAll() {
    books.values.forEach(ReminderBook::cancelAll)
    books.clear()
  }

  fun quit(player: UUID) {
    books.remove(player)?.cancelAll()
  }
}
