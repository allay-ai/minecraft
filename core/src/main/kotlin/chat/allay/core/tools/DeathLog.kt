package chat.allay.core.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/** One death as the game told it: where, when, what killed the player, and what they lost. */
@Serializable
data class Death(
  val at: Long,
  val world: String,
  val dimension: String,
  val x: Int,
  val y: Int,
  val z: Int,
  /** The damage type, like minecraft:fall. */
  val cause: String? = null,
  /** The type of whatever caused it, like minecraft:creeper or minecraft:player. */
  val killer: String? = null,
  /** A killer player's name, or a mob's custom name, which a player wrote. */
  val killerName: String? = null,
  val message: String? = null,
  val level: Int = 0,
  val keptInventory: Boolean = false,
  val drops: Map<String, Int> = emptyMap(),
  val experience: Int = 0,
)

/** One player's deaths, oldest first, apart from Bukkit so tests can reach it. The cap is read on every add, so `/allay reload` applies it. */
class DeathHistory(initial: List<Death>, private val max: () -> Int) {
  private val deaths = initial.toMutableList()

  @Synchronized fun add(death: Death) {
    deaths += death
    val over = deaths.size - max()
    if (over > 0) deaths.subList(0, over).clear()
  }

  @Synchronized fun all(): List<Death> = deaths.toList()

  @Synchronized fun latest(): Death? = deaths.lastOrNull()

  /** Page [page] of [size], counted from 1, newest first; an error names the page count when there's no such page. */
  @Synchronized fun page(page: Int, size: Int = PAGE): Page {
    val pages = (deaths.size + size - 1) / size
    if (page != 1 && pages == 0) throw ToolError("no deaths are recorded for this player yet")
    if (page < 1 || (page > pages && page != 1)) throw ToolError("there ${if (pages == 1) "is 1 page" else "are $pages pages"} of deaths; ask for 1–$pages")
    val newest = deaths.asReversed()
    return Page(newest.drop((page - 1) * size).take(size), page, pages, deaths.size)
  }

  class Page(val deaths: List<Death>, val page: Int, val pages: Int, val total: Int)

  companion object {
    const val PAGE = 10
  }
}

/** Deaths on disk: `deaths/<uuid>.json` in the plugin folder, written atomically so a crash never leaves half a file. */
class DeathLog(dataFolder: File, private val log: Logger, private val max: () -> Int) {
  private val folder = File(dataFolder, "deaths")
  private val histories = ConcurrentHashMap<UUID, DeathHistory>()
  private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

  fun of(uuid: UUID): DeathHistory = histories.computeIfAbsent(uuid) { DeathHistory(load(it), max) }

  fun record(uuid: UUID, death: Death) {
    of(uuid).add(death)
    save(uuid)
  }

  /** Erasure: the player's deaths go from memory and disk. */
  fun forget(uuid: UUID): Int {
    val count = of(uuid).all().size
    histories.remove(uuid)
    File(folder, "$uuid.json").delete()
    return count
  }

  @Synchronized private fun save(uuid: UUID) {
    val history = histories[uuid] ?: return
    folder.mkdirs()
    val target = File(folder, "$uuid.json")
    val temp = File(folder, "$uuid.json.tmp")
    temp.writeText(json.encodeToString(history.all()))
    Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
  }

  private fun load(uuid: UUID): List<Death> {
    val file = File(folder, "$uuid.json")
    if (!file.exists()) return emptyList()
    return try {
      json.decodeFromString<List<Death>>(file.readText())
    } catch (error: Exception) {
      // Keep the broken file for a human and start clean, rather than lose every death after it.
      log.warning("Couldn't read deaths of $uuid: ${error.message}; keeping it as $uuid.json.broken")
      file.renameTo(File(folder, "$uuid.json.broken"))
      emptyList()
    }
  }
}
