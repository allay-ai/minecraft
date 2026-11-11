package chat.allay.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import java.net.InetAddress
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatHistoryTest {
  private fun player(name: String) = object : PlayerAudience {
    override val name = name
    override val uuid: UUID = UUID.nameUUIDFromBytes(name.toByteArray())
    override val operator = false
    override val online = true
    override val locale = "en_us"
    override val address: InetAddress? = null
    override val position = Protocol.Position(0, 64, 0, "overworld")
    override fun can(permission: String) = true
    override fun send(text: String) {}
    override fun send(line: Line) {}
    override fun actionBar(text: String) {}
    override fun show(window: Window) {}
    override fun closeWindow() {}
    override fun chat(text: String) {}
  }

  private val steve = player("Steve")
  private val alex = player("Alex")
  private val notch = player("Notch")
  private val online = listOf(steve, alex, notch)

  /** Only what ChatHistory asks of a platform: who's online, by name and by UUID. */
  private val platform = Proxy.newProxyInstance(Platform::class.java.classLoader, arrayOf(Platform::class.java)) { _, method, args ->
    when (method.name) {
      "getPlayers" -> online
      "player" -> when (val key = args[0]) {
        is String -> online.firstOrNull { it.name.equals(key, ignoreCase = true) }
        else -> online.firstOrNull { it.uuid == key }
      }
      else -> throw UnsupportedOperationException(method.name)
    }
  } as Platform

  private fun config(yaml: String = "") = Config(YamlConfig(YamlConfig.parse("server:\n  url: x\n$yaml"), YamlConfig.parse(javaClass.getResource("/config.yml")!!.readText())), "test")

  private var now = 1_000L
  private val sent = mutableListOf<List<ChatHistory.Line>>()
  private var failure: Exception? = null

  private var erased = 0

  private fun history(yaml: String = "") = ChatHistory(platform, CoroutineScope(Dispatchers.Unconfined), Logger.getAnonymousLogger(), { config(yaml) }, { batch ->
    failure?.let { throw it }
    sent += batch
  }, { erased++; 42 }, clock = { now })

  @Test fun `a reset drops what waits and asks the backend to forget the rest`() {
    val history = history()
    history.chat(steve, "hi")
    var heard: Result<Int>? = null
    history.reset { heard = it }
    assertEquals(42, heard?.getOrNull())
    assertEquals(1, erased)
    assertEquals(emptyList(), history.waiting)
  }

  @Test fun `a line everyone read is public, one fewer saw is local and names who saw it`() {
    val history = history()
    history.chat(steve, "§bhi all")
    history.chat(steve, "and again", listOf(steve.uuid, alex.uuid, notch.uuid))
    history.chat(notch, "psst", listOf(notch.uuid, alex.uuid))
    val (all, again, local) = history.waiting
    assertEquals("public" to "§bhi all", all.channel to all.text)
    assertEquals("public", again.channel)
    assertNull(again.audience)
    assertEquals("local", local.channel)
    assertEquals(listOf(alex.uuid.toString()), local.audience)
  }

  @Test fun `private messages and replies go to the right player, with a moment of their own each`() {
    val history = history()
    history.command(steve, "msg alex can you lend me diamonds")
    history.command(alex, "essentials:r sure, 12?")
    history.command(steve, "tell Nobody hi")
    history.command(steve, "spawn")
    val (ask, reply) = history.waiting
    assertEquals(listOf("Steve" to "Alex", "Alex" to "Steve"), history.waiting.map { it.from.name to it.to?.name })
    assertEquals("can you lend me diamonds", ask.text)
    assertEquals("sure, 12?", reply.text)
    history.whisper(steve, alex, "one")
    history.whisper(steve, notch, "two")
    val (one, two) = history.waiting.drop(2)
    assertTrue(two.at > one.at, "the backend keeps one line per sender and millisecond")
  }

  @Test fun `a line Allay sends is kept once, and one no hook heard was refused`() {
    val history = history()
    assertTrue(history.sent(steve.uuid, hooked = true) { history.chat(steve, "heard by the hook"); true })
    assertFalse(history.sent(steve.uuid, hooked = true) { true }, "a mute cancelled the chat event")
    assertTrue(history.sent(steve.uuid, hooked = false, { history.whisper(steve, alex, "a mod's own /msg") }) { true })
    assertFalse(history.sent(steve.uuid, hooked = false, { history.whisper(steve, alex, "never ran") }) { false })
    assertEquals(listOf("heard by the hook", "a mod's own /msg"), history.waiting.map { it.text })
  }

  @Test fun `a line the player typed meanwhile on another thread doesn't pass for Allay's`() {
    val history = history()
    val sent = history.sent(steve.uuid, hooked = true) {
      Thread { history.chat(steve, "typed by Steve") }.apply { start() }.join()
      true
    }
    assertFalse(sent)
    assertEquals(listOf("typed by Steve"), history.waiting.map { it.text })
  }

  @Test fun `a typed command the hooks let through counts as heard, even one the chat keeps nothing of`() {
    val history = history("tools:\n  enabled:\n    chat_history: false")
    assertTrue(history.sent(steve.uuid, hooked = true) { history.command(steve, "msg alex hi"); true })
    assertEquals(emptyList(), history.waiting)
  }

  @Test fun `switched off it keeps nothing, and the owner's command lists count`() {
    val off = history("tools:\n  enabled:\n    chat_history: false")
    off.chat(steve, "hi")
    off.command(steve, "msg alex hi")
    assertEquals(emptyList(), off.waiting)
    val own = history("tools:\n  chat_history:\n    private_commands: [whisp]\n    reply_commands: []")
    own.command(steve, "msg alex hi")
    own.command(steve, "whisp alex hi")
    own.command(alex, "r back")
    assertEquals(listOf("hi"), own.waiting.map { it.text })
  }

  @Test fun `a failed batch waits for the next round, and a backend without the route ends it`() = runBlocking {
    val history = history()
    history.chat(steve, "one")
    failure = ApiErrorException(ApiError("UNAVAILABLE", "down", 503))
    history.flush()
    assertEquals(listOf("one"), history.waiting.map { it.text })
    failure = null
    history.chat(steve, "two")
    history.flush()
    assertEquals(listOf(listOf("one", "two")), sent.map { batch -> batch.map { it.text } })
    failure = ApiErrorException(ApiError("NOT_FOUND", "no route", 404))
    history.chat(steve, "three")
    history.flush()
    assertTrue(history.old)
    history.chat(steve, "four")
    assertEquals(emptyList(), history.waiting)
  }

  @Test fun `a batch the backend refuses is dropped, so the lines after it go on`() = runBlocking {
    val history = history()
    history.chat(steve, "refused")
    failure = ApiErrorException(ApiError("BAD_REQUEST", "lines.0: bad", 400))
    history.flush()
    assertEquals(emptyList(), history.waiting)
    history.chat(steve, "too many")
    failure = ApiErrorException(ApiError("RATE_LIMITED", "wait", 429))
    history.flush()
    assertEquals(listOf("too many"), history.waiting.map { it.text })
    // A license being fixed keeps the chat for when it works again.
    failure = ApiErrorException(ApiError("LICENSE_INVALID", "revoked", 401))
    history.flush()
    assertEquals(listOf("too many"), history.waiting.map { it.text })
    failure = null
    history.flush()
    assertEquals(listOf(listOf("too many")), sent.map { batch -> batch.map { it.text } })
  }
}
