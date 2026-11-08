package chat.allay.core

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MemoryFormTest {
  @Test fun `the window's form reads tags, a title, and the text, which may hold bars itself`() {
    assertEquals(Triple(listOf("ферма", "железо"), "Железная ферма", "Два слоя | 20 на 20"), MemoryCommands.Form.parse("#ферма, железо ферма | Железная ферма | Два слоя | 20 на 20")?.let { (tags, title, text) -> Triple(tags.take(2), title, text) })
    assertEquals(Triple(emptyList<String>(), "", "base at the lake"), MemoryCommands.Form.parse(" |  | base at the lake"))
  }

  @Test fun `a typed line without the form's bars is the text alone`() {
    assertNull(MemoryCommands.Form.parse("base at 120 64 -300"))
    assertNull(MemoryCommands.Form.parse("a | b"))
  }

  @Test fun `memories from an older backend read with no kind, tags, or pin`() {
    val json = Json { ignoreUnknownKeys = true }
    val old = json.decodeFromString(Service.Memory.serializer(), """{"id":"k3f9a","text":"base","created_at":"2026-10-11T00:00:00Z"}""")
    assertEquals(Service.Memory("k3f9a", "base", created_at = "2026-10-11T00:00:00Z"), old)
    val new = json.decodeFromString(Service.Memory.serializer(), """{"id":"a","text":"Two layers","kind":"place","title":"Farm","tags":["ферма"],"pinned":true,"category":"farm","place":{"x":1,"y":2,"z":3,"dimension":"overworld","radius":10}}""")
    assertEquals("Farm", new.label)
    assertEquals(10, new.place?.radius)
    assertEquals(true, new.pinned)
  }
}
