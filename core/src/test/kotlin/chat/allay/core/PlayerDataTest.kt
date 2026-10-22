package chat.allay.core

import chat.allay.core.tools.Death
import chat.allay.core.tools.Goal
import chat.allay.core.tools.TimeZones
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerDataTest {
  private val log = Logger.getAnonymousLogger()
  private val player = UUID.fromString("00000000-0000-0000-0000-000000000001")
  private val stranger = UUID.fromString("00000000-0000-0000-0000-000000000002")
  private val folder = Files.createTempDirectory("allay-data").toFile()

  /** The backend's store; [status] makes every call fail with it. */
  private class FakeBackend(var status: Int? = null) : PlayerData.Backend {
    val states = mutableMapOf<String, PlayerState>()
    val deaths = mutableListOf<Pair<String, Death>>()

    private fun fail() = status?.let { throw ApiErrorException(ApiError("DOWN", "down", it)) }

    override suspend fun state(userId: String): PlayerState? {
      fail()
      return states[userId]
    }

    override suspend fun saveState(userId: String, state: PlayerState) {
      fail()
      states[userId] = state
    }

    override suspend fun death(userId: String, death: Death) {
      fail()
      deaths += userId to death
    }
  }

  private fun data(backend: PlayerData.Backend, spool: Spool? = null, online: Boolean = true) = PlayerData(
    backend = backend,
    scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob()),
    spool = spool,
    log = log,
    agreed = { it == player },
    agreedNow = { it == player },
    online = { online },
    debounceMs = 60_000,
  )

  private fun death(at: Long) = Death(at, "world", "overworld", 1, 64, 2, cause = "minecraft:fall")

  @Test fun `settings and time zones live in the state and go out when the player leaves`() = runBlocking {
    val backend = FakeBackend()
    val data = data(backend, online = false)
    val settings = PlayerSettings(data)
    val zones = TimeZones(data) { "UTC" }
    settings.update(player) { it.copy(chat_mode = true) }
    zones.set(player, "Europe/Moscow")
    assertTrue(settings.of(player).chat_mode)
    assertEquals("Europe/Moscow", zones.of(player))
    data.quit(player).join()
    assertEquals(PlayerState("Europe/Moscow", Settings(chat_mode = true)), backend.states[player.toString()])
    // Gone from memory once the backend has it.
    assertEquals(Settings(), settings.of(player))
    assertEquals("UTC", zones.of(player))
  }

  @Test fun `a player who didn't agree sends nothing and leaves nothing behind`() = runBlocking {
    val backend = FakeBackend()
    val data = data(backend, online = false)
    PlayerSettings(data).update(stranger) { it.copy(language = "ru") }
    data.died(stranger, death(1))
    data.quit(stranger).join()
    assertTrue(backend.states.isEmpty())
    assertTrue(backend.deaths.isEmpty())
    assertNull(data.of(stranger).settings)
  }

  @Test fun `the backend's copy comes in on join and what changed meanwhile wins`() = runBlocking {
    val backend = FakeBackend()
    val goal = Goal(id = "g1", kind = "level", title = "30", target = 30, createdAt = 0)
    backend.states[player.toString()] = PlayerState("Asia/Tokyo", Settings(hints = false), listOf(goal))
    val data = data(backend)
    var loaded: PlayerState? = null
    data.loaded = { _, state -> loaded = state }
    PlayerSettings(data).update(player) { it.copy(chat_mode = true) }
    data.join(player).join()
    // The local change wins its field; the rest comes from the backend.
    assertEquals(PlayerState("Asia/Tokyo", Settings(chat_mode = true), listOf(goal)), loaded)
  }

  @Test fun `deaths go one by one, and a backend before 6_9 stops the sending`() = runBlocking {
    val backend = FakeBackend()
    val data = data(backend)
    data.died(player, death(1))
    data.died(player, death(2))
    assertEquals(listOf(1L, 2L), backend.deaths.map { it.second.at })
    val old = FakeBackend(status = 404)
    val stale = data(old)
    stale.died(player, death(3))
    assertTrue(stale.old)
  }

  @Test fun `what can't be sent at shutdown waits sealed in the spool`() = runBlocking {
    val file = folder.resolve(Spool.FILE)
    val down = FakeBackend(status = 503)
    val spool = Spool(file, { "lic.secret-value" }, log)
    val first = data(down, spool)
    PlayerSettings(first).update(player) { it.copy(hints = false) }
    PlayerSettings(first).update(stranger) { it.copy(hints = false) }
    first.died(player, death(7))
    first.stop(1_000)
    assertTrue(file.isFile)
    // Sealed: nothing readable inside.
    assertFalse(file.readText(Charsets.ISO_8859_1).contains("hints"))
    val up = FakeBackend()
    data(up, Spool(file, { "lic.secret-value" }, log)).start()
    assertEquals(Settings(hints = false), up.states[player.toString()]?.settings)
    assertFalse(up.states.containsKey(stranger.toString()))
    assertEquals(listOf(7L), up.deaths.map { it.second.at })
    assertFalse(file.exists())
  }

  @Test fun `a spool under another key is dropped`() {
    val file = folder.resolve("other.bin")
    Spool(file, { "one.key" }, log).write(Spool.Pending(mapOf(player.toString() to PlayerState("UTC"))))
    assertNull(Spool(file, { "two.key" }, log).read())
    assertFalse(file.exists())
  }
}
