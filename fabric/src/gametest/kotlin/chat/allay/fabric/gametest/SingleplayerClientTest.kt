package chat.allay.fabric.gametest

import chat.allay.fabric.AllayMod

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.world.level.storage.LevelResource

/**
 * A real client in a fresh singleplayer world: Allay starts on the game's own server as `integrated`, keeps its files
 * inside the world's save, and answers `/allay` in chat. `./gradlew :fabric:runClientGameTest` opens a game window.
 */
class SingleplayerClientTest : FabricClientGameTest {
  override fun runTest(context: ClientGameTestContext) {
    context.runOnClient<RuntimeException> { it.options.languageCode = "en_us" }
    context.worldBuilder().create().use { world ->
      world.connection.waitForChunksRender()
      val (side, inSave) = world.server.computeOnServer<Pair<String?, Boolean>, RuntimeException> { server ->
        val allay = AllayMod.allay
        val folder = AllayMod.platform?.folder?.toPath()
        allay?.game?.side to (folder?.startsWith(server.getWorldPath(LevelResource.ROOT).normalize()) == true)
      }
      check(side == "integrated") { "Allay runs as $side in a singleplayer world, not integrated" }
      check(inSave) { "Allay's files aren't inside the world's save" }
      world.server.runCommand("allay status")
      context.waitTicks(20)
    }
  }
}
