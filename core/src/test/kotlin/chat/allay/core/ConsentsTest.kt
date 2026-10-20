package chat.allay.core

import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConsentsTest {
  private val player = UUID.fromString("00000000-0000-0000-0000-000000000001")

  /** The backend's store, or a backend that answers [status] to everything. */
  private class FakeBackend(var status: Int? = null) : Consents.Backend {
    val versions = mutableMapOf<String, Int>()
    var reads = 0

    private fun fail() = status?.let { throw ApiErrorException(ApiError("NOT_FOUND", "Not found", it)) }

    override suspend fun consent(userId: String): Int? {
      reads++
      fail()
      return versions[userId]
    }

    override suspend fun giveConsent(userId: String, version: Int) {
      fail()
      versions[userId] = version
    }

    override suspend fun withdrawConsent(userId: String): Boolean {
      fail()
      return versions.remove(userId) != null
    }
  }

  @Test fun `a player is asked until they agree, and then not for an hour`() = runBlocking {
    var now = 0L
    val backend = FakeBackend()
    val consents = Consents(backend) { now }
    assertFalse(consents.known(player))
    assertEquals(Consents.State.MISSING, consents.check(player))
    consents.give(player)
    assertEquals(mapOf(player.toString() to Consents.VERSION), backend.versions)
    assertTrue(consents.known(player))
    now += Consents.RECHECK_MS
    // An hour on, the backend says again: the admin panel may have erased the player since.
    assertFalse(consents.known(player))
    backend.versions.clear()
    assertEquals(Consents.State.MISSING, consents.check(player))
  }

  @Test fun `an older notice's agreement doesn't count`() = runBlocking {
    val backend = FakeBackend().apply { versions[player.toString()] = Consents.VERSION - 1 }
    assertEquals(Consents.State.MISSING, Consents(backend).check(player))
    backend.versions[player.toString()] = Consents.VERSION
    assertEquals(Consents.State.GIVEN, Consents(backend).check(player))
  }

  @Test fun `withdrawing and erasing ask again`() = runBlocking {
    val backend = FakeBackend()
    val consents = Consents(backend)
    consents.give(player)
    assertTrue(consents.withdraw(player))
    assertFalse(consents.known(player))
    assertFalse(consents.withdraw(player))
    consents.give(player)
    consents.forget(player)
    assertFalse(consents.known(player))
  }

  @Test fun `a backend before 6_1 asks nobody, and other errors go through`() = runBlocking {
    val old = Consents(FakeBackend(status = 404))
    assertEquals(Consents.State.UNSUPPORTED, old.check(player))
    assertTrue(old.known(player))
    val down = Consents(FakeBackend(status = 503))
    assertFailsWith<ApiErrorException> { down.check(player) }
    assertFalse(down.known(player))
  }
}
