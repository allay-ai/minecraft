package chat.allay.modcore.gametest

import chat.allay.core.tools.ToolOutput
import chat.allay.modcore.Mod
import chat.allay.modcore.ModGame
import chat.allay.modcore.ModPlayer
import chat.allay.modcore.tools.Features
import chat.allay.modcore.tools.ToolContext
import chat.allay.modcore.tools.Tools

import com.mojang.authlib.GameProfile
import io.netty.channel.embedded.EmbeddedChannel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.Connection
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.network.CommonListenerCookie
import net.minecraft.world.level.GameType
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * Real players for the game tests: a survival player on an embedded connection, who joins as a client would, so every
 * join hook of Allay runs. They remember the chat they got, and the packets the server sent them wait in [channel].
 */
object Players {
  class Survivor(server: MinecraftServer, level: ServerLevel, cookie: CommonListenerCookie) :
    ServerPlayer(server, level, cookie.gameProfile(), cookie.clientInformation()) {
    /** Everything Allay or the game said to this player in chat, as plain text. */
    val chat = mutableListOf<String>()

    lateinit var channel: EmbeddedChannel

    override fun sendSystemMessage(message: Component) {
      chat += message.string
      super.sendSystemMessage(message)
    }

    /** The packets the server sent this player since the last look. */
    fun packets(): List<Any> = generateSequence { channel.readOutbound<Any>() }.toList()
  }

  /** A survival player standing at [at] in the test's structure, with a name of their own for commands. */
  fun survivor(helper: GameTestHelper, at: Vec3 = Vec3(1.5, 2.0, 1.5)): Survivor {
    val server = helper.level.server
    val cookie = CommonListenerCookie.createInitial(UUID.randomUUID().let { GameProfile(it, "al-${it.toString().take(8)}") }, false)
    val player = Survivor(server, helper.level, cookie)
    val connection = Connection(PacketFlow.SERVERBOUND)
    player.channel = EmbeddedChannel(connection)
    server.playerList.placeNewPlayer(connection, player, cookie)
    player.setGameMode(GameType.SURVIVAL)
    player.connection.handleAcceptPlayerLoad(ServerboundPlayerLoadedPacket())
    val world = helper.absoluteVec(at)
    player.teleportTo(world.x, world.y, world.z)
    return player
  }

  /** Allay's tools' stores on the test server. */
  val features: Features get() = (Mod.allay!!.game as ModGame).features

  /** A run's view for [player], as the backend client builds it for a question. */
  fun context(player: ServerPlayer) = ToolContext(ModPlayer(player), features)

  /** Runs the tool called [name] for [player], as the backend would ask it. */
  fun run(player: ServerPlayer, name: String, args: JsonObject = buildJsonObject {}): ToolOutput = run(context(player), name, args)

  fun run(context: ToolContext, name: String, args: JsonObject = buildJsonObject {}): ToolOutput =
    Tools.all().single { it.name == name }.run(context, args)
}
