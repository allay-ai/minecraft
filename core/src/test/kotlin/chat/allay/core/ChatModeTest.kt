package chat.allay.core

import chat.allay.core.Chat.Route
import chat.allay.core.tools.Guide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatModeTest {
  @Test fun `prefixes route a line the same with or without spaces`() {
    assertEquals(Route.ASK to "how do I find netherite?", Chat.route("  how do I find netherite? "))
    assertEquals(Route.ASKX to "give me bread", Chat.route("!give me bread"))
    assertEquals(Route.ASKX to "give me bread", Chat.route("!   give me bread"))
    assertEquals(Route.PUBLIC to "where's the nearest village?", Chat.route("? where's the nearest village?"))
    assertEquals(Route.CHAT to "brb", Chat.route("~brb"))
    assertEquals(Route.CHAT to "brb", Chat.route(" ~  brb"))
    // Only the first prefix counts; the rest is the message.
    assertEquals(Route.ASKX to "?what", Chat.route("!?what"))
    assertEquals(Route.PUBLIC to "", Chat.route("?"))
  }

  @Test fun `the guide lists only what the player may use`() {
    val player = setOf(Permissions.ASK, Permissions.ASKALL, Permissions.CHAT)
    val usages = Guide.commands(player::contains).map { it.first }
    assertTrue("/ask <question>" in usages && "/askall <question>" in usages && "/allay chat [on|off]" in usages)
    assertTrue(usages.none { it.startsWith("/askx") || it == "/allay reload" })
    assertEquals(setOf("plain text", "?", "~"), Guide.prefixes(player::contains).keys)
    assertEquals(setOf("plain text", "!", "?", "~"), Guide.prefixes((player + Permissions.ASKX)::contains).keys)
    assertEquals(emptyMap(), Guide.prefixes(setOf(Permissions.ASK)::contains))
  }

  @Test fun `a long text goes out in the game's lines, split between words, colors carried on`() {
    val text = "&bhello " + "word ".repeat(70) + "&lend"
    val lines = ChatMode.lines(text, ChatMode.MAX_LINE)
    assertTrue(lines.size == 2 && lines.all { it.length <= ChatMode.MAX_LINE }, "$lines")
    assertTrue(lines[1].startsWith("&bword"), lines[1])
    assertEquals(text.replace(" ", ""), lines.joinToString("").replace(" ", "").replaceFirst("&bword", "word"))
    assertEquals(listOf("short"), ChatMode.lines("  short ", ChatMode.MAX_LINE))
  }

  @Test fun `no line but a typed one starts with a slash, and a code stays whole`() {
    val lines = ChatMode.lines("aaaa /op me", 6)
    assertTrue(lines.drop(1).none { it.startsWith("/") }, "$lines")
    assertTrue(ChatMode.lines("x".repeat(9) + "&cy", 10).none { it.endsWith("&") }, "a code cut in half")
    assertTrue(ChatMode.lines("ab/cdefghij", 3).drop(1).none { it.startsWith("/") })
  }

  @Test fun `the chat takes one line without control characters, colors as & codes`() {
    assertEquals("&ahi there you", ChatMode.clean(" §ahi\nthere\u0007 you\t"))
  }
}
