package chat.allay.fabric.client

import chat.allay.core.Legacy
import chat.allay.core.Texts as Languages
import chat.allay.fabric.Texts

import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.client.Minecraft

/**
 * Allay in a player's game. In singleplayer and on LAN it runs on the game's own server ([chat.allay.fabric.AllayMod]).
 * On a server without Allay it can't do anything: the world, the chests, and the answers belong to that server. There
 * `/ask`, `/askall`, `/askx`, and `/allay` only say so in the player's language. On a server with Allay they go to it.
 */
class AllayClient : ClientModInitializer {
  override fun onInitializeClient() {
    net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
      NAMES.forEach { name ->
        // Unknown to the client while the server has its own, so the command goes to the server as typed.
        val command = ClientCommands.literal(name).requires { mine() }.executes { refuse(it.source) }
        command.then(ClientCommands.argument("text", StringArgumentType.greedyString()).executes { refuse(it.source) })
        dispatcher.register(command)
      }
    }
  }

  companion object {
    private val NAMES = listOf("ask", "askall", "askx", "allay")

    /** What the server we're on registered itself, as its command tree last said. */
    @Volatile private var server: Set<String> = emptySet()

    @JvmStatic fun serverCommands(names: List<String>) {
      server = names.toSet()
    }

    /**
     * Ours to answer: a server without Allay, and not the game's own server, where Allay runs. A server sends only the
     * commands a player may use, so any of Allay's four in its tree (`/allay` is everyone's) means Allay runs there.
     */
    fun mine(): Boolean = !Minecraft.getInstance().hasSingleplayerServer() && NAMES.none { it in server }

    private fun refuse(source: FabricClientCommandSource): Int {
      source.sendFeedback(Texts.legacy(Legacy.color(languages(Minecraft.getInstance().options.languageCode).text("messages.not_on_this_server")), links = true))
      return 1
    }

    /** The player's language from the jar's texts, English when Allay doesn't speak it. */
    private fun languages(locale: String): chat.allay.core.Lang {
      val code = locale.substringBefore('_')
      val texts = listOf("en", code).distinct().mapNotNull { language ->
        AllayClient::class.java.classLoader.getResourceAsStream("lang/$language.yml")?.reader(Charsets.UTF_8)?.use { language to Languages.read(it) }
      }.toMap()
      return Languages(texts, "en").lang(code)
    }
  }
}
