package chat.allay.core.tools

import kotlinx.serialization.Serializable

/**
 * A goal the player set through the assistant, shown as a boss bar. Progress counts only what happens after it was set:
 * statistics count from [baseline], pickups from events, places and levels from where the player is now.
 */
@Serializable
data class Goal(
  val id: String,
  val kind: String,
  val title: String,
  val target: Long,
  val color: String = "green",
  val style: String = "solid",
  val statistic: String? = null,
  val material: String? = null,
  val entity: String? = null,
  val advancement: String? = null,
  val x: Int? = null,
  val z: Int? = null,
  val radius: Int? = null,
  val biome: String? = null,
  val dimension: String? = null,
  /** The statistic's value, or the distance to the place, when the goal was set. */
  val baseline: Long = 0,
  /** Statistics in centimeters show in blocks: 100 raw units each. */
  val scale: Int = 1,
  val progress: Long = 0,
  val createdAt: Long,
) {
  val done get() = progress >= target

  /** The bar's fill, 0–1. */
  val fraction get() = if (target <= 0) 1.0 else (progress.toDouble() / target).coerceIn(0.0, 1.0)

  companion object {
    val KINDS = setOf("stat", "pickup", "level", "advancement", "reach", "biome", "dimension")
  }
}

/**
 * One player's goals, apart from Bukkit so tests can reach it. Items from the player's own death don't count
 * as picked up again: [deathDrops] holds what they dropped, and pickups use it up first.
 */
class GoalBook(initial: List<Goal>, private val max: () -> Int, private val newId: () -> String = ReminderBook::shortId) {
  private val goals = initial.toMutableList()
  private val deathDrops = HashMap<String, Long>()

  @Synchronized fun add(make: (String) -> Goal): Goal {
    val cap = max()
    if (goals.size >= cap) throw ToolError("this player already has $cap goals; cancel one first")
    val id = generateSequence(newId).first { candidate -> goals.none { it.id == candidate } }
    return make(id).also { goals += it }
  }

  @Synchronized fun all(): List<Goal> = goals.toList()

  @Synchronized fun remove(id: String): Goal? = goals.firstOrNull { it.id.equals(id, ignoreCase = true) }?.also { goals.remove(it) }

  @Synchronized fun clear(): Int = goals.size.also { goals.clear() }

  /** Sets a goal's progress; returns the goal as it is now, or null when it's gone. */
  @Synchronized fun progress(id: String, value: Long): Goal? {
    val index = goals.indexOfFirst { it.id == id }
    if (index < 0) return null
    if (goals[index].progress != value) goals[index] = goals[index].copy(progress = value.coerceAtLeast(0))
    return goals[index]
  }

  @Synchronized fun died(drops: Map<String, Int>) {
    drops.forEach { (item, count) -> deathDrops.merge(item, count.toLong(), Long::plus) }
  }

  /** Counts a pickup toward every pickup goal for this item; returns the goals that moved. */
  @Synchronized fun pickedUp(item: String, count: Int): List<Goal> {
    val owed = deathDrops[item] ?: 0
    val repaid = minOf(owed, count.toLong())
    if (repaid > 0) deathDrops[item] = owed - repaid
    val fresh = count - repaid
    if (fresh <= 0) return emptyList()
    return goals.withIndex().filter { (_, goal) -> goal.kind == "pickup" && goal.material == item }.map { (index, goal) ->
      goal.copy(progress = goal.progress + fresh).also { goals[index] = it }
    }
  }
}
