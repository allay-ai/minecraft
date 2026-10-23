package chat.allay.spigot.tools

import chat.allay.core.*
import chat.allay.core.tools.*

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BookMeta
import chat.allay.core.tools.WrittenBook

/** A book the assistant wrote, as the game's written book. */
object BookItems {
  fun item(book: WrittenBook, author: String): ItemStack = ItemStack(Material.WRITTEN_BOOK).apply {
    val meta = itemMeta as BookMeta
    meta.title = book.title.take(32)
    meta.author = author
    meta.generation = BookMeta.Generation.ORIGINAL
    meta.pages = book.pages.map(Legacy::color)
    itemMeta = meta
  }
}
