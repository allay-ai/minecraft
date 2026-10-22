package chat.allay.spigot

import chat.allay.core.Config
import chat.allay.core.License
import chat.allay.core.LicenseStatus
import chat.allay.core.PlayerSettings
import chat.allay.core.tools.Goal
import chat.allay.spigot.tools.Goals
import chat.allay.spigot.tools.Reminders
import com.djrapitops.plan.capability.CapabilityService
import com.djrapitops.plan.extension.CallEvents
import com.djrapitops.plan.extension.DataExtension
import com.djrapitops.plan.extension.ExtensionService
import com.djrapitops.plan.extension.FormatType
import com.djrapitops.plan.extension.NotReadyException
import com.djrapitops.plan.extension.annotation.BooleanProvider
import com.djrapitops.plan.extension.annotation.Conditional
import com.djrapitops.plan.extension.annotation.GroupProvider
import com.djrapitops.plan.extension.annotation.NumberProvider
import com.djrapitops.plan.extension.annotation.PercentageProvider
import com.djrapitops.plan.extension.annotation.PluginInfo
import com.djrapitops.plan.extension.annotation.StringProvider
import com.djrapitops.plan.extension.annotation.TableProvider
import com.djrapitops.plan.extension.icon.Color
import com.djrapitops.plan.extension.icon.Family
import com.djrapitops.plan.extension.icon.Icon
import com.djrapitops.plan.extension.table.Table
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.time.Instant
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Allay in Plan, when Plan is there: a block on each player's page (chat mode, goals, reminders, language)
 * and one in the server's “Plugins Overview” (who pays for the model, chat mode across players). Plan calls these
 * methods off the server thread, so everything here reads thread-safe state. Plan's labels are English: its
 * panel is for the server's admins, not for players.
 */
object Plan {
  val available: Boolean get() = Bukkit.getPluginManager().isPluginEnabled("Plan")

  class Sources(
    val settings: PlayerSettings,
    val goals: Goals,
    val reminders: Reminders,
    val locales: Locales,
    val license: License,
    val config: () -> Config,
    val subscription: () -> LicenseStatus?,
  )

  /** Registers the extension, again after `/plan reload`. Call only when [available]. Returns a handle that unregisters it. */
  fun register(plugin: JavaPlugin, sources: Sources): AutoCloseable {
    val capabilities = CapabilityService.getInstance()
    if (!REQUIRED.all(capabilities::hasCapability)) {
      plugin.logger.warning("This Plan is too old for Allay's page; Plan 5.8 or newer shows it")
      return AutoCloseable {}
    }
    val extension = Extension(sources)
    fun register() = runCatching { ExtensionService.getInstance().register(extension) }
      .onFailure { plugin.logger.warning("Couldn't add Allay to Plan: ${it.message}") }
    register()
    capabilities.registerEnableListener { enabled -> if (enabled && plugin.isEnabled) register() }
    return AutoCloseable { runCatching { ExtensionService.getInstance().unregister(extension) } }
  }

  private val REQUIRED = listOf("DATA_EXTENSION_VALUES", "DATA_EXTENSION_TABLES", "DATA_EXTENSION_GROUPS", "DATA_EXTENSION_SHOW_IN_PLAYER_TABLE")

  @PluginInfo(name = "Allay", iconName = "feather", iconFamily = Family.SOLID, color = Color.LIGHT_BLUE)
  class Extension(private val sources: Sources) : DataExtension {
    override fun callExtensionMethodsOn() = arrayOf(CallEvents.PLAYER_JOIN, CallEvents.PLAYER_LEAVE, CallEvents.SERVER_EXTENSION_REGISTER, CallEvents.SERVER_PERIODICAL)

    @BooleanProvider(text = "Chat mode", description = "What the player writes in chat goes to Allay", priority = 100,
      iconName = "comments", iconColor = Color.LIGHT_BLUE, showInPlayerTable = true)
    fun chatMode(playerUUID: UUID): Boolean = sources.settings.of(playerUUID).chat_mode

    @GroupProvider(text = "Chat mode", groupColor = Color.LIGHT_BLUE, iconName = "comments")
    fun chatModeGroup(playerUUID: UUID): Array<String> = arrayOf(if (sources.settings.of(playerUUID).chat_mode) "On" else "Off")

