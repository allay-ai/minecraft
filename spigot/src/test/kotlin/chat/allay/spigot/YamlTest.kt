package chat.allay.spigot

import org.bukkit.configuration.file.YamlConfiguration
import chat.allay.core.Config
import chat.allay.core.Texts
import chat.allay.core.YamlConfig
import kotlin.test.Test
import kotlin.test.assertEquals

/** The core reads YAML without Bukkit; these check it reads the plugin's files exactly as Bukkit did. */
class YamlTest {
  private fun resource(path: String) = javaClass.getResourceAsStream(path)!!.reader(Charsets.UTF_8).readText()

  private fun bukkitFlatten(text: String): Map<String, Any> {
    val yaml = YamlConfiguration().apply { loadFromString(text) }
    return buildMap {
      for (key in yaml.getKeys(true)) {
        if (yaml.isConfigurationSection(key)) continue
        when (val value = yaml.get(key)) {
          is List<*> -> put(key, value.map { it.toString() })
          null -> {}
          else -> put(key, value.toString())
        }
      }
    }
  }

  @Test fun `language files read the same`() {
    for (code in Texts.BUNDLED) {
      val text = resource("/lang/$code.yml")
      assertEquals(bukkitFlatten(text), Texts.read(text.reader()), "lang/$code.yml")
    }
  }

  private fun bukkitConfig(file: String): YamlConfiguration = YamlConfiguration().apply {
    loadFromString(file)
    setDefaults(YamlConfiguration().apply { loadFromString(resource("/config.yml")) })
  }

  private fun coreConfig(file: String) = Config(YamlConfig(YamlConfig.parse(file), YamlConfig.parse(resource("/config.yml"))), "test")

  /** What the old Bukkit-based settings read from a file, key by key. */
  private fun snapshot(config: Config) = listOf(
    config.server.url, config.http.callTimeout, config.http.customHeaders, config.http.userAgent, config.ui.spinnerFrames, config.ui.frameMillis,
    config.ui.particleType, config.ui.particleCount, config.ui.particleHeightOffset, config.ui.bookAuthor,
    config.ui.successSound.name, config.ui.successSound.volume, config.ui.errorSound.pitch, config.ui.thinkingSound.name,
    config.tools.structureRadius, config.tools.maxGoals, config.tools.hiddenPlugins, config.tools.enabled("look_at"), config.tools.enabled("give_item"),
    config.askall.cooldownSeconds, config.time.zone, config.blockedCommands, config.locales.default, config.locales.sources, config.locales.countries,
    config.geoIp.enabled, config.geoIp.url, config.geoIp.refreshDays,
  )

  private fun bukkitSnapshot(yaml: YamlConfiguration): List<Any?> {
    val operator = yaml.getConfigurationSection("tools.operator")
    val switches = yaml.getConfigurationSection("tools.enabled")
    return listOf(
      yaml.getString("server.url", "https://allay.chat"), yaml.getInt("http.call_timeout", 300).toLong(),
      yaml.getConfigurationSection("http.custom_headers").let { it?.getKeys(false)?.associateWith(it::getString) }.orEmpty().filterKeys { !it.equals("X-Plugin-Version", ignoreCase = true) },
      "Allay-Spigot/test (Minecraft Plugin)", yaml.getString("ui.action_bar.spinner", "·✢✳✶✻✽"), yaml.getInt("ui.action_bar.frame_millis", 120).toLong().coerceAtLeast(50),
      yaml.getString("ui.particles.type"), yaml.getInt("ui.particles.count", 3), yaml.getDouble("ui.particles.height_offset", 2.0), yaml.getString("ui.book_author", "Allay"),
      yaml.getString("ui.sounds.success_sound"), yaml.getDouble("ui.sounds.success_volume", 1.0).toFloat(), yaml.getDouble("ui.sounds.error_pitch", 0.9).toFloat(), yaml.getString("ui.sounds.thinking_sound"),
      yaml.getInt("tools.locate_structure_radius", 64).coerceIn(1, 128), yaml.getInt("tools.goals.max_per_player", 5).coerceIn(1, 20),
      yaml.getStringList("tools.server.hidden_plugins").map(String::lowercase).toSet(), switches?.getBoolean("look_at", true) ?: true,
      (operator?.getBoolean("enabled", true) ?: true) && (operator?.getBoolean("give_item", true) ?: true),
      yaml.getInt("askall.cooldown_seconds", 30).toLong().coerceAtLeast(0), yaml.getString("time.zone", "Europe/Moscow"),
      yaml.getStringList("blocked_commands").map(String::lowercase).toSet(), yaml.getString("locales.default", "en"),
      yaml.getStringList("locales.sources").map(String::lowercase).ifEmpty { listOf("ip", "client") },
      yaml.getConfigurationSection("locales.countries")?.let { section -> section.getKeys(false).associateWith(section::getStringList) },
      yaml.getBoolean("geoip.enabled", true), yaml.getString("geoip.url", "https://download.db-ip.com/free/dbip-country-lite-{year}-{month}.mmdb.gz"), yaml.getInt("geoip.refresh_days", 30),
    )
  }

  @Test fun `settings read the same as with Bukkit`() {
    val files = listOf(
      resource("/config.yml"),
      "",
      // An old, partial config with odd values: missing sections, numbers as text, a section where a value goes.
      """
      server:
        url: http://localhost:3000
      http:
        call_timeout: "60"
        custom_headers:
          X-Plugin-Version: "1"
          X-Key: abc
      ui:
        book_author: 42
        sounds:
          success_sound: entity_allay_item_given
          success_volume: 1
      tools:
        enabled:
          look_at: false
        operator: false
      locales:
        sources: client
        countries:
          ru: [ RU ]
      blocked_commands: [ Op, 12, true ]
      geoip:
        refresh_days: 7.5
      """.trimIndent(),
    )
    for (file in files) {
      val core = snapshot(coreConfig(file))
      val bukkit = bukkitSnapshot(bukkitConfig(file))
      // The time zone check and Locales' fallback happen after reading; compare what was read.
      assertEquals(bukkit.size, core.size)
      bukkit.forEachIndexed { index, value ->
        val expected = when (index) {
          20 -> (value as String).takeIf { chat.allay.core.tools.TimeZones.parse(it) != null } ?: "UTC"
          24 -> value ?: core[24]
          else -> value
        }
        assertEquals(expected, core[index], "value $index of:\n$file")
      }
    }
  }
}
