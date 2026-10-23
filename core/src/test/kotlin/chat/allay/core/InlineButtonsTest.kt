package chat.allay.core

import chat.allay.core.tools.InlineButtons
import chat.allay.core.tools.InlineButtons.Part
import kotlin.test.Test
import kotlin.test.assertEquals

class InlineButtonsTest {
  @Test fun `tags split the answer where they stand`() {
    val parts = InlineButtons.parse("""Хочешь, расскажу? <button action="ask" question="Как зачаровать кирку?">Да</button> Или нет.""")
    assertEquals(
      listOf(Part.Text("Хочешь, расскажу? "), Part.Tag("Да", mapOf("action" to "ask", "question" to "Как зачаровать кирку?")), Part.Text(" Или нет.")),
      parts,
    )
  }

  @Test fun `attributes unescape and keep their order-free values`() {
    val tag = InlineButtons.parse("""<button  action="say"   text="Say &quot;hi&quot; &amp; bye">Send</button>""").single() as Part.Tag
    assertEquals("Say \"hi\" & bye", tag.attributes["text"])
    assertEquals("say", tag.attributes["action"])
  }

  @Test fun `a broken tag stays as written, and the plain answer keeps labels`() {
    val broken = "Text <button action=\"ask\">Yes"
    assertEquals(listOf(Part.Text(broken)), InlineButtons.parse(broken))
    assertEquals("Go Here now", InlineButtons.plain("""Go <button action="point" x="1" z="2">Here</button> now"""))
  }

  @Test fun `buttons past the limit become their labels`() {
    val answer = (1..10).joinToString(" ") { """<button action="ask" question="q$it">b$it</button>""" }
    val parts = InlineButtons.parse(answer)
    assertEquals(InlineButtons.MAX, parts.count { it is Part.Tag })
    assertEquals(Part.Text("b9"), parts.filterNot { it == Part.Text(" ") }[8])
  }
}
