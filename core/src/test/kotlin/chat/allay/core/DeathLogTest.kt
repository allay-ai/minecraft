package chat.allay.core

import chat.allay.core.tools.Death
import chat.allay.core.tools.DeathHistory
import chat.allay.core.tools.DeathLog
import chat.allay.core.tools.ToolError
import java.nio.file.Files
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeathLogTest {
  private fun death(at: Long) = Death(at, "world", "overworld", at.toInt(), 64, 0, cause = "minecraft:fall")

  @Test fun `the cap drops the oldest deaths`() {
    val history = DeathHistory(emptyList()) { 3 }
    (1L..5L).forEach { history.add(death(it)) }
    assertEquals(listOf(3L, 4L, 5L), history.all().map { it.at })
    assertEquals(5L, history.latest()?.at)
  }

  @Test fun `pages go newest first`() {
    val history = DeathHistory(emptyList()) { 100 }
    (1L..23L).forEach { history.add(death(it)) }
    val first = history.page(1)
    assertEquals(3, first.pages)
    assertEquals(23, first.total)
    assertEquals((23L downTo 14L).toList(), first.deaths.map { it.at })
    assertEquals(listOf(3L, 2L, 1L), history.page(3).deaths.map { it.at })
  }

  @Test fun `a page past the end names the page count`() {
    val history = DeathHistory(listOf(death(1))) { 100 }
    val error = assertFailsWith<ToolError> { history.page(2) }
    assertTrue("1 page" in error.message!!)
  }

  @Test fun `no deaths is one empty page`() {
    val page = DeathHistory(emptyList()) { 100 }.page(1)
    assertEquals(0, page.total)
    assertTrue(page.deaths.isEmpty())
  }

  @Test fun `deaths survive a restart and erasure removes them`() {
    val folder = Files.createTempDirectory("deaths").toFile()
    val uuid = UUID.randomUUID()
    DeathLog(folder, Logger.getAnonymousLogger()) { 100 }.record(uuid, death(7).copy(killer = "minecraft:zombie", drops = mapOf("dirt" to 3)))
    val again = DeathLog(folder, Logger.getAnonymousLogger()) { 100 }
    assertEquals(mapOf("dirt" to 3), again.of(uuid).latest()?.drops)
    assertEquals(1, again.forget(uuid))
    assertNull(DeathLog(folder, Logger.getAnonymousLogger()) { 100 }.of(uuid).latest())
  }

  @Test fun `a broken file is kept aside`() {
    val folder = Files.createTempDirectory("deaths").toFile()
    val uuid = UUID.randomUUID()
    folder.resolve("deaths").apply { mkdirs() }.resolve("$uuid.json").writeText("{nope")
    assertNull(DeathLog(folder, Logger.getAnonymousLogger()) { 100 }.of(uuid).latest())
    assertTrue(folder.resolve("deaths/$uuid.json.broken").exists())
  }
}
