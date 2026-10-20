package chat.allay.spigot

import chat.allay.core.*
import chat.allay.core.tools.*

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.md_5.bungee.api.chat.BaseComponent
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.HoverEvent
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.chat.hover.content.Text
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import chat.allay.spigot.Minecraft.formatMessage
import java.time.ZoneId

/**
 * `/allay link`, `/allay status`, `/allay locale`, and `/allay init` for operators, and the hint under an error a payment or the
 * config fixes. The payment link goes only to the chat of whoever asked; the key and the license never reach the log.
 */
class BillingCommands(
  private val plugin: JavaPlugin,
  private val config: Config,
  private val locales: Locales,
  private val billing: Billing,
  private val setup: AdminSetup,
  private val credentials: Credentials,
  private val zones: TimeZones,
  private val scope: CoroutineScope,
) {
  /** A line of text with buttons after it; the console reads the text and each button's [console] text, if any. */
  private class Line(val text: String, val buttons: List<BaseComponent> = emptyList(), val console: String? = null)

  /** `/allay link`: a claim code to pay with in Telegram, for a new license or the one in `license.key`. */
  fun link(sender: CommandSender) {
    val lang = locales.of(sender)
    if (credentials.byok) return lang.send(sender, "billing.link.byok")
    // The link itself stays out of the log: whoever opens it first claims the subscription.
    plugin.logger.info("${sender.name} asked for a payment link")
    val motd = plugin.server.motd
    request(sender) {
      val claim = billing.link(motd)
      if (!claim.billing) return@request listOf(Line(lang.text("billing.no_billing")))
      val url = claim.pay_url?.takeIf { it.startsWith("https://") } ?: throw ApiErrorException(ApiError(ErrorCodes.PARSE_ERROR, "no pay_url"))
      buildList {
        if (claim.license != null) {
          plugin.logger.info("A new license is saved in ${License.FILE}")
          add(Line(lang.text("billing.link.created")))
        }
        add(Line(lang.text("billing.link.ready")))
        add(Line("", listOf(open(lang, url), TextComponent(" "), copy(lang, url)), console = lang.text("billing.link.url", "url" to url)))
        add(Line(lang.text("billing.link.once")))
      }
    }
  }

  /**
   * `/allay init`: the admin panel's one-time setup link on a self-hosted backend, a recovery link for a
   * forgotten password once an account exists, or where to sign in. A link goes only to whoever asked, never
   * to the log: whoever opens it first owns the panel, or may change any account's password.
   */
  fun init(sender: CommandSender) {
    val lang = locales.of(sender)
    plugin.logger.info("${sender.name} asked for an admin panel link")
    request(sender) {
      when (val answer = setup.init()) {
        is AdminInit.Link -> listOf(
          Line(lang.text("admin.init.ready")),
          Line("", listOf(panel(lang, answer.url), TextComponent(" "), copyLink(lang, answer.url)), console = lang.text("admin.init.url", "url" to answer.url)),
          Line(lang.text("admin.init.once")),
        )
        is AdminInit.Recovery -> listOf(
          Line(lang.text("admin.init.recover")),
          Line("", listOf(panel(lang, answer.url), TextComponent(" "), copyLink(lang, answer.url)), console = lang.text("admin.init.url", "url" to answer.url)),
          Line(lang.text("admin.init.recover_once")),
        )
        is AdminInit.Exists -> listOfNotNull(
          Line(lang.text("admin.init.exists")),
          answer.url?.let { Line("", listOf(panel(lang, it)), console = lang.text("admin.init.url", "url" to it)) },
        )
        AdminInit.NotSelfHosted -> listOf(Line(lang.text("admin.init.hosted")))
        AdminInit.Unsupported -> listOf(Line(lang.text("admin.init.unsupported")))
      }
    }
  }

  /** `/allay status`: who pays for the model and, for the subscription, how it stands. */
  fun status(sender: CommandSender) {
    val lang = locales.of(sender)
    val provider = config.provider
    if (provider.byok) {
      lang.send(sender, "billing.status.header")
      lang.send(sender, "billing.status.byok")
      lang.send(sender, "billing.status.base_url", "url" to provider.baseUrl)
      lang.send(sender, "billing.status.model", "model" to provider.model.ifEmpty { lang.text("billing.status.default_model") })
      lang.send(sender, "billing.status.search_model", "model" to provider.searchModel.ifEmpty { lang.text("billing.status.no_search") })
      if (provider.apiKey.isEmpty()) lang.send(sender, "billing.status.no_key") else lang.send(sender, "billing.status.key", "key" to Secrets.mask(provider.apiKey))
      when {
        credentials.token != null -> lang.send(sender, "billing.status.token", "token" to Secrets.mask(credentials.token))
        billing.license.id != null -> lang.send(sender, "billing.status.identity", "id" to billing.license.id)
      }
      return
    }
    // A self-hosted backend knows the server by its token, and needs no license at all.
    credentials.token?.let { lang.send(sender, "billing.status.token", "token" to Secrets.mask(it)) }
    val zone = zone(sender)
    val license = billing.license
    request(sender) {
      val header = listOf(Line(lang.text("billing.status.header")), Line(lang.text("billing.status.hosted")))
      if (license.broken) throw ApiErrorException(ApiError(Billing.LICENSE_FILE_BROKEN, "license.key holds no license"))
      val status = if (license.read() == null) {
        // Without a license only a backend with billing off answers 200; anything else means there's none yet.
        runCatching { billing.status() }.getOrNull()?.takeIf { !it.billing }
      } else {
        billing.status()
      }
      header + when {
        status?.billing == false -> listOf(Line(lang.text("billing.no_billing")))
        status == null -> listOfNotNull(
          provider.legacyKey?.let { Line(lang.text("billing.status.legacy_key", "key" to Secrets.mask(it))) },
          Line(lang.text("billing.status.no_license"), listOf(TextComponent(" "), linkButton(lang))),
        )
        else -> subscription(lang, status, license, zone)
      }
    }
  }

  private fun subscription(lang: Lang, status: LicenseStatus, license: License, zone: ZoneId): List<Line> = buildList {
    add(Line(lang.text("billing.status.license", "id" to (status.license_id ?: license.id ?: "?"))))
    add(Line(lang.text(if (status.linked) "billing.status.linked" else "billing.status.not_linked")))
    add(Line(lang.text(if (status.active) "billing.status.active" else "billing.status.inactive")))
    val until = BillingDates.format(lang, status.paid_until, zone)
    if (status.plan == null || until == null) {
      add(Line(lang.text("billing.status.unpaid")))
    } else {
      val plan = "billing.plans.${status.plan}".takeIf(lang::has)?.let(lang::text) ?: status.plan
      add(Line(lang.text("billing.status.plan", "plan" to plan, "date" to until)))
      add(Line(lang.text(if (status.renews) "billing.status.renews" else "billing.status.no_renew")))
    }
    status.quota?.takeIf { status.active }?.let { quota ->
      add(Line(lang.text("billing.status.quota", "bar" to QuotaBar.render(quota.used_percent))))
      val percent = QuotaBar.percent(quota.used_percent)
      val refills = BillingDates.format(lang, quota.resets_at, zone)
      add(Line(if (refills == null) lang.text("billing.status.quota_used_no_reset", "percent" to percent) else lang.text("billing.status.quota_used", "percent" to percent, "date" to refills)))
    }
  }

  /** `/allay locale [player]`: the language a player reads and what picked it, as in Mo’Joins. */
  fun locale(sender: CommandSender, name: String?) {
    val lang = locales.of(sender)
    // A vanished player reads as not online to anyone they're hidden from.
    val target = name?.let(plugin.server::getPlayerExact)?.takeIf { Vanish.visible(sender, it) } ?: (sender as? Player).takeIf { name == null }
    if (target == null) {
      return if (name == null) lang.send(sender, "locale.usage") else lang.send(sender, "locale.not_found", "player" to name)
    }
    val choice = locales.explain(target)
    val unknown = lang.text("locale.unknown")
    lang.send(sender, "locale.info", "player" to target.name, "language" to choice.code, "reason" to lang.text("locale.reason.${choice.source}"))
    lang.send(sender, "locale.details", "country" to (choice.country ?: unknown), "client" to choice.client, "sources" to choice.sources.joinToString(" → "))
  }

  /** Under an error, for operators only: a payment link, or what to fix in `config.yml`. */
  fun hint(sender: CommandSender, error: Throwable) {
    if (!sender.hasPermission(Permissions.ADMIN)) return
    val code = (error as? ApiErrorException)?.apiError?.code ?: return
    val lang = locales.of(sender)
    val provider = config.provider
    val line = when (code) {
      in ErrorCodes.TOKEN -> Line(lang.text("billing.hint.$code"))
      // A byok server needs a license only as its identity, and Allay gets one by itself; this one couldn't.
      ErrorCodes.LICENSE_REQUIRED if provider.byok -> Line(lang.text("billing.hint.identity"))
      ErrorCodes.LICENSE_INVALID -> Line(lang.text("billing.hint.LICENSE_INVALID"))
      in ErrorCodes.SUBSCRIPTION -> Line(lang.text("billing.hint.subscription"), listOf(TextComponent(" "), linkButton(lang)), console = "/allay link")
      in ErrorCodes.PROVIDER -> Line(lang.text(
        "billing.hint.$code",
        "key" to Secrets.mask(provider.apiKey.ifEmpty { provider.legacyKey.orEmpty() }),
        "model" to provider.model.ifEmpty { lang.text("billing.status.default_model") },
        "url" to provider.baseUrl,
      ))
      else -> return
    }
    show(sender, line)
  }

  /** The reader's time zone, for dates: a player's own, the console's from `time.zone`. */
  fun zone(sender: CommandSender): ZoneId =
    (sender as? Player)?.let { TimeZones.parse(zones.of(it.uniqueId)) } ?: TimeZones.parse(config.time.zone) ?: ZoneId.of("UTC")

  /** `[Renew]` or `[/allay link]`: runs `/allay link`, which checks the permission again. */
  fun linkButton(lang: Lang, key: String = "billing.hint.link") =
    button(lang.text(key), lang.text("billing.notice.renew_hover"), ClickEvent(ClickEvent.Action.RUN_COMMAND, "/allay link"))

  private fun open(lang: Lang, url: String) = button(lang.text("billing.link.pay"), lang.text("billing.link.pay_hover"), ClickEvent(ClickEvent.Action.OPEN_URL, url))

  private fun panel(lang: Lang, url: String) = button(lang.text("admin.init.open"), lang.text("admin.init.open_hover"), ClickEvent(ClickEvent.Action.OPEN_URL, url))

  private fun copyLink(lang: Lang, url: String) = button(lang.text("admin.init.copy"), lang.text("admin.init.copy_hover"), ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, url))

  private fun copy(lang: Lang, url: String) = button(lang.text("billing.link.copy"), lang.text("billing.link.copy_hover"), ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, url))

  private fun button(text: String, hover: String, click: ClickEvent) = TextComponent(*plain(formatMessage(text))).apply {
    clickEvent = click
    hoverEvent = HoverEvent(HoverEvent.Action.SHOW_TEXT, Text(plain(formatMessage(hover))))
  }

  private fun show(sender: CommandSender, line: Line) {
    if (sender is Player) {
      val text = if (line.text.isEmpty()) emptyArray() else plain(formatMessage(line.text))
      if (text.isNotEmpty() || line.buttons.isNotEmpty()) sender.spigot().sendMessage(*text, *line.buttons.toTypedArray())
    } else {
      val text = listOfNotNull(line.text.takeIf { it.isNotEmpty() }, line.console.takeIf { sender !is Player }).joinToString(" ")
      if (text.isNotEmpty()) sender.sendMessage(formatMessage(text))
    }
  }

  /** Runs [block] off the server thread and shows its lines on it; errors become the reader's error text. */
  private fun request(sender: CommandSender, block: suspend () -> List<Line>) {
    val lang = locales.of(sender)
    scope.launch(Dispatchers.IO) {
      val lines = try {
        block()
      } catch (error: ApiErrorException) {
        listOf(Line(when {
          error.apiError.statusCode == 404 -> lang.text("billing.unsupported")
          error.apiError.code == ErrorCodes.LICENSE_INVALID -> lang.text("billing.license_invalid")
          error.apiError.code == Billing.LICENSE_FILE_BROKEN -> lang.text("billing.license_broken")
          else -> credentials.scrub(ErrorHandler.message(lang, error.apiError, zone(sender)))
        }))
      } catch (error: Exception) {
        plugin.logger.warning("License request failed: ${credentials.scrub(error.toString())}")
        listOf(Line(ErrorHandler.message(lang, error)))
      }
      if (plugin.isEnabled) plugin.server.scheduler.runTask(plugin, Runnable { lines.forEach { show(sender, it) } })
    }
  }
}

/**
 * Legacy text as components without the links the game guesses: Bukkit makes `license.key` or `provider.mode`
 * a link to http://license.key. The billing lines link only through their buttons.
 */
internal fun plain(text: String): Array<out BaseComponent> = TextComponent.fromLegacyText(text).onEach { it.clickEvent = null }
