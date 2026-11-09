package chat.allay.spigot

import chat.allay.core.Config
import chat.allay.core.Tally
import org.bstats.bukkit.Metrics
import org.bstats.charts.AdvancedPie
import org.bstats.charts.SimplePie
import org.bstats.charts.SingleLineChart
import org.bukkit.plugin.java.JavaPlugin
import java.net.URI
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * bStats (bstats.org): how servers run Allay, in numbers only. bStats sends every 30 minutes from its own thread, so
 * every chart reads [Tally]'s counters or the config, never Bukkit. A failure anywhere here is a warning, once, and
 * never stops Allay.
 */
object Stats {
  /** Allay's plugin id on bStats: https://bstats.org/plugin/bukkit/AllayAI/35231 */
  const val BSTATS_ID = 35231

  /** Starts bStats unless `metrics: false`; returns a handle that stops it. */
  fun start(plugin: JavaPlugin, config: () -> Config, tally: Tally): AutoCloseable? {
    val warned = AtomicBoolean()
    fun <T> soft(fallback: T, read: () -> T) = Callable {
      try {
        read()
      } catch (error: Throwable) {
        if (warned.compareAndSet(false, true)) plugin.logger.warning("bStats: couldn't count something (${error.javaClass.simpleName}); the rest goes on")
        fallback
      }
    }
    return try {
      if (!config().metrics) return null
      val metrics = Metrics(plugin, BSTATS_ID)
      fun pie(id: String, read: () -> String?) = metrics.addCustomChart(SimplePie(id, soft(null, read)))
      fun pies(id: String, read: () -> Map<String, Int>) = metrics.addCustomChart(AdvancedPie(id, soft(emptyMap(), read)))
      fun line(id: String, read: () -> Int) = metrics.addCustomChart(SingleLineChart(id, soft(0, read)))

      pies("player_languages", tally::languages)
      pies("language_source", tally::sources)
      pie("provider_mode") { config().provider.mode }
      pie("byok_provider") { config().provider.takeIf { it.byok }?.let { provider(it.baseUrl) } }
      pie("backend") { backend(config().server.url) }
      line("questions", tally::questions)
      pies("questions_by_command", tally::commands)
      line("input_tokens", tally::inputThousands)
      line("output_tokens", tally::outputThousands)
      line("cost_cents", tally::cents)
      pies("answer_time", tally::answerTimes)
      AutoCloseable { runCatching { metrics.shutdown() } }
    } catch (error: Throwable) {
      plugin.logger.warning("bStats didn't start: ${error.javaClass.simpleName}")
      null
    }
  }

  /** Whose API a byok key pays: only the provider's name leaves, never the URL. */
  fun provider(baseUrl: String): String {
    val host = host(baseUrl) ?: return "other"
    return when {
      host == "openrouter.ai" || host.endsWith(".openrouter.ai") -> "openrouter"
      host == "openai.com" || host.endsWith(".openai.com") -> "openai"
      else -> "other"
    }
  }

  /** allay.chat or a backend of the server's own; never the URL. */
  fun backend(url: String): String = host(url)?.let { if (it == "allay.chat" || it.endsWith(".allay.chat")) "allay.chat" else null } ?: "self-hosted"

  private fun host(url: String): String? = runCatching { URI(url.trim()).host?.lowercase()?.removeSuffix(".") }.getOrNull()
}
