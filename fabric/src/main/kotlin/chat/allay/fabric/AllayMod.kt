package chat.allay.fabric

import chat.allay.core.Allay
import chat.allay.core.Permissions
import chat.allay.fabric.tools.ToolContext

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress

/**
 * The mod's entry: Allay from the core for each server the game runs, a dedicated one or the one under a singleplayer
 * world, with the game's side in [FabricGame]. Hands the game's commands to the core's `Commands` and its events to
 * the core's pieces, as Bukkit's `Events` does.
 */
class AllayMod : ModInitializer {
  override fun onInitialize() {
    ServerLifecycleEvents.SERVER_STARTED.register(::start)
    ServerLifecycleEvents.SERVER_STOPPING.register { stop() }
    ServerTickEvents.END_SERVER_TICK.register {
      platform?.scheduler?.tick()
      Hooks.tick.each { it() }
    }
    CommandRegistrationCallback.EVENT.register { dispatcher, _, _ -> register(dispatcher) }

    ServerPlayConnectionEvents.JOIN.register { handler, _, _ -> joined(handler.player) }
    ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> left(handler.player) }
    // Before every other listener sees it as an ordinary message, as Bukkit's LOWEST.
    ServerMessageEvents.ALLOW_CHAT_MESSAGE.register { message, player, _ -> chat(player, message.signedContent()) }
    PlayerBlockBreakEvents.AFTER.register { level, player, pos, _, _ ->
      if (player is ServerPlayer && level is ServerLevel) Hooks.brokeBlock.each { it(player, level, pos) }
    }
    ServerPlayerEvents.AFTER_RESPAWN.register { _, player, _ -> Hooks.arrived.each { it(player) } }
    ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register { player, _, _ -> Hooks.arrived.each { it(player) } }
    AttackBlockCallback.EVENT.register { player, level, _, pos, _ ->
      if (player is ServerPlayer && level is ServerLevel && Hooks.hit(player, level, pos)) InteractionResult.FAIL else InteractionResult.PASS
    }
  }

  private fun start(server: MinecraftServer) {
    val platform = FabricPlatform(server)
    val allay = Allay<ToolContext>(platform) { FabricGame(platform, it) }
    platform.on = true
    try {
      allay.enable()
      Companion.platform = platform
      Companion.allay = allay
    } catch (error: Throwable) {
      platform.on = false
      LoggerFactory.getLogger("Allay").error("Allay couldn't start", error)
    }
  }

  private fun stop() {
    val allay = allay ?: return
    try {
      allay.disable()
    } finally {
      platform?.on = false
      platform?.scheduler?.stop()
      Hooks.clear()
      Companion.allay = null
      Companion.platform = null
    }
  }

  private fun joined(player: ServerPlayer) {
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

  private fun left(player: ServerPlayer) {
    val allay = allay ?: return
    allay.chat.quit(player.uuid)
    allay.locales.quit(player.uuid)
    Hooks.left.each { it(player) }
    // After every other listener, so what they changed goes back to the backend.
    allay.quit(player.uuid)
  }

  /**
   * Chat mode's routing. The game signs chat, so a line can't be changed on its way: when chat mode turns `~text` into
   * `text`, the signed line stays behind and the plain one goes out as the player's, as a say button sends it.
   */
  private fun chat(player: ServerPlayer, message: String): Boolean {
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
  private fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
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

  companion object {
    /** Allay on the server that runs now; null between servers, as in the main menu of a singleplayer game. */
    @Volatile var allay: Allay<ToolContext>? = null; private set
    @Volatile var platform: FabricPlatform? = null; private set

    /** From the game's options packet: the player switched their game's language. */
    @JvmStatic fun localeChanged(player: ServerPlayer, locale: String) {
      allay?.locales?.changed(player.uuid, locale)
    }
  }
}
