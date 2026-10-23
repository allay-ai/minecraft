package chat.allay.fabric.client

import chat.allay.modcore.Elsewhere

import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.Minecraft

/**
 * Allay in a player's game on Fabric. In singleplayer and on LAN it runs on the game's own server
 * ([chat.allay.modcore.Mod]); on a server without Allay its commands only say it can't work there ([Elsewhere]).
 */
class AllayClient : ClientModInitializer {
  override fun onInitializeClient() {
    ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
      Elsewhere.NAMES.forEach { name ->
        // Unknown to the client while the server has its own, so the command goes to the server as typed.
        val command = ClientCommands.literal(name).requires { mine() }.executes { refuse(it.source) }
        command.then(ClientCommands.argument("text", StringArgumentType.greedyString()).executes { refuse(it.source) })
        dispatcher.register(command)
      }
    }
  }

  companion object {
    fun mine(): Boolean = Elsewhere.mine(Minecraft.getInstance().hasSingleplayerServer())

    private fun refuse(source: FabricClientCommandSource): Int {
      source.sendFeedback(Elsewhere.refusal(Minecraft.getInstance().options.languageCode))
      return 1
    }
  }
}
