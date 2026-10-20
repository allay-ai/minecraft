package chat.allay.core

import chat.allay.core.tools.EXPERIMENTAL_TOOLS
import chat.allay.core.tools.OPERATOR_TOOLS
import chat.allay.core.tools.TimeZones

/**
 * Settings from `config.yml`. Every key has a default; texts live in `lang/`, so the message keys of 2.3 configs are ignored.
 * Sounds and particles stay names here: what a name means is up to the platform.
 */
class Config(private val config: YamlConfig, private val pluginVersion: String) {
  val server = Server() ; inner class Server {
    val url = config.string("server.url", "https://allay.chat")
    /** A self-hosted backend's `PLUGIN_TOKEN` (since 6.0); empty for a hosted one, which knows the server by its license. */
    val token = config.string("server.token", "").trim()
  }

  /** Who pays for the model: the backend's key with a subscription (hosted), or the server's own key (byok). */
  val provider = Provider() ; inner class Provider {
    /** Anything but `byok` is hosted, so a typo never sends a key nobody meant to send. */
    val mode = if (config.string("provider.mode", HOSTED).trim().equals(BYOK, ignoreCase = true)) BYOK else HOSTED
    val byok get() = mode == BYOK
    val baseUrl = config.string("provider.byok.base_url", "https://openrouter.ai/api/v1").trim().trimEnd('/')
    val apiKey = config.string("provider.byok.api_key", "").trim()
    val model = config.string("provider.byok.model", "").trim()
    /** Empty: runs get no web search. */
    val searchModel = config.string("provider.byok.search_model", "").trim()
    /** A key from before 5.0, sent as is through `http.custom_headers`; the backend reads it as BYOK on OpenRouter. */
    val legacyKey: String? get() = http.customHeaders.entries.firstOrNull { it.key.equals("X-OpenRouter-Token", ignoreCase = true) }?.value?.trim()?.takeIf { it.isNotEmpty() }
  }

  val http = Http() ; inner class Http {
    val callTimeout = config.int("http.call_timeout", 300).toLong()
    val connectTimeout = config.int("http.connect_timeout", 10).toLong()
    val readTimeout = config.int("http.read_timeout", 300).toLong()
    val writeTimeout = config.int("http.write_timeout", 10).toLong()
    val maxIdleConnections = config.int("http.max_idle_connections", 5)
    val keepAliveDuration = config.int("http.keep_alive_duration", 300).toLong()
    val maxRequestsPerHost = config.int("http.max_requests_per_host", 5)
    val followRedirects = config.boolean("http.follow_redirects", true)
    val retryOnConnectionFailure = config.boolean("http.retry_on_connection_failure", true)
    val compressionEnabled = config.boolean("http.compression_enabled", true)
    val logging = config.boolean("http.logging", false)
    // Configs before 2.4 pinned an old version here; the plugin's own version wins now.
    val userAgent = "Allay-Spigot/$pluginVersion (Minecraft Plugin)"
    val toolTimeout = config.int("http.tool_timeout", 10).toLong()
    val customHeaders = config.section("http.custom_headers")
      .let { section -> section?.keys?.associateWith { section.string(it) } }.orEmpty()
      .filterKeys { !it.equals("X-Plugin-Version", ignoreCase = true) }
  }

