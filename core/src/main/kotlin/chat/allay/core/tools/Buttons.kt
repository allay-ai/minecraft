package chat.allay.core.tools

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A clickable `[label]` under an answer. Each one does something harmless for the player who got it. */
sealed interface Button {
  val label: String

  /** Asks Allay this question, as if the player typed `/ask question`; under an answer everyone read, as `/askall`. */
  data class Ask(override val label: String, val question: String, val public: Boolean = false) : Button

  /** Shows the way to a spot with a particle trail and the compass. */
  data class Point(override val label: String, val x: Int, val y: Int, val z: Int, val dimension: String?) : Button

  /** Shows the way to another player by name, following them while they're on the presser's locator bar. */
  data class Follow(override val label: String, val player: String) : Button

  /** Remembers the text with these coordinates: a memory with a place on the backend. */
  data class Note(override val label: String, val text: String, val x: Int, val y: Int, val z: Int, val dimension: String?) : Button

  /** Puts text into the player's clipboard, such as coordinates; the client does it, so no id is needed. */
  data class Copy(override val label: String, val text: String) : Button

  /** Opens the recipe window for an item. */
  data class Recipe(override val label: String, val item: String) : Button

  /** Opens the brewing window: how to brew [potion] in the bottle [form]. */
  data class Brewing(override val label: String, val potion: String, val form: String?) : Button

  /** Opens the anvil plan for the item in hand with what the player has, or with these inventory [slots] only. */
  data class Anvil(override val label: String, val slots: List<Int>?) : Button

  /** Opens the enchanting window for the item in hand. */
  data class Enchants(override val label: String) : Button

  /** Opens the window with the villagers around, optionally only trades with this item. */
  data class Villagers(override val label: String, val item: String?) : Button

  /** Opens a book Allay wrote for the player. */
  data class ReadBook(override val label: String, val book: String) : Button

  /** Puts a book Allay wrote into the player's inventory, once. */
  data class TakeBook(override val label: String, val book: String) : Button

  /** Answers an automation agent's question: the click wakes agent [task] with [reply]. */
  data class Reply(override val label: String, val task: String, val reply: String) : Button

  /** Ends picking blocks for an automation agent: [action] is done, all, or cancel. */
  data class Pick(override val label: String, val action: String) : Button

  /** Puts back the last change to chests or the inventory (an agent's plan, a sort), within its undo time. */
  data class Undo(override val label: String) : Button

  /** Turns chat mode on, from the tip under an answer. */
  data class ChatMode(override val label: String) : Button

  /** Stops the chat mode tip for good. */
  data class HideTips(override val label: String) : Button
}

/**
 * Buttons each player got, by short id. A click runs `/allay do <id>`, so a player can only press buttons that were
 * shown to them: ids belong to one player and run out after [ttlMillis].
 */
class ButtonStore(private val ttlMillis: Long = 60 * 60_000L, private val perPlayer: Int = 60, private val newId: () -> String = ReminderBook::shortId) {
  private class Entry(val button: Button, val createdAt: Long)

  private val players = ConcurrentHashMap<UUID, LinkedHashMap<String, Entry>>()

  fun add(player: UUID, button: Button, now: Long = System.currentTimeMillis()): String {
    val entries = players.computeIfAbsent(player) { LinkedHashMap() }
    synchronized(entries) {
      entries.values.removeIf { now - it.createdAt > ttlMillis }
      while (entries.size >= perPlayer) entries.remove(entries.keys.first())
      val id = generateSequence(newId).first { it !in entries }
      entries[id] = Entry(button, now)
      return id
    }
  }

  /** The button behind an id, or null when it's someone else's, expired, or used up. One-shot buttons go away here. */
  fun take(player: UUID, id: String, now: Long = System.currentTimeMillis()): Button? {
    val entries = players[player] ?: return null
    synchronized(entries) {
      val entry = entries[id.lowercase()] ?: return null
      if (now - entry.createdAt > ttlMillis) {
        entries.remove(id.lowercase())
        return null
      }
      if (entry.button is Button.Note || entry.button is Button.TakeBook) entries.remove(id.lowercase())
      return entry.button
    }
  }

  fun forget(player: UUID) {
    players.remove(player)
  }
}