    @StringProvider(text = "Language", description = "The language Allay talks to the player in", priority = 90,
      iconName = "language", iconColor = Color.BLUE)
    fun language(playerUUID: UUID): String = sources.locales.known(playerUUID) ?: throw NotReadyException()

    @NumberProvider(text = "Goals", description = "Goals set through Allay, with a boss bar each", priority = 80,
      iconName = "bullseye", iconColor = Color.GREEN)
    fun goals(playerUUID: UUID): Long = sources.goals.snapshot(playerUUID).size.toLong()

    @TableProvider(tableColor = Color.GREEN, iconName = "bullseye")
    fun goalTable(playerUUID: UUID): Table {
      val table = Table.builder()
        .columnOne("Goal", Icon.called("bullseye").build())
        .columnTwo("Progress", Icon.called("chart-line").build())
        .columnThree("Done", Icon.called("percent").build())
      sources.goals.snapshot(playerUUID).forEach { goal -> table.addRow(goal.title, progress(goal), "${(goal.fraction * 100).roundToInt()}%") }
      return table.build()
    }

    @NumberProvider(text = "Reminders", description = "Reminders waiting; they last only while the player is online", priority = 60,
      iconName = "bell", iconColor = Color.AMBER)
    fun reminders(playerUUID: UUID): Long = sources.reminders.pending(playerUUID).toLong()

    @NumberProvider(text = "Players in chat mode", description = "Players who have chat mode on", priority = 100,
      iconName = "comments", iconColor = Color.LIGHT_BLUE)
    fun playersInChatMode(): Long = sources.settings.chatModeCount().toLong()

    @StringProvider(text = "Who pays for the model", description = "Set by provider.mode in config.yml and license.key", priority = 90,
      iconName = "wallet", iconColor = Color.TEAL)
    fun payer(): String {
      val provider = sources.config().provider
      return when {
        provider.byok -> if (provider.apiKey.isEmpty()) "The server's own key (not set)" else "The server's own key"
        sources.license.read() != null -> "Allay's subscription"
        provider.legacyKey != null -> "The key in http.custom_headers"
        else -> "The backend (no key or subscription here)"
      }
    }

    /** Shows the subscription's lines only when this server pays through one and the poll has seen it. */
    @BooleanProvider(text = "Has a subscription", conditionName = "subscription", hidden = true)
    fun hasSubscription(): Boolean = current() != null

    @BooleanProvider(text = "Subscription active", priority = 80, iconName = "check", iconColor = Color.GREEN)
    @Conditional("subscription")
    fun subscriptionActive(): Boolean = subscription().active

    @StringProvider(text = "Plan", priority = 70, iconName = "calendar", iconColor = Color.TEAL)
    @Conditional("subscription")
    fun subscriptionPlan(): String = subscription().plan ?: "none"

    @NumberProvider(text = "Paid until", priority = 60, format = FormatType.DATE_YEAR, iconName = "calendar-check", iconColor = Color.TEAL)
    @Conditional("subscription")
    fun paidUntil(): Long = subscription().paid_until?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: throw NotReadyException()

    @PercentageProvider(text = "Quota used", description = "Of this period's limit", priority = 50, iconName = "percent", iconColor = Color.AMBER)
    @Conditional("subscription")
    fun quotaUsed(): Double = (subscription().quota ?: throw NotReadyException()).used_percent / 100.0

    /** The subscription as Allay's 5-minute poll last saw it; not ready with a key of the server's own or before that poll. */
    private fun subscription(): LicenseStatus = current() ?: throw NotReadyException()

    private fun current(): LicenseStatus? = sources.subscription()?.takeIf { it.billing && !sources.config().provider.byok }

    /** Where the player stands, as the boss bar puts it, in numbers only. */
    private fun progress(goal: Goal): String = when (goal.kind) {
      "reach" -> "${max(0, goal.target - goal.progress)} blocks left"
      "advancement", "biome", "dimension" -> if (goal.done) "done" else "not yet"
      else -> "${goal.progress / goal.scale} / ${goal.target / goal.scale}"
    }
  }
}
