package chat.allay.core

import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConsentsTest {
  private val player = UUID.fromString("00000000-0000-0000-0000-000000000001")
  private var notice = listOf("Allay is an AI helper.", "The backend keeps your data in the EU.", "Allay keeps your conversation for 30 days.")

  /** The backend's store, or a backend that answers [status] to everything; [revision] is null for one before 6.9. */
  private class FakeBackend(var status: Int? = null, var revision: String? = "2026-10-23") : Consents.Backend {
    val given = mutableMapOf<String, Consents.Given>()

    private fun fail() = status?.let { throw ApiErrorException(ApiError("NOT_FOUND", "Not found", it)) }

    override suspend fun consent(userId: String): Consents.Answer {
      fail()
      return Consents.Answer(given[userId], revision)
    }

    override suspend fun giveConsent(userId: String, version: Int, hash: String?, ageConfirmed: Boolean?) {
      fail()
      given[userId] = Consents.Given(version, hash, "2026-10-20T10:00:00Z", ageConfirmed)
    }

    override suspend fun withdrawConsent(userId: String): Boolean {
      fail()
      return given.remove(userId) != null
    }
  }

  private fun consents(backend: Consents.Backend, now: () -> Long = { 0L }) = Consents(backend, { notice }, now = now)

  @Test fun `a player is asked until they agree, and then not for an hour`() = runBlocking {
    var now = 0L
    val backend = FakeBackend()
    val consents = consents(backend) { now }
    assertFalse(consents.known(player))
    assertEquals(Consents.State.MISSING, consents.check(player))
    consents.give(player)
    assertEquals(Consents.VERSION, backend.given.getValue(player.toString()).version)
    assertEquals(consents.hash("2026-10-23"), backend.given.getValue(player.toString()).hash)
    assertTrue(consents.known(player))
    now += Consents.RECHECK_MS
    // An hour on, the backend says again: the admin panel may have erased the player since.
    assertFalse(consents.known(player))
    backend.given.clear()
    assertEquals(Consents.State.MISSING, consents.check(player))
  }

  @Test fun `the hash covers the notice and the revision, joined by new lines`() {
    val consents = consents(FakeBackend())
    assertEquals(Consents.digest(notice + "r1"), consents.hash("r1"))
    assertEquals(64, consents.hash("r1").length)
    assertTrue(consents.hash("r1") != consents.hash("r2"))
  }

  @Test fun `a changed notice or policy asks again and says since when`() = runBlocking {
    val backend = FakeBackend()
    val consents = consents(backend)
    consents.check(player)
    consents.give(player)
    assertEquals(Consents.State.GIVEN, consents.check(player))
    // The backend's policy moved on.
    backend.revision = "2026-12-01"
    assertEquals(Consents.State.CHANGED, consents.check(player))
    assertFalse(consents.known(player))
    assertEquals("2026-10-20T10:00:00Z", consents.previous(player))
    consents.give(player)
    assertNull(consents.previous(player))
    assertEquals(Consents.State.GIVEN, consents.check(player))
    // The English notice changed with a new release.
    notice = notice + "Traces stay 30 days."
    assertEquals(Consents.State.CHANGED, consents.check(player))
  }

  @Test fun `an agreement without a hash or of an older version counts as changed`() = runBlocking {
    val backend = FakeBackend().apply { given[player.toString()] = Consents.Given(Consents.VERSION, null, "2026-10-01T00:00:00Z") }
    assertEquals(Consents.State.CHANGED, consents(backend).check(player))
    backend.given[player.toString()] = Consents.Given(Consents.VERSION - 1, consents(backend).hash("2026-10-23"))
    assertEquals(Consents.State.CHANGED, consents(backend).check(player))
  }

  @Test fun `a backend before 6_9 decides by the version alone`() = runBlocking {
    val backend = FakeBackend(revision = null)
    val consents = consents(backend)
    assertEquals(Consents.State.MISSING, consents.check(player))
    consents.give(player)
    assertNull(backend.given.getValue(player.toString()).hash)
    assertEquals(Consents.State.GIVEN, consents.check(player))
  }

  @Test fun `a US player confirms their age with the agreement, and without it is asked again`() = runBlocking {
    val backend = FakeBackend()
    val american = Consents(backend, { notice }, { true })
    assertTrue(american.asksAge(player))
    assertEquals(Consents.State.MISSING, american.check(player))
    american.give(player)
    // Agreed, but no age: the question comes back.
    assertEquals(Consents.State.AGE, american.check(player))
    assertFalse(american.known(player))
    american.give(player, ageConfirmed = true)
    assertEquals(true, backend.given.getValue(player.toString()).ageConfirmed)
    assertEquals(Consents.State.GIVEN, american.check(player))
    // Elsewhere nobody is asked, and nothing about age is sent.
    val other = consents(FakeBackend())
    assertFalse(other.asksAge(player))
    assertEquals(Consents.State.MISSING, other.check(player))
    other.give(player, ageConfirmed = false)
    assertEquals(Consents.State.GIVEN, other.check(player))
  }

  @Test fun `the sealed list holds who agreed here, and loses them the ways they leave`() = runBlocking {
    val file = java.nio.file.Files.createTempDirectory("allay-consented").resolve(Consented.FILE).toFile()
    val log = java.util.logging.Logger.getAnonymousLogger()
    fun list() = Consented(SealedFile(file, { "lic.secret-value" }, "allay consented v1", log), log)
    val backend = FakeBackend()
    val consents = Consents(backend, { notice }, local = list())
    assertFalse(consents.listed(player))
    consents.check(player)
    consents.give(player)
    // Sealed on disk, and read back by a fresh start.
    assertFalse(file.readText(Charsets.ISO_8859_1).contains(player.toString()))
    assertTrue(list().has(player))
    consents.declined(player)
    assertFalse(list().has(player))
    // The backend knows the agreement: the list learns it back.
    assertEquals(Consents.State.GIVEN, consents.check(player))
    assertTrue(consents.listed(player))
    // Erased in the admin panel: the backend has nothing, so the list drops the player.
    backend.given.clear()
    assertEquals(Consents.State.MISSING, consents.check(player))
    assertFalse(list().has(player))
    consents.check(player)
    consents.give(player)
    assertTrue(consents.withdraw(player))
    assertFalse(consents.listed(player))
    consents.give(player)
    consents.forget(player)
    assertFalse(list().has(player))
  }

  @Test fun `withdrawing and erasing ask again`() = runBlocking {
    val backend = FakeBackend()
    val consents = consents(backend)
    consents.check(player)
    consents.give(player)
    assertTrue(consents.withdraw(player))
    assertFalse(consents.known(player))
    assertFalse(consents.withdraw(player))
    consents.give(player)
    consents.forget(player)
    assertFalse(consents.known(player))
  }

  @Test fun `a backend before 6_1 asks nobody, until it's updated, and other errors go through`() = runBlocking {
    var now = 0L
    val backend = FakeBackend(status = 404)
    val old = consents(backend) { now }
    assertEquals(Consents.State.UNSUPPORTED, old.check(player))
    assertTrue(old.known(player))
    // The backend moved to 6.1 meanwhile: within the hour the player is asked.
    backend.status = null
    now += Consents.RECHECK_MS
    assertFalse(old.known(player))
    assertEquals(Consents.State.MISSING, old.check(player))
    val down = consents(FakeBackend(status = 503))
    assertFailsWith<ApiErrorException> { down.check(player) }
    assertFalse(down.known(player))
  }
}
