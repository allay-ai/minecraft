package chat.allay.fabric.gametest

import chat.allay.fabric.AllayMod

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.world.level.storage.LevelResource

/**
 * A real client in a fresh singleplayer world: Allay starts on the game's own server as `integrated`, keeps the
 * world's server id inside its save and everything that goes with the license in `config/allay/`, and answers
 * `/allay` in chat. `./gradlew :fabric:runClientGameTest` opens a game window.
 */
class SingleplayerClientTest : FabricClientGameTest {
  override fun runTest(context: ClientGameTestContext) {
    context.runOnClient<RuntimeException> { it.options.languageCode = "en_us" }
    context.worldBuilder().create().use { world ->
      world.connection.waitForChunksRender()
      val (side, inSave, shared) = world.server.computeOnServer<Triple<String?, Boolean, Boolean>, RuntimeException> { server ->
        val platform = AllayMod.platform
        val folder = platform?.folder?.toPath()
        Triple(
          AllayMod.allay?.game?.side,
          folder?.startsWith(server.getWorldPath(LevelResource.ROOT).normalize()) == true,
          platform?.shared?.resolve("config.yml")?.exists() == true && platform.shared.resolve("license.key").parentFile == FabricLoader.getInstance().configDir.resolve("allay").toFile(),
        )
      }
      check(side == "integrated") { "Allay runs as $side in a singleplayer world, not integrated" }
      check(inSave) { "the world's server id isn't inside the world's save" }
      check(shared) { "config.yml and license.key aren't shared in config/allay/" }
      world.server.runCommand("allay status")
      context.waitTicks(20)
    }
  }
}
