package chat.allay.modcore

import chat.allay.core.Allay
import chat.allay.core.Permissions
import chat.allay.modcore.tools.ToolContext

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress

/**
 * Allay from the core for each server the game runs, a dedicated one or the one under a singleplayer world, on any
 * loader. The loader's entry calls these from its events: it hands the game's commands to the core's `Commands` and
 * its events to the core's pieces, as Bukkit's `Events` does.
 */
object Mod {
  /** Allay on the server that runs now; null between servers, as in the main menu of a singleplayer game. */
  @Volatile var allay: Allay<ToolContext>? = null; private set
  @Volatile var platform: ModPlatform? = null; private set

  /** The loader the game runs; set once, before the first server starts. */
  lateinit var loader: Loader

  fun start(server: MinecraftServer) {
    val platform = ModPlatform(server)
    val allay = Allay<ToolContext>(platform) { ModGame(platform, it) }
    platform.on = true
    try {
      allay.enable()
      this.platform = platform
      this.allay = allay
    } catch (error: Throwable) {
      platform.on = false
      LoggerFactory.getLogger("Allay").error("Allay couldn't start", error)
      // As Bukkit disables a plugin whose start failed: what started stops, and its listeners and timers go.
      runCatching { allay.disable() }.onFailure { LoggerFactory.getLogger("Allay").error("Allay couldn't stop what it started", it) }
      platform.scheduler.stop()
      Hooks.clear()
    }
  }

  fun stop() {
    val allay = allay ?: return
    try {
      allay.disable()
    } finally {
      platform?.on = false
      platform?.scheduler?.stop()
      Hooks.clear()
      this.allay = null
      this.platform = null
    }
  }

  /** The end of every server tick. */
  fun tick() {
    platform?.scheduler?.tick()
    Hooks.tick.each { it() }
  }

  fun joined(player: ServerPlayer) {
    val allay = allay ?: return
    val audience = player.audience
    allay.locales.login(player.uuid, (player.connection.remoteAddress as? InetSocketAddress)?.address)
    allay.chat.joined(audience)
    allay.quota.joined(audience)
    Hooks.joined.each { it(player) }
    // After the rest: the language first, then the player's state from the backend.
    allay.locales.joined(audience)
    allay.joined(audience)
  }

  fun left(player: ServerPlayer) {
    val allay = allay ?: return
    allay.chat.quit(player.uuid)
    allay.locales.quit(player.uuid)
    Hooks.left.each { it(player) }
    // After every other listener, so what they changed goes back to the backend.
    allay.quit(player.uuid)
  }

  /**
   * Chat mode's routing; false keeps the line out of the chat. The game signs chat, so a line can't be changed on its
   * way: when chat mode turns `~text` into `text`, the signed line stays behind and the plain one goes out as the
   * player's, as a say button sends it.
   */
  fun chat(player: ServerPlayer, message: String): Boolean {
    val allay = allay ?: return true
    val audience = player.audience
    return when (val line = allay.chat.message(audience, message)) {
      null -> false
      message -> true
      else -> {
        platform?.scheduler?.main { if (audience.online) allay.chat.say(audience, line) }
        false
      }
    }
  }

  /** `/ask`, `/askall`, `/askx`, and `/allay`, with the same words after them as on Bukkit, read by the core. */
  fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
    mapOf("ask" to Permissions.ASK, "askall" to Permissions.ASKALL, "askx" to Permissions.ASKX, "allay" to null).forEach { (name, permission) ->
      val command: LiteralArgumentBuilder<CommandSourceStack> = Commands.literal(name)
      if (permission != null) command.requires { Perms.check(it, permission) }
      command.executes { run(it, name, "") }
      command.then(
        Commands.argument("text", StringArgumentType.greedyString())
          .suggests { context, builder ->
            val allay = allay
            if (allay != null && name == "allay") {
              val typed = builder.remaining
              val args = typed.split(" ")
              val start = builder.createOffset(builder.start + typed.lastIndexOf(' ') + 1)
              allay.commands.complete(context.source.audience, name, args).filter { it.startsWith(args.last(), ignoreCase = true) }.forEach(start::suggest)
              return@suggests start.buildFuture()
            }
            builder.buildFuture()
          }
          .executes { run(it, name, StringArgumentType.getString(it, "text")) },
      )
      dispatcher.register(command)
    }
  }

  private fun run(context: CommandContext<CommandSourceStack>, name: String, text: String): Int {
    val allay = allay ?: return 0
    val args = if (text.isEmpty()) emptyList() else text.split(" ")
    return if (allay.commands.run(context.source.audience, name, args)) 1 else 0
  }

  /** From the game's options packet: the player switched their game's language. */
  @JvmStatic fun localeChanged(player: ServerPlayer, locale: String) {
    allay?.locales?.changed(player.uuid, locale)
  }
}
