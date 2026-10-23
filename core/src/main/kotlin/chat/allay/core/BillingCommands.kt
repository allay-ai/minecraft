package chat.allay.core

import chat.allay.core.tools.TimeZones
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * `/allay link`, `/allay status`, `/allay locale`, and `/allay init` for operators, and the hint under an error a payment or the
 * config fixes. The payment link goes only to the chat of whoever asked; the key and the license never reach the log.
 */
class BillingCommands(
  private val platform: Platform,
  private val config: Config,
  private val locales: Locales,
  private val billing: Billing,
  private val setup: AdminSetup,
  private val credentials: Credentials,
  private val zones: TimeZones,
  private val scope: CoroutineScope,
) {
  /** A line of text with buttons after it; the console reads the text and each button's [console] text, if any. */
  private class Reply(val text: String, val buttons: List<Line.Part> = emptyList(), val console: String? = null)

  /** `/allay link`: a claim code to pay with in Telegram, for a new license or the one in `license.key`. */
  fun link(sender: Audience) {
    val lang = locales.of(sender)
    if (credentials.byok) return lang.send(sender, "billing.link.byok")
    // The link itself stays out of the log: whoever opens it first claims the subscription.
    platform.log.info("${sender.name} asked for a payment link")
    val motd = platform.motd
    request(sender) {
      val claim = billing.link(motd)
      if (!claim.billing) return@request listOf(Reply(lang.text("billing.no_billing")))
      val url = claim.pay_url?.takeIf { it.startsWith("https://") } ?: throw ApiErrorException(ApiError(ErrorCodes.PARSE_ERROR, "no pay_url"))
      buildList {
        if (claim.license != null) {
          platform.log.info("A new license is saved in ${License.FILE}")
          add(Reply(lang.text("billing.link.created")))
        }
        add(Reply(lang.text("billing.link.ready")))
        add(Reply("", listOf(open(lang, url), Line.SPACE, copy(lang, url)), console = lang.text("billing.link.url", "url" to url)))
        add(Reply(lang.text("billing.link.once")))
      }
    }
  }

  /**
   * `/allay init`: the admin panel's one-time setup link on a self-hosted backend, a recovery link for a
   * forgotten password once an account exists, or where to sign in. A link goes only to whoever asked, never
   * to the log: whoever opens it first owns the panel, or may change any account's password.
   */
  fun init(sender: Audience) {
    val lang = locales.of(sender)
    platform.log.info("${sender.name} asked for an admin panel link")
    request(sender) {
      when (val answer = setup.init()) {
        is AdminInit.Link -> listOf(
          Reply(lang.text("admin.init.ready")),
          Reply("", listOf(panel(lang, answer.url), Line.SPACE, copyLink(lang, answer.url)), console = lang.text("admin.init.url", "url" to answer.url)),
          Reply(lang.text("admin.init.once")),
        )
        is AdminInit.Recovery -> listOf(
          Reply(lang.text("admin.init.recover")),
          Reply("", listOf(panel(lang, answer.url), Line.SPACE, copyLink(lang, answer.url)), console = lang.text("admin.init.url", "url" to answer.url)),
          Reply(lang.text("admin.init.recover_once")),
        )
        is AdminInit.Exists -> listOfNotNull(
          Reply(lang.text("admin.init.exists")),
          answer.url?.let { Reply("", listOf(panel(lang, it)), console = lang.text("admin.init.url", "url" to it)) },
        )
        AdminInit.NotSelfHosted -> listOf(Reply(lang.text("admin.init.hosted")))
        AdminInit.Unsupported -> listOf(Reply(lang.text("admin.init.unsupported")))
      }
    }
  }

  /**
   * `/allay status`: who pays for the model and, for the subscription, how it stands. A player reads it in a window,
   * with a button to pay when there's nothing paid; the console in lines.
   */
  fun status(sender: Audience) {
    val lang = locales.of(sender)
    val provider = config.provider
    if (provider.byok) return request(sender, window = true) {
      listOfNotNull(
        Reply(lang.text("billing.status.header")),
        Reply(lang.text("billing.status.byok")),
        Reply(lang.text("billing.status.base_url", "url" to provider.baseUrl)),
        Reply(lang.text("billing.status.model", "model" to provider.model.ifEmpty { lang.text("billing.status.default_model") })),
        Reply(lang.text("billing.status.search_model", "model" to provider.searchModel.ifEmpty { lang.text("billing.status.no_search") })),
        Reply(if (provider.apiKey.isEmpty()) lang.text("billing.status.no_key") else lang.text("billing.status.key", "key" to Secrets.mask(provider.apiKey))),
        credentials.token?.let { Reply(lang.text("billing.status.token", "token" to Secrets.mask(it))) }
          ?: billing.license.id?.let { Reply(lang.text("billing.status.identity", "id" to it)) },
      )
    }
    val zone = zone(sender)
    val license = billing.license
    request(sender, window = true) {
      // A self-hosted backend knows the server by its token, and needs no license at all.
      val header = listOfNotNull(
        Reply(lang.text("billing.status.header")),
        credentials.token?.let { Reply(lang.text("billing.status.token", "token" to Secrets.mask(it))) },
        Reply(lang.text("billing.status.hosted")),
      )
      if (license.broken) throw ApiErrorException(ApiError(Billing.LICENSE_FILE_BROKEN, "license.key holds no license"))
      val status = if (license.read() == null) {
        // Without a license only a backend with billing off answers 200; anything else means there's none yet.
        runCatching { billing.status() }.getOrNull()?.takeIf { !it.billing }
      } else {
        billing.status()
      }
      header + when {
        status?.billing == false -> listOf(Reply(lang.text("billing.no_billing")))
        status == null -> listOfNotNull(
          provider.legacyKey?.let { Reply(lang.text("billing.status.legacy_key", "key" to Secrets.mask(it))) },
          Reply(lang.text("billing.status.no_license"), listOf(Line.SPACE, linkButton(lang))),
        )
        else -> subscription(lang, status, license, zone)
      }
    }
  }

  private fun subscription(lang: Lang, status: LicenseStatus, license: License, zone: ZoneId): List<Reply> = buildList {
    add(Reply(lang.text("billing.status.license", "id" to (status.license_id ?: license.id ?: "?"))))
    add(Reply(lang.text(if (status.linked) "billing.status.linked" else "billing.status.not_linked")))
    add(Reply(lang.text(if (status.active) "billing.status.active" else "billing.status.inactive")))
    val until = BillingDates.format(lang, status.paid_until, zone)
    if (status.plan == null || until == null) {
      add(Reply(lang.text("billing.status.unpaid")))
    } else {
      val plan = "billing.plans.${status.plan}".takeIf(lang::has)?.let(lang::text) ?: status.plan
      add(Reply(lang.text("billing.status.plan", "plan" to plan, "date" to until)))
      add(Reply(lang.text(if (status.renews) "billing.status.renews" else "billing.status.no_renew")))
    }
    status.quota?.takeIf { status.active }?.let { quota ->
      add(Reply(lang.text("billing.status.quota", "bar" to QuotaBar.render(quota.used_percent))))
      val percent = QuotaBar.percent(quota.used_percent)
      val refills = BillingDates.format(lang, quota.resets_at, zone)
      add(Reply(if (refills == null) lang.text("billing.status.quota_used_no_reset", "percent" to percent) else lang.text("billing.status.quota_used", "percent" to percent, "date" to refills)))
    }
  }

  /** `/allay locale [player]`: the language a player reads and what picked it, as in Mo’Joins. */
  fun locale(sender: Audience, name: String?) {
    val lang = locales.of(sender)
    // A vanished player reads as not online to anyone they're hidden from.
    val target = name?.let(platform::player)?.takeIf { platform.visible(sender, it) } ?: (sender as? PlayerAudience).takeIf { name == null }
    if (target == null) {
      return if (name == null) lang.send(sender, "locale.usage") else lang.send(sender, "locale.not_found", "player" to name)
    }
    val choice = locales.explain(target)
    val unknown = lang.text("locale.unknown")
    lang.send(sender, "locale.info", "player" to target.name, "language" to choice.code, "reason" to lang.text("locale.reason.${choice.source}"))
    lang.send(sender, "locale.details", "country" to (choice.country ?: unknown), "client" to choice.client, "sources" to choice.sources.joinToString(" → "))
  }

  /** Under an error, for operators only: a payment link, or what to fix in `config.yml`. */
  fun hint(sender: Audience, error: Throwable) {
    if (!sender.can(Permissions.ADMIN)) return
    val code = (error as? ApiErrorException)?.apiError?.code ?: return
    val lang = locales.of(sender)
    val provider = config.provider
    val reply = when (code) {
      in ErrorCodes.TOKEN -> Reply(lang.text("billing.hint.$code"))
      // A byok server needs a license only as its identity, and Allay gets one by itself; this one couldn't.
      ErrorCodes.LICENSE_REQUIRED if provider.byok -> Reply(lang.text("billing.hint.identity"))
      ErrorCodes.LICENSE_INVALID -> Reply(lang.text("billing.hint.LICENSE_INVALID"))
      in ErrorCodes.SUBSCRIPTION -> Reply(lang.text("billing.hint.subscription"), listOf(Line.SPACE, linkButton(lang)), console = "/allay link")
      in ErrorCodes.PROVIDER -> Reply(lang.text(
        "billing.hint.$code",
        "key" to Secrets.mask(provider.apiKey.ifEmpty { provider.legacyKey.orEmpty() }),
        "model" to provider.model.ifEmpty { lang.text("billing.status.default_model") },
        "url" to provider.baseUrl,
      ))
      else -> return
    }
    show(sender, reply)
  }

  /** The reader's time zone, for dates: a player's own, the console's from `time.zone`. */
  fun zone(sender: Audience): ZoneId =
    sender.uuid?.let { TimeZones.parse(zones.of(it)) } ?: TimeZones.parse(config.time.zone) ?: ZoneId.of("UTC")

  /** `[Renew]` or `[/allay link]`: runs `/allay link`, which checks the permission again. */
  fun linkButton(lang: Lang, key: String = "billing.hint.link") =
    button(lang.text(key), lang.text("billing.notice.renew_hover"), Line.Click.Run("/allay link"))

  private fun open(lang: Lang, url: String) = button(lang.text("billing.link.pay"), lang.text("billing.link.pay_hover"), Line.Click.Open(url))

  private fun panel(lang: Lang, url: String) = button(lang.text("admin.init.open"), lang.text("admin.init.open_hover"), Line.Click.Open(url))

  private fun copyLink(lang: Lang, url: String) = button(lang.text("admin.init.copy"), lang.text("admin.init.copy_hover"), Line.Click.Copy(url))

  private fun copy(lang: Lang, url: String) = button(lang.text("billing.link.copy"), lang.text("billing.link.copy_hover"), Line.Click.Copy(url))

  private fun button(text: String, hover: String, click: Line.Click) = Line.Button(Legacy.color(text), Legacy.color(hover), click)

  private fun show(sender: Audience, reply: Reply) {
    if (sender is PlayerAudience) {
      val text = if (reply.text.isEmpty()) emptyList() else listOf(Line.Text(Legacy.color(reply.text)))
      if (text.isNotEmpty() || reply.buttons.isNotEmpty()) sender.send(Line(text + reply.buttons))
    } else {
      val text = listOfNotNull(reply.text.takeIf { it.isNotEmpty() }, reply.console).joinToString(" ")
      if (text.isNotEmpty()) sender.send(Legacy.color(text))
    }
  }

  /**
   * Lines as a window: the first is its title, and a line with a button to pay gives the window that button. The
   * payment link itself still comes to chat, where it can be copied.
   */
  private fun window(player: PlayerAudience, replies: List<Reply>) {
    val lang = locales.of(player)
    val pay = replies.any { it.buttons.isNotEmpty() }
    player.show(Window(
      replies.first().text, replies.drop(1).map { it.text }.filter { it.isNotEmpty() },
      if (pay) listOf(Window.button(lang, "billing.status.pay", "billing.notice.renew_hover", "/allay link")) else emptyList(),
      1, Window.BODY, exit = Window.button(lang, "windows.back", "windows.back_hover", "/allay"),
    ))
  }

  /** Runs [block] off the server thread and shows its lines on it; errors become the reader's error text. */
  private fun request(sender: Audience, window: Boolean = false, block: suspend () -> List<Reply>) {
    val lang = locales.of(sender)
    scope.launch(Dispatchers.IO) {
      val replies = try {
        block()
      } catch (error: ApiErrorException) {
        listOf(Reply(when {
          error.apiError.statusCode == 404 -> lang.text("billing.unsupported")
          error.apiError.code == ErrorCodes.LICENSE_INVALID -> lang.text("billing.license_invalid")
          error.apiError.code == Billing.LICENSE_FILE_BROKEN -> lang.text("billing.license_broken")
          else -> credentials.scrub(ErrorHandler.message(lang, error.apiError, zone(sender)))
        }))
      } catch (error: Exception) {
        platform.log.warning("License request failed: ${credentials.scrub(error.toString())}")
        listOf(Reply(ErrorHandler.message(lang, error)))
      }
      platform.scheduler.main {
        if (window && sender is PlayerAudience && replies.size > 1) window(sender, replies) else replies.forEach { show(sender, it) }
      }
    }
  }
}
