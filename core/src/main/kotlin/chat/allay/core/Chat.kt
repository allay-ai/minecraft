package chat.allay.core

/** Permissions as the plugin names them; each platform checks them its own way. */
object Permissions {
  const val ASK = "allay.ask"
  const val ASKX = "allay.askx"
  const val ASKALL = "allay.askall"
  const val ASKALL_NO_COOLDOWN = "allay.askall.nocooldown"
  const val CHAT = "allay.chat"
  const val RELOAD = "allay.reload"
  /** Paying for Allay and seeing why a player got their language: `/allay link`, `/allay status`, `/allay locale`. */
  const val ADMIN = "allay.admin"
  /** Automation agents at all; each kind also needs its own, `allay.automation.chests` and so on. */
  const val AUTOMATION = "allay.automation"
  fun automation(kind: chat.allay.core.tasks.Kind) = "$AUTOMATION.${kind.wire}"
  /** A tier of `quota.tiers` in config.yml: `allay.quota.vip`. */
  fun quota(tier: String) = "allay.quota.$tier"
  /** A tier of `models` in config.yml: `allay.model.vip`. */
  fun model(tier: String) = "allay.model.$tier"
  /** Builds from Allay's catalog: the preview, and building it from the player's blocks (since 6.37). */
  const val BUILD = "allay.build"
  /** A tier of `building.tiers` in config.yml: `allay.build.vip`; `allay.build.unlimited` lifts the size limits. */
  fun build(tier: String) = "$BUILD.$tier"

  /**
   * Whether a player without a permissions plugin or mod has [permission]: operators get everything, everyone else
   * what `plugin.yml` gives by default. The mods read it; Bukkit reads `plugin.yml` itself.
   */
  fun byDefault(permission: String, operator: Boolean): Boolean = when {
    operator -> true
    permission in OPERATORS -> false
    // Tiers are for the players a server picks.
    permission.startsWith("allay.quota.") || permission.startsWith("allay.model.") || permission.startsWith("$BUILD.") -> false
    else -> permission.startsWith("allay.")
  }

  private val OPERATORS = setOf(ASKALL_NO_COOLDOWN, ASKX, RELOAD, ADMIN)

  /**
   * Every permission Allay may ask about, for a permissions API that wants each named up front (NeoForge's): the fixed
   * ones, each kind of automation, and the tiers [config] names in `quota.tiers` and `models`.
   */
  fun all(config: YamlConfig?): List<String> {
    val tiers = config?.let {
      QuotaSettings.parse(it).tiers.keys.map(::quota) + ModelTiers.parse(it).models.keys.map(::model) +
        chat.allay.core.building.BuildTiers.parse(it).tiers.keys.filter { tier -> tier != chat.allay.core.building.BuildTiers.DEFAULT }.map(::build)
    }.orEmpty()
    return (listOf(ASK, ASKX, ASKALL, ASKALL_NO_COOLDOWN, CHAT, RELOAD, ADMIN, AUTOMATION, BUILD, build(chat.allay.core.building.BuildTiers.UNLIMITED)) +
      chat.allay.core.tasks.Kind.entries.map(::automation) + tiers).distinct()
  }
}

/** Chat mode's prefixes: where a line a player writes in chat goes, by its first character. */
object Chat {
  enum class Route { ASK, ASKX, PUBLIC, CHAT }

  /** `!text` and `! text` alike: the prefix, then the rest without the spaces around it. */
  fun route(message: String): Pair<Route, String> {
    val line = message.trim()
    val route = when (line.firstOrNull()) {
      '!' -> Route.ASKX
      '?' -> Route.PUBLIC
      '~' -> Route.CHAT
      else -> return Route.ASK to line
    }
    return route to line.drop(1).trim()
  }
}
