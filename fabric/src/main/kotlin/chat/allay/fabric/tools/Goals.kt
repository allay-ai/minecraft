package chat.allay.fabric.tools

import chat.allay.core.Locales
import chat.allay.core.PlayerData
import chat.allay.core.PlayerState
import chat.allay.core.Scheduler
import chat.allay.core.tools.Goal
import chat.allay.core.tools.GoalBook

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Stands in until goals are ported: the book without bars or progress. */
class Goals(private val scheduler: Scheduler, private val locales: Locales, private val data: PlayerData, private val max: () -> Int) {
  private val books = ConcurrentHashMap<UUID, GoalBook>()

  fun of(uuid: UUID): GoalBook = books.computeIfAbsent(uuid) { GoalBook(data.of(it).goals.orEmpty(), max) }
  fun count(uuid: UUID): Int = books[uuid]?.all()?.size ?: 0
  fun snapshot(uuid: UUID): List<Goal> = books[uuid]?.all() ?: data.of(uuid).goals.orEmpty()
  fun loaded(uuid: UUID, state: PlayerState) {}
  fun start() {}
  fun stop() {}
  fun forget(uuid: UUID) {
    books.remove(uuid)
  }

  fun save(uuid: UUID) {}
}
