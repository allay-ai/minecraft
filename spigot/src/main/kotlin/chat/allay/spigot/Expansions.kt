package chat.allay.spigot

import chat.allay.expansion.AllayExpansion
import chat.allay.expansion.AllayExpansions

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.plugin.Plugin
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * Expansions plugins name in `META-INF/services/chat.allay.expansion.AllayExpansion`, found in each plugin's own jar
 * when it's enabled (and in every plugin already on at the first tick, since Allay loads before them), and taken out
 * again when it's disabled. A plugin that registers in code needs none of this.
 */
class Expansions(private val self: Plugin, private val log: Logger) : Listener {
  /** The expansions each plugin brought, by plugin name. */
  private val found = ConcurrentHashMap<String, List<String>>()

  /** Every plugin that's on now. */
  fun scanAll() = self.server.pluginManager.plugins.filter { it.isEnabled && it != self }.forEach(::scan)

  @EventHandler
  fun enabled(event: PluginEnableEvent) {
    if (event.plugin != self) scan(event.plugin)
  }

  @EventHandler
  fun disabled(event: PluginDisableEvent) {
    found.remove(event.plugin.name)?.forEach(AllayExpansions::unregister)
  }

  private fun scan(plugin: Plugin) {
    if (found.containsKey(plugin.name)) return
    val loader = plugin.javaClass.classLoader
    val ids = mutableListOf<String>()
    ServiceLoader.load(AllayExpansion::class.java, loader).stream()
      // The plugin's own classes only: the server's class loader above it is everyone's.
      .filter { it.type().classLoader == loader }
      .forEach { provider ->
        runCatching { provider.get().also(AllayExpansions::register) }
          .onSuccess { ids += it.id() }
          .onFailure { log.warning("❌ Couldn't start ${plugin.name}'s expansion ${provider.type().name}: ${it.message ?: it.javaClass.simpleName}") }
      }
    found[plugin.name] = ids
  }
}
