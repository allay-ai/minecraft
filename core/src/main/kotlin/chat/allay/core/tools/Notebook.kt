package chat.allay.core.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/** A note the player asked to keep: “a fletcher selling arrows here”, with where they stood when they said it. */
@Serializable
data class Note(
  val id: String,
  val text: String,
  val x: Int? = null,
  val y: Int? = null,
  val z: Int? = null,
  val world: String? = null,
  val dimension: String? = null,
  val createdAt: Long,
)

/** One player's notes, apart from Bukkit so tests can reach it. The cap is read on every add, so `/allay reload` applies it. */
class NoteBook(initial: List<Note>, private val max: () -> Int, private val newId: () -> String = ReminderBook::shortId) {
  private val notes = initial.toMutableList()

  @Synchronized fun add(make: (String) -> Note): Note {
    val cap = max()
    if (notes.size >= cap) throw ToolError("this player already has $cap notes; delete one first")
    val id = generateSequence(newId).first { candidate -> notes.none { it.id == candidate } }
    return make(id).also { notes += it }
  }

  @Synchronized fun all(): List<Note> = notes.toList()

  @Synchronized fun delete(id: String): Boolean = notes.removeIf { it.id.equals(id, ignoreCase = true) }

  /**
   * Notes whose text contains the query, case-insensitively. When nothing matches, every note comes
   * back, so the model can still match by meaning (“arrows” against “fletcher”). Nearest first.
   */
  @Synchronized fun find(query: String?, distance: (Note) -> Double?, limit: Int): Found {
    val matching = query?.lowercase()?.let { q -> notes.filter { q in it.text.lowercase() } }
    val matched = matching != null && matching.isNotEmpty()
    val pool = if (matched) matching!! else notes
    // Notes in this world by distance, then the rest newest first.
    val ordered = pool.sortedWith(compareBy<Note> { distance(it) ?: Double.MAX_VALUE }.thenByDescending { it.createdAt })
    return Found(ordered.take(limit), matched = query == null || matched, total = notes.size)
  }

  class Found(val notes: List<Note>, val matched: Boolean, val total: Int)
}

/** The notebook on disk: `notes/<uuid>.json` in the plugin folder, written atomically so a crash never leaves half a file. */
class Notes(dataFolder: File, private val log: Logger, private val max: () -> Int) {
  private val folder = File(dataFolder, "notes")
  private val books = ConcurrentHashMap<UUID, NoteBook>()
  private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

  fun of(uuid: UUID): NoteBook = books.computeIfAbsent(uuid) { NoteBook(load(it), max) }

  /** How many notes a player keeps, read from disk without loading the notebook when it isn't open; safe from any thread. */
  fun count(uuid: UUID): Int = books[uuid]?.all()?.size
    ?: runCatching { json.decodeFromString<List<Note>>(File(folder, "$uuid.json").readText()).size }.getOrDefault(0)

  /** Erasure: the player's notebook goes from memory and disk. */
  fun forget(uuid: UUID) {
    books.remove(uuid)
    File(folder, "$uuid.json").delete()
    File(folder, "$uuid.json.broken").delete()
  }

  fun save(uuid: UUID) {
    val book = books[uuid] ?: return
    folder.mkdirs()
    val target = File(folder, "$uuid.json")
    val temp = File(folder, "$uuid.json.tmp")
    temp.writeText(json.encodeToString(book.all()))
    Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
  }

  private fun load(uuid: UUID): List<Note> {
    val file = File(folder, "$uuid.json")
    if (!file.exists()) return emptyList()
    return try {
      json.decodeFromString<List<Note>>(file.readText())
    } catch (error: Exception) {
      // Keep the broken file for a human and start clean, rather than fail every note call.
      log.warning("Couldn't read notes of $uuid: ${error.message}; keeping it as $uuid.json.broken")
      file.renameTo(File(folder, "$uuid.json.broken"))
      emptyList()
    }
  }
}

/**
 * `/allay memory <text>` without the assistant. Two or three whole numbers in the text are a place, so it goes to the
 * notebook; the dimension is the one the text names (“в аду”, “the end”), or none when it names none or several.
 */
object QuickNote {
  class Spot(val x: Int, val y: Int?, val z: Int, val dimension: String?)

  // A minus may come as a hyphen, a minus sign, or a dash, as phones and chats type it.
  private val NUMBER = Regex("""(?<![\p{L}\d.,])[-−–]?\d{1,8}(?![\p{L}\d]|[.,]\d)""")
  private val NAMES = mapOf(
    "the_nether" to Regex("""(?iU)\b(ад|ада|аду|аде|адом|незер\w*|нижн\w*|nether\w*|hell)\b"""),
    "the_end" to Regex("""(?iU)\b(энд\w*|енд\w*|the end)\b"""),
    "overworld" to Regex("""(?iU)\b(обычн\w*|верхн\w*|overworld|oberwelt)\b"""),
  )

  /** The place in [text], or null when it holds no coordinates. */
  fun spot(text: String): Spot? {
    val numbers = NUMBER.findAll(text).map { it.value.replace('−', '-').replace('–', '-').toInt() }.toList()
    val dimension = NAMES.filterValues { it.containsMatchIn(text) }.keys.singleOrNull()
    return when (numbers.size) {
      3 -> Spot(numbers[0], numbers[1], numbers[2], dimension)
      2 -> Spot(numbers[0], null, numbers[1], dimension)
      else -> null
    }
  }
}
