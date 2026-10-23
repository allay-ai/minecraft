package chat.allay.fabric.tools

import chat.allay.core.*
import chat.allay.core.tools.*
import chat.allay.fabric.Texts

import net.minecraft.core.component.DataComponents
import net.minecraft.server.network.Filterable
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.WrittenBookContent

/** A book the assistant wrote, as the game's written book. */
object BookItems {
  fun item(book: WrittenBook, author: String): ItemStack = ItemStack(Items.WRITTEN_BOOK).apply {
    val pages = book.pages.map { Filterable.passThrough<net.minecraft.network.chat.Component>(Texts.legacy(Legacy.color(it))) }
    set(DataComponents.WRITTEN_BOOK_CONTENT, WrittenBookContent(Filterable.passThrough(book.title.take(32)), author, 0, pages, true))
  }
}
