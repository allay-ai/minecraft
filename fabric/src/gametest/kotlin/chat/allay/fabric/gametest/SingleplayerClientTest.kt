package chat.allay.fabric.gametest

import chat.allay.core.tools.TrailClock
import chat.allay.modcore.Mod
import chat.allay.modcore.tools.Trails

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.renderer.item.properties.numeric.CompassAngleState
import net.minecraft.core.BlockPos
import net.minecraft.core.GlobalPos
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.LevelResource
import net.minecraft.world.phys.Vec3

/**
 * A real client in a fresh singleplayer world: Allay starts on the game's own server as `integrated`, keeps the
 * world's server id inside its save and everything that goes with the license in `config/allay/`, and answers
 * `/allay` in chat, and points the compass in the nether; then a dedicated server with Allay, where the player's commands go to the server, not to the mod
 * in their game. `./gradlew :fabric:runClientGameTest` opens a game window.
 */
class SingleplayerClientTest : FabricClientGameTest {
  override fun runTest(context: ClientGameTestContext) {
    context.runOnClient<RuntimeException> { it.options.languageCode = "en_us" }
    context.worldBuilder().create().use { world ->
      world.connection.waitForChunksRender()
      val (side, inSave, shared) = world.server.computeOnServer<Triple<String?, Boolean, Boolean>, RuntimeException> { server ->
        val platform = Mod.platform
        val folder = platform?.folder?.toPath()
        Triple(
          Mod.allay?.game?.side,
          folder?.startsWith(server.getWorldPath(LevelResource.ROOT).normalize()) == true,
          platform?.shared?.resolve("config.yml")?.exists() == true && platform.shared.resolve("license.key").parentFile == FabricLoader.getInstance().configDir.resolve("allay").toFile(),
        )
      }
      check(side == "integrated") { "Allay runs as $side in a singleplayer world, not integrated" }
      check(inSave) { "the world's server id isn't inside the world's save" }
      check(shared) { "config.yml and license.key aren't shared in config/allay/" }
      val admin = world.server.computeOnServer<Boolean, RuntimeException> { server ->
        chat.allay.modcore.Perms.check(server.playerList.players.single(), chat.allay.core.Permissions.ADMIN)
      }
      check(admin) { "the world's owner can't link a license" }
      check(context.computeOnClient<Boolean, RuntimeException> { !chat.allay.fabric.client.AllayClient.mine() }) { "the mod in the game answers instead of the game's own server" }
      world.server.runCommand("allay status")
      context.waitTicks(20)
      compassInTheNether(context, world)
    }

    // A dedicated server with Allay of its own: the player's /allay goes to it, never to the mod in their game.
    context.worldBuilder().createServer().use { server ->
      server.connect().use { connection ->
        connection.waitForChunksRender()
        val local = context.computeOnClient<Boolean, RuntimeException> { chat.allay.fabric.client.AllayClient.mine() }
        check(!local) { "the mod in the player's game answered for a server that has Allay" }
      }
    }
  }

  /**
   * A way trail in the nether points the compass there, as the client reads it for the compass's needle, and its end
   * gives the compass back to the world spawn, where it spins in the nether as the game's own does.
   */
  private fun compassInTheNether(context: ClientGameTestContext, world: TestSingleplayerContext) {
    world.server.runCommand("execute in minecraft:the_nether run tp @a 0 120 0")
    context.waitTicks(40)
    val spot = BlockPos(100, 64, 0)
    world.server.runOnServer<RuntimeException> { server ->
      val player = server.playerList.players.single()
      Trails.show(Mod.platform!!.scheduler, player, Trails.Target(Level.NETHER, Vec3.atBottomCenterOf(spot)), TrailClock.fixed(60))
    }
    context.waitTicks(5)
    val target = { context.computeOnClient<GlobalPos?, RuntimeException> { client ->
      CompassAngleState.CompassTarget.SPAWN.get(client.level!!, ItemStack(Items.COMPASS), client.player)
    } }
    val during = target()
    check(context.computeOnClient<Boolean, RuntimeException> { it.level!!.dimension() == Level.NETHER }) { "the player isn't in the nether" }
    check(during == GlobalPos.of(Level.NETHER, spot)) { "the compass in the nether points at $during, not the trail's spot" }
    world.server.runOnServer<RuntimeException> { server -> Trails.stop(server.playerList.players.single()) }
    context.waitTicks(5)
    val after = target()
    check(after?.dimension() == Level.OVERWORLD) { "after the trail the compass points at $after, not the world spawn" }
  }
}
