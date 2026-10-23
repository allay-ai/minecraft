package chat.allay.neoforge.gametest

import chat.allay.core.Permissions
import chat.allay.modcore.Elsewhere
import chat.allay.modcore.Folders
import chat.allay.modcore.Mod
import chat.allay.modcore.Perms
import chat.allay.neoforge.client.AllayClient

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.world.level.GameType
import net.minecraft.world.level.LevelSettings
import net.minecraft.world.level.WorldDataConfiguration
import net.minecraft.world.level.levelgen.WorldOptions
import net.minecraft.world.level.levelgen.presets.WorldPresets
import net.minecraft.world.level.storage.LevelResource
import net.neoforged.api.distmarker.Dist
import net.neoforged.fml.ModLoader
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.neoforge.client.gui.LoadingErrorScreen
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.common.NeoForge
import org.slf4j.LoggerFactory
import java.io.File

/**
 * A real client in a fresh singleplayer world without cheats, as Fabric's client game test opens one: Allay starts on
 * the game's own server as `integrated`, keeps the world's server id inside its save and everything that goes with the
 * license in `config/allay/`, lets the world's owner link a license, and leaves `/allay` to the game's own server,
 * whose command tree the mod read. The result goes to `allay-check.txt` in the run folder, then the game closes;
 * `./gradlew :neoforge:runClientGameTest` fails unless it says `ok`.
 */
object ClientCheck {
  fun start() {
    if (System.getProperty("allay.clientGameTest") != "true" || FMLEnvironment.getDist() != Dist.CLIENT) return
    Client.start()
  }

  private object Client {
    private var created = false
    private var joinedTicks = 0
    private var done = false
    private var closeIn = -1
    private var ticks = 0
    private val early = mutableListOf<String>()
    private val log = LoggerFactory.getLogger("Allay check")

    fun start() {
      NeoForge.EVENT_BUS.addListener { _: ClientTickEvent.Post -> tick() }
    }

    private fun tick() {
      val game = Minecraft.getInstance()
      if (done) {
        // A second for `/allay status` to answer in the log, then the game closes.
        if (closeIn > 0 && --closeIn == 0) game.stop()
        return
      }
      // Three minutes is far more than a world takes; a check that never ran fails.
      if (++ticks > 20 * 180) return finish(game, listOf("the world didn't open within three minutes"))
      // NeoForge lists loading warnings first; another mod's are its own business, Allay's fail the check.
      if (!created && game.gui.screen() is LoadingErrorScreen) {
        ModLoader.getLoadingIssues().filter { it.affectedMod()?.modId?.startsWith("allay") == true }.forEach { early += "NeoForge warns about Allay: $it" }
        game.gui.setScreen(TitleScreen())
        return
      }
      // A fresh game folder asks about accessibility first, as a player's first start does.
      if (!created && game.gui.screen() is AccessibilityOnboardingScreen) {
        game.options.onboardingAccessibilityFinished()
        game.gui.setScreen(TitleScreen())
        return
      }
      if (!created && game.gui.screen() is TitleScreen) {
        created = true
        if (!Elsewhere.mine(singleplayer = false)) early += "the mod leaves its commands to a server before any server said it has Allay"
        val name = "allay-check-${System.currentTimeMillis()}"
        val settings = LevelSettings(name, GameType.SURVIVAL, LevelSettings.DifficultySettings.DEFAULT, false, WorldDataConfiguration.DEFAULT)
        game.createWorldOpenFlows().createFreshLevel(name, settings, WorldOptions.defaultWithRandomSeed(), WorldPresets::createNormalWorldDimensions, TitleScreen())
        return
      }
      val server = game.singleplayerServer ?: return
      if (game.player == null || ++joinedTicks < 40) return
      done = true
      val problems = runCatching {
        val onServer = server.submit<List<String>> {
          val platform = Mod.platform
          val owner = server.playerList.players.single()
          listOfNotNull(
            "Allay runs as ${Mod.allay?.game?.side} in a singleplayer world, not integrated".takeUnless { Mod.allay?.game?.side == "integrated" },
            "the world's server id isn't inside the world's save".takeUnless { platform?.folder?.toPath()?.startsWith(server.getWorldPath(LevelResource.ROOT).normalize()) == true },
            "config.yml isn't shared in config/allay/".takeUnless { platform?.shared == Folders.shared && File(Folders.shared, "config.yml").exists() },
            "the world's owner can't link a license".takeUnless { Perms.check(owner, Permissions.ADMIN) },
            "the world's owner gets operator rights without cheats".takeIf { owner.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER) },
          )
        }.get()
        onServer + listOfNotNull(
          "the mod in the game answers instead of the game's own server".takeIf { AllayClient.mine() },
          "the mod didn't read the server's command tree, which has /allay".takeIf { Elsewhere.mine(singleplayer = false) },
        )
      }.getOrElse { listOf("the check failed: $it") }
      game.player?.connection?.sendCommand("allay status")
      finish(game, early + problems)
    }

    private fun finish(game: Minecraft, problems: List<String>) {
      done = true
      val result = if (problems.isEmpty()) "ok" else problems.joinToString("\n")
      log.info("Singleplayer check: {}", result)
      File(game.gameDirectory, "allay-check.txt").writeText(result + "\n")
      closeIn = 20
    }
  }
}
