package chat.allay.modcore

import chat.allay.expansion.AllayExpansion
import chat.allay.expansion.AllayExpansions

import net.minecraft.server.MinecraftServer
import java.util.ServiceLoader
import java.util.logging.Logger

/**
 * Expansions other mods name in `META-INF/services/chat.allay.expansion.AllayExpansion`. Mods don't come and go while
 * the game runs, so they're found once; [AllayExpansions] keeps them for every server the game starts.
 */
object Expansions {
  @Volatile private var found = false

  fun discover(log: Logger) {
    if (found) return
    found = true
    // The game's class loader sees every mod's classes and files, on Fabric and NeoForge alike.
    ServiceLoader.load(AllayExpansion::class.java, MinecraftServer::class.java.classLoader).stream().forEach { provider ->
      runCatching { AllayExpansions.register(provider.get()) }
        .onFailure { log.warning("❌ Couldn't start the expansion ${provider.type().name}: ${it.message ?: it.javaClass.simpleName}") }
    }
  }
}
