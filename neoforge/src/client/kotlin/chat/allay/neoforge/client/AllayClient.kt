package chat.allay.neoforge.client

import chat.allay.modcore.Elsewhere

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import net.minecraft.client.Minecraft
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent
import net.neoforged.neoforge.common.NeoForge

/**
 * Allay in a player's game on NeoForge. In singleplayer and on LAN it runs on the game's own server
 * ([chat.allay.modcore.Mod]); on a server without Allay its commands only say it can't work there ([Elsewhere]).
 */
@Mod(value = "allay", dist = [Dist.CLIENT])
class AllayClient(@Suppress("UNUSED_PARAMETER") modBus: IEventBus) {
  init {
    NeoForge.EVENT_BUS.addListener { event: RegisterClientCommandsEvent ->
      Elsewhere.NAMES.forEach { name ->
        // Unknown to the client while the server has its own, so NeoForge sends the command to the server as typed.
        val command = Commands.literal(name).requires { mine() }.executes(::refuse)
        command.then(Commands.argument("text", StringArgumentType.greedyString()).executes(::refuse))
        event.dispatcher.register(command)
      }
    }
  }

  companion object {
    fun mine(): Boolean = Elsewhere.mine(Minecraft.getInstance().hasSingleplayerServer())

    private fun refuse(context: CommandContext<CommandSourceStack>): Int {
      context.source.sendSystemMessage(Elsewhere.refusal(Minecraft.getInstance().options.languageCode))
      return 1
    }
  }
}