  val ui = UI() ; inner class UI {
    val actionBarEnabled = config.boolean("ui.action_bar.enabled", true)
    val spinnerFrames = config.string("ui.action_bar.spinner", "·✢✳✶✻✽")
    val frameMillis = config.int("ui.action_bar.frame_millis", 120).toLong().coerceAtLeast(50)
    val verbSeconds = config.int("ui.action_bar.verb_seconds", 3).toLong()
    val toolLines = config.boolean("ui.tool_lines.enabled", true)

    val particlesEnabled = config.boolean("ui.particles.enabled", true)
    /** The particle's name as the config spells it; null means the default. */
    val particleType = config.string("ui.particles.type")
    val particleCount = config.int("ui.particles.count", 3)
    val particleHeightOffset = config.double("ui.particles.height_offset", 2.0)
    val particleAnimationInterval = config.int("ui.particles.animation_interval", 250).toLong()

    val chatStatusEnabled = config.boolean("ui.chat_status.enabled", false)
    val bookAuthor = config.string("ui.book_author", "Allay")

    val soundsEnabled = config.boolean("ui.sounds.enabled", true)
    val successSound = sound("success", 1.0, 1.0)
    val errorSound = sound("error", 1.0, 0.9)
    val thinkingSound = sound("thinking", 1.0, 1.0)
    /** How far each sound's pitch may move up or down, so repeated answers don't sound mechanical. */
    val pitchVariation = config.double("ui.sounds.pitch_variation", 0.1).toFloat().coerceIn(0f, 1f)
    /** Everyone who reads a public answer hears a quiet success sound. */
    val publicAnswerSound = config.boolean("ui.sounds.public_answer", true)

    private fun sound(name: String, volume: Double, pitch: Double) = Sound(
      key = "ui.sounds.${name}_sound",
      name = config.string("ui.sounds.${name}_sound"),
      volume = config.double("ui.sounds.${name}_volume", volume).toFloat(),
      pitch = config.double("ui.sounds.${name}_pitch", pitch).toFloat(),
    )
  }

  /** A sound by the name the config gives under [key]; a null [name] means the platform's default. */
  class Sound(val key: String, val name: String?, val volume: Float, val pitch: Float)

  val tools = Tools() ; inner class Tools {
    private val switches = config.section("tools.enabled")
    private val operator = config.section("tools.operator")
    val structureRadius = config.int("tools.locate_structure_radius", 64).coerceIn(1, 128)
    val maxReminders = config.int("tools.reminders.max_pending", 10).coerceIn(1, 100)
    val maxNotes = config.int("tools.notes.max_per_player", 200).coerceIn(1, 1000)
    val maxGoals = config.int("tools.goals.max_per_player", 5).coerceIn(1, 20)
    /** The longest trail `point_to` may draw, and the length when nobody names one (also for point buttons). */
    val trailMaxSeconds = config.int("tools.point_to.max_seconds", 600).coerceIn(10, 3600)
    val trailSeconds = config.int("tools.point_to.default_seconds", 30).coerceIn(1, trailMaxSeconds)
    /** A trail to another player when nobody names a length, also for their way buttons: they move, so it lasts longer. */
    val followSeconds = config.int("tools.point_to.follow_seconds", 180).coerceIn(1, trailMaxSeconds)
    val biomeRadius = config.int("tools.locate_biome_radius", 2048).coerceIn(64, 6400)
    /** `show_marks`: how many at once, how far from the player, and how long they stay when nobody says, and at most. */
    val maxMarks = config.int("tools.show_marks.max_marks", 64).coerceIn(1, 256)
    val marksRadius = config.int("tools.show_marks.radius", 48).coerceIn(8, 128)
    val marksMaxSeconds = config.int("tools.show_marks.max_seconds", 600).coerceIn(10, 3600)
    val marksSeconds = config.int("tools.show_marks.default_seconds", 30).coerceIn(1, marksMaxSeconds)
    val villagerRadius = config.int("tools.villager_radius", 32).coerceIn(8, 64)
    /** Plugins `get_server` leaves out of its list, by name, in any case. */
    val hiddenPlugins = config.strings("tools.server.hidden_plugins").map(String::lowercase).toSet()

    /**
     * Missing keys mean on: an old config gets every tool, except the experimental ones in [EXPERIMENTAL_TOOLS], which
     * stay off until a server turns them on. Operator tools also need `tools.operator.enabled`.
     */
    fun enabled(name: String) = if (name in OPERATOR_TOOLS) {
      (operator?.boolean("enabled", true) ?: true) && (operator?.boolean(name, true) ?: true)
    } else {
      switches?.boolean(name, name !in EXPERIMENTAL_TOOLS) ?: (name !in EXPERIMENTAL_TOOLS)
    }
  }

