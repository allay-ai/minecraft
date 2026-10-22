package chat.allay.core

import chat.allay.core.tools.Goal
import chat.allay.core.tools.GoalFiles
import chat.allay.core.tools.Note
import chat.allay.core.tools.Notes
import chat.allay.core.tools.TimeZones
import java.nio.file.Files
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `/allay privacy erase` forgets what the plugin keeps, and only for that player. */
class ErasureTest {
  private val log = Logger.getAnonymousLogger()
  private val folder = Files.createTempDirectory("erasure").toFile()
  private val gone = UUID.randomUUID()
  private val stays = UUID.randomUUID()

  @Test fun `settings start over and survive for others`() {
    val settings = PlayerSettings(folder, log)
    settings.update(gone) { it.copy(chat_mode = true, full_privacy = true) }
    settings.update(stays) { it.copy(language = "ru") }
    settings.forget(gone)
    val reloaded = PlayerSettings(folder, log)
    assertEquals(Settings(), reloaded.of(gone))
    assertEquals("ru", reloaded.of(stays).language)
  }

  @Test fun `a time zone goes back to the default`() {
    val zones = TimeZones(folder, log) { "UTC" }
    zones.set(gone, "Europe/Moscow")
    zones.set(stays, "Asia/Tokyo")
    assertEquals("Europe/Moscow", zones.chosen(gone))
    zones.forget(gone)
    val reloaded = TimeZones(folder, log) { "UTC" }
    assertNull(reloaded.chosen(gone))
    assertEquals("UTC", reloaded.of(gone))
    assertEquals("Asia/Tokyo", reloaded.of(stays))
  }

  @Test fun `goals and notes files go, with broken copies`() {
    val goals = GoalFiles(folder, log)
    goals.save(gone, listOf(Goal(id = "g1", kind = "collect", title = "Diamonds", target = 5, createdAt = 0)))
    goals.save(stays, listOf(Goal(id = "g2", kind = "collect", title = "Iron", target = 5, createdAt = 0)))
    folder.resolve("goals/$gone.json.broken").writeText("{")
    goals.delete(gone)
    assertTrue(goals.peek(gone).isEmpty())
    assertFalse(folder.resolve("goals/$gone.json.broken").exists())
    assertEquals(1, goals.peek(stays).size)

    val notes = Notes(folder, log) { 10 }
    notes.of(gone).add { id -> Note(id, "base", createdAt = 0) }
    notes.save(gone)
    notes.forget(gone)
    assertEquals(0, notes.count(gone))
    assertTrue(Notes(folder, log) { 10 }.of(gone).all().isEmpty())
  }
}
