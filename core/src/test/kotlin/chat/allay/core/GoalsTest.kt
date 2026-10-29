package chat.allay.core

import chat.allay.core.tools.Books
import chat.allay.core.tools.Button
import chat.allay.core.tools.ButtonStore
import chat.allay.core.tools.Goal
import chat.allay.core.tools.GoalBook
import chat.allay.core.tools.ToolError
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoalsTest {
  private fun pickup(item: String, target: Long) = Goal(id = "", kind = "pickup", title = "Алмазы", target = target, material = item, createdAt = 0)

  @Test fun `pickups count toward goals for that item only`() {
    val book = GoalBook(emptyList(), { 5 })
    val goal = book.add { id -> pickup("diamond", 16).copy(id = id) }
    book.pickedUp("diamond", 3)
    assertEquals(emptyList(), book.pickedUp("emerald", 5))
    val moved = book.pickedUp("diamond", 2)
    assertEquals(5, moved.single().progress)
    assertEquals(goal.id, moved.single().id)
  }

  @Test fun `items from the player's own death don't count twice`() {
    val book = GoalBook(emptyList(), { 5 })
    book.add { id -> pickup("diamond", 16).copy(id = id) }
    book.pickedUp("diamond", 10)
    book.died(mapOf("diamond" to 10))
    // Picking the dropped ten back up repays the debt; only the two new ones count.
    assertEquals(emptyList(), book.pickedUp("diamond", 6))
    assertEquals(12, book.pickedUp("diamond", 6).single().progress)
  }

  @Test fun `a place set from another dimension counts from the first distance there`() {
    val book = GoalBook(emptyList(), { 5 })
    val goal = book.add { id -> Goal(id = id, kind = "reach", title = "База", target = 1, x = 100, z = 0, radius = 10, dimension = "overworld", baseline = Goal.UNSET, createdAt = 0) }
    val counted = book.rebase(goal.id, 400)!!
    assertEquals(400, counted.baseline)
    assertEquals(400, counted.target)
    assertEquals(0, counted.progress)
    assertNull(book.rebase("gone", 10))
  }

  @Test fun `goals are capped and done at the target`() {
    val book = GoalBook(emptyList(), { 1 })
    val goal = book.add { id -> pickup("diamond", 2).copy(id = id) }
    assertFailsWith<ToolError> { book.add { id -> pickup("emerald", 1).copy(id = id) } }
    assertTrue(book.progress(goal.id, 2)!!.done)
    assertEquals(1.0, book.progress(goal.id, 5)!!.fraction)
    assertEquals(goal.id, book.remove(goal.id.uppercase())?.id)
    assertNull(book.progress(goal.id, 1))
  }

  @Test fun `buttons work only for their player, and one-shot ones once`() {
    val store = ButtonStore(ttlMillis = 1_000)
    val me = UUID.randomUUID()
    val someone = UUID.randomUUID()
    val ask = store.add(me, Button.Ask("Да", "как зачаровать меч?"), now = 0)
    val note = store.add(me, Button.Note("Записать", "деревня", 1, 64, 2, null), now = 0)
    assertNull(store.take(someone, ask, now = 10))
    assertEquals("как зачаровать меч?", (store.take(me, ask, now = 10) as Button.Ask).question)
    assertTrue(store.take(me, ask, now = 20) is Button.Ask)
    assertTrue(store.take(me, note, now = 20) is Button.Note)
    assertNull(store.take(me, note, now = 30))
    assertNull(store.take(me, ask, now = 2_000))
  }

  @Test fun `books split into pages that fit`() {
    val text = (1..60).joinToString("\n") { "Шаг $it: положи блок и подожди немного, пока всё не вырастет." }
    val pages = Books.paginate(text)
    assertTrue(pages.size > 5)
    pages.forEach { assertTrue(it.length <= Books.PAGE_CHARS, "page of ${it.length}") }
    assertEquals(text.replace("\n", " ").split(" ").size, pages.joinToString(" ") { it.replace("\n", " ") }.split(" ").filter(String::isNotEmpty).size)
    assertEquals(listOf("Коротко."), Books.paginate("Коротко."))
  }
}
