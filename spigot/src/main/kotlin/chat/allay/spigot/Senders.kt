package chat.allay.spigot

import chat.allay.core.*
import chat.allay.core.tools.*

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import chat.allay.core.Asker
import chat.allay.core.Lang
import chat.allay.core.Run
import chat.allay.spigot.tools.id
import java.util.UUID

/** A Bukkit sender as the core sees whoever asked; [chosen] is the language they picked in Allay, which beats the game's. */
class SenderAsker(val sender: CommandSender, private val chosen: String? = null) : Asker {
  override val name: String get() = sender.name
  override val uuid: UUID? get() = (sender as? Player)?.uniqueId
  override val operator: Boolean get() = sender.isOp
  override val locale: String? get() = chosen ?: (sender as? Player)?.locale
  // Read once here, on the server thread: the request is built off it.
  override val dimension: String? = (sender as? Player)?.world?.let(Dimensions::id)
  override fun can(permission: String) = sender.hasPermission(permission)
}

/** Who asked this run, as Bukkit knows them. */
val Run.sender: CommandSender get() = (asker as SenderAsker).sender

/** Sends the message unless the server turned it off with an empty text. */
fun Lang.send(to: CommandSender, key: String, vararg values: Pair<String, Any?>) {
  val text = text(key, *values)
  if (text.isNotEmpty()) to.sendMessage(Minecraft.formatMessage(text))
}
