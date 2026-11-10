package chat.allay.core

import chat.allay.core.tasks.AgentSnapshot
import chat.allay.core.tasks.AgentStore
import chat.allay.core.tasks.HeldItems
import chat.allay.core.tasks.Kind
import chat.allay.core.tasks.Need
import chat.allay.core.tasks.Parked
import chat.allay.core.tasks.Reservations
import chat.allay.core.tasks.SourceScope
import chat.allay.core.tasks.TaskBook
import chat.allay.core.tools.ToolError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Agents that outlive their player's leaving and the server's restart: their slots, their snapshots, and the store. */
class AgentStoreTest {
  private val player = UUID.fromString("00000000-0000-0000-0000-000000000002")
  private val log = Logger.getAnonymousLogger()
  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

  @Test fun `an agent waiting for items holds no slot`() {
    val book = TaskBook({ 1 })
    val first = book.start(player, Kind.CRAFTING, "a", "", 1, null)
    assertFailsWith<ToolError> { book.start(player, Kind.CHESTS, "b", "", 1, null) }
    first.parked = Parked(listOf(Need("oak_log", 10)), 0, 1, 0)
    book.start(player, Kind.CHESTS, "b", "", 1, null)
    assertEquals(2, book.of(player).size)
  }

  @Test fun `an agent brought back keeps its id, and its id never names another`() {
    val ids = ArrayDeque(listOf("k3f9ab", "zz2222"))
    val book = TaskBook({ 2 }, newId = { ids.removeFirst() })
    val back = chat.allay.core.tasks.Task("k3f9ab", player, "Печи", Kind.FURNACES, 1, null, "smelt", 0)
    book.adopt(back)
    assertEquals(back, book.find(player, "k3f9ab"))
    // The next agent can't take the id the one brought back holds.
    assertEquals("zz2222", book.start(player, Kind.CHESTS, "b", "", 1, null).id)
    book.detach(back)
    assertNull(book.find(player, "k3f9ab"))
    assertTrue(!back.done, "taking it out isn't ending it")
  }

  @Test fun `what an agent holds goes with it and comes back`() {
    val holds = Reservations()
    holds.reserve("k3f9ab", "world:1,64,2", "iron_ore", 40)
    holds.reserve("k3f9ab", "inventory:p", "coal", 5)
    holds.reserve("other1", "world:1,64,2", "iron_ore", 3)
    val saved = holds.export("k3f9ab")
    holds.release("k3f9ab")
    assertEquals(3, holds.held("world:1,64,2", "iron_ore"))
    holds.restore("k3f9ab", saved)
    assertEquals(43, holds.held("world:1,64,2", "iron_ore"))
    assertEquals(listOf(HeldItems("world:1,64,2", "iron_ore", 40), HeldItems("inventory:p", "coal", 5)), holds.export("k3f9ab"))
  }

  @Test fun `a snapshot reads back, and a newer plugin's fields don't break an older one`() {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }
    val snapshot = AgentSnapshot(
      id = "k3f9ab", name = "Печи", kind = "furnaces", depth = 1, brief = "smelt 64 iron", started_at = 5, active_ms = 61_000, state = "waiting",
      anchor = Protocol.Position(10, 64, -3, "overworld", "world"), scope = SourceScope(ender_chest = true, chests = listOf("world:1,64,2")),
      parked = Parked(listOf(Need("oak_log", 10)), 1, 2, 1, "for the roof"), job = buildJsonObject { put("type", "smelt") },
    )
    val written = json.encodeToJsonElement(AgentSnapshot.serializer(), snapshot).jsonObject
    assertEquals(snapshot, json.decodeFromJsonElement(AgentSnapshot.serializer(), written))
    val newer = JsonObject(written + ("mood" to kotlinx.serialization.json.JsonPrimitive("cheerful")))
    assertEquals(snapshot, json.decodeFromJsonElement(AgentSnapshot.serializer(), newer))
  }

  private class Backend : AgentStore.Backend {
    val saved = CopyOnWriteArrayList<List<JsonObject>>()
    @Volatile var failures = 0
    @Volatile var gone = false

    override suspend fun agents(userId: String, serverId: String): List<JsonObject> {
      if (gone) throw ApiErrorException(ApiError("HTTP_ERROR", "not found", statusCode = 404))
      return saved.lastOrNull().orEmpty()
    }

    override suspend fun saveAgents(userId: String, serverId: String, agents: List<JsonObject>) {
      if (gone) throw ApiErrorException(ApiError("HTTP_ERROR", "not found", statusCode = 404))
      if (failures > 0) {
        failures--
        throw java.io.IOException("connection reset")
      }
      saved += agents
    }
  }

  private fun agent(name: String) = buildJsonObject { put("name", name) }

  @Test fun `a burst of changes goes as one, and a lost backend gets it again`() = runBlocking {
    val backend = Backend().apply { failures = 1 }
    val store = AgentStore({ backend }, "moonlands", scope, log, debounceMs = 50)
    store.save(player, listOf(agent("a")))
    store.save(player, listOf(agent("a"), agent("b")))
    val until = System.currentTimeMillis() + 10_000
    while (backend.saved.isEmpty() && System.currentTimeMillis() < until) delay(20)
    assertEquals(listOf(listOf(agent("a"), agent("b"))), backend.saved.toList())
    assertEquals(listOf(agent("a"), agent("b")), store.load(player))
  }

  @Test fun `a player who leaves is saved at once, and a backend without agents is left alone`() = runBlocking {
    val backend = Backend()
    val store = AgentStore({ backend }, "moonlands", scope, log, debounceMs = 60_000)
    store.save(player, listOf(agent("a")))
    store.flush(player).join()
    assertEquals(1, backend.saved.size)
    backend.gone = true
    assertEquals(emptyList(), store.load(player))
    store.save(player, listOf(agent("b")))
    store.stop(500)
    assertEquals(1, backend.saved.size)
  }
}
