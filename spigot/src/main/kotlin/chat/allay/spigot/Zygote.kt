package chat.allay.spigot

import chat.allay.core.Allay
import chat.allay.spigot.tools.ToolContext

import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.plugin.java.JavaPlugin

/** The plugin's entry: Allay from the core on Bukkit, with the game's side in [SpigotGame]. */
class Zygote : JavaPlugin(), TabCompleter {
  private val allay = Allay<ToolContext>(BukkitPlatform(this)) { SpigotGame(this, it) }

  override fun onEnable() {
    allay.enable()
    listOf("ask", "askx", "askall", "allay").map(::getCommand).forEach {
      it?.setExecutor(this)
      it?.tabCompleter = this
    }
  }

  override fun onDisable() = allay.disable()

  override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean =
    allay.commands.run(sender.audience, command.name, args.toList())

  override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> =
    allay.commands.complete(sender.audience, command.name, args.toList())
}