  /**
   * Automation agents: background agents that sort chests, smelt, brew, and craft for a player near their base.
   * Off as a whole with `automation.enabled`, or one kind at a time; permissions narrow it further.
   */
  val automation = Automation() ; inner class Automation {
    val enabled = config.boolean("automation.enabled", true)
    fun on(kind: chat.allay.core.tasks.Kind) = enabled && config.boolean("automation.${kind.wire}.enabled", true)
    /** Agents one player may run at once, sub-agents included. */
    val maxTasks = config.int("automation.max_tasks", 2).coerceIn(1, 8)
    val maxMinutes = config.int("automation.max_minutes", 30).coerceIn(1, 240)
    /** Wakes one agent may use: each is a request to the model, so this bounds what a task costs. */
    val maxWakes = config.int("automation.max_wakes", 40).coerceIn(2, 200)
    val maxWaitSeconds = config.int("automation.max_wait_seconds", 600).coerceIn(10, 3600)
    /** How far from the player furnaces, brewing stands, crafting tables, and chests to take from may be. */
    val radius = config.int("automation.radius", 32).coerceIn(4, 52)
    /** An agent pauses while its player is farther than this from where it started, or in another dimension. */
    val pauseDistance = config.int("automation.pause_distance", 64).coerceIn(8, 256)
    /** A furnace, stand, or crafting table counts only when no opaque full block stands between it and the player's eyes; glass and leaves don't hide it. */
    val lineOfSight = config.boolean("automation.line_of_sight", true)
    /** “Sort all”: chests reached from the picked ones across gaps of up to this many blocks, at most [maxChests]. */
    val chestGap = config.int("automation.chests.gap", 6).coerceIn(1, 16)
    val maxChests = config.int("automation.chests.max", 64).coerceIn(1, 256)
    val undoMinutes = config.int("automation.chests.undo_minutes", 5).coerceIn(1, 60)
  }

  /** Spending limits for the server and its players, whoever pays; see [QuotaSettings]. */
  val quota = QuotaSettings.parse(config)

  /** A model per tier of players; see [ModelTiers]. */
  val models = ModelTiers.parse(config)

  val privacy = Privacy() ; inner class Privacy {
    /** Ask each player to agree before their first question; the backend keeps the answer (since 6.1). */
    val consent = config.boolean("privacy.consent", true)
    /** The page the question links to; empty means allay.chat's policy, or none with a backend of your own. */
    val policyUrl = config.string("privacy.policy_url", "").trim().ifEmpty { if (server.token.isEmpty()) POLICY_URL else "" }
  }

  val askall = AskAll() ; inner class AskAll {
    val cooldownSeconds = config.int("askall.cooldown_seconds", 30).toLong().coerceAtLeast(0)
  }

  val time = Time() ; inner class Time {
    val zone = config.string("time.zone", "Europe/Moscow").let { zone -> zone.takeIf { TimeZones.parse(it) != null } ?: "UTC" }
  }

  val blockedCommands: Set<String> = config.strings("blocked_commands").map(String::lowercase).toSet()

  class Locales(val default: String, val sources: List<String>, val countries: Map<String, List<String>>)

  val locales = Locales(
    default = config.string("locales.default", "en"),
    sources = config.strings("locales.sources").map(String::lowercase).ifEmpty { listOf("ip", "client") },
    countries = config.section("locales.countries")?.let { section -> section.keys.associateWith(section::strings) }
      ?: DEFAULT_COUNTRIES,
  )

  class GeoIp(val enabled: Boolean, val url: String, val refreshDays: Int)

  val geoIp = GeoIp(
    enabled = config.boolean("geoip.enabled", true),
    url = config.string("geoip.url", "https://download.db-ip.com/free/dbip-country-lite-{year}-{month}.mmdb.gz"),
    refreshDays = config.int("geoip.refresh_days", 30),
  )

  companion object {
    const val HOSTED = "hosted"
    const val BYOK = "byok"
    const val POLICY_URL = "https://allay.chat/privacy/"

    private val DEFAULT_COUNTRIES = mapOf(
      "ru" to listOf("RU", "BY", "KZ", "KG", "TJ", "UZ", "AM", "AZ", "MD", "GE", "TM"),
      "es" to listOf("ES", "MX", "AR", "CO", "CL", "PE", "VE", "EC", "GT", "CU", "BO", "DO", "HN", "PY", "SV", "NI", "CR", "PA", "UY", "PR", "GQ"),
      "pt" to listOf("BR", "PT", "AO", "MZ", "CV", "GW", "ST", "TL"),
      "de" to listOf("DE", "AT", "CH", "LI", "LU"),
    )
  }
}
