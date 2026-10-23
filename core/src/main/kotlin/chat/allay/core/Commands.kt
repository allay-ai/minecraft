package chat.allay.core

/**
 * `/ask`, `/askall`, `/askx`, and `/allay` with its subcommands and completions, the same on every platform. The
 * platform registers the four commands and hands each call here with its arguments split on spaces.
 */
class Commands(private val allay: Allay<*>) {
  /** The command's [name] without its `/`; false when it isn't one of Allay's. */
  fun run(sender: Audience, name: String, args: List<String>): Boolean {
    val text = args.joinToString(" ")
    val handler = allay.handler
    when (name.lowercase()) {
      "ask" -> handler.ask(sender, text, Run.Mode.ASK, tip = sender is PlayerAudience && text.isNotBlank() && allay.chat.counts(sender))
      "askall" -> handler.ask(sender, text, Run.Mode.ASK, public = true)
      "askx" -> handler.ask(sender, text, Run.Mode.ASKX)
      "allay" -> allay(sender, args)
      else -> return false
    }
    return true
  }

  /** `/allay`: everything around the questions. */
  private fun allay(sender: Audience, args: List<String>) {
    val lang = allay.locales.of(sender)
    val word = args.firstOrNull()?.lowercase()
    val player = sender as? PlayerAudience
    val asker = sender.can(Permissions.ASK)
    val privacy = allay.privacy
    val billing = allay.billing
    when {
      word == null && player != null -> allay.game.settings(player)
      // Buttons under answers run this; a typed id works only if it was shown to this player.
      word == "do" && args.size == 2 && player != null -> allay.clicks.press(player, args[1])
      word == "reload" && sender.can(Permissions.RELOAD) -> allay.reload(sender)
      word == "language" || word == "lang" -> when {
        player == null -> lang.send(sender, "messages.players_only")
        args.size == 1 -> allay.game.languages(player)
        args.size == 2 -> if (!allay.languages.choose(player, args[1])) lang.send(sender, "messages.language_unknown", "code" to args[1], "codes" to (allay.languages.codes() + "auto").joinToString(", "))
        else -> usage(sender, lang)
      }
      word == "chat" || word == "hints" -> when {
        player == null -> lang.send(sender, "messages.players_only")
        !sender.can(Permissions.CHAT) -> lang.send(sender, "messages.no_permission")
        else -> {
          val settings = allay.settings.of(player.uuid)
          val current = if (word == "chat") settings.chat_mode else settings.hints
          val wanted = when (args.getOrNull(1)?.lowercase()) {
            "on" -> true
            "off" -> false
            null -> !current
            else -> return usage(sender, lang)
          }
          if (word == "chat") allay.chat.set(player, wanted) else allay.chat.hints(player, wanted)
        }
      }
      word == "privacy" -> when {
        args.size == 1 -> privacy.info(sender)
        args.size == 2 && args[1].equals("agree", ignoreCase = true) -> privacy.agree(sender)
        args.size == 2 && args[1].equals("decline", ignoreCase = true) -> privacy.decline(sender)
        args.size == 2 && args[1].equals("review", ignoreCase = true) -> privacy.review(sender)
        args.size == 3 && args[1].equals("age", ignoreCase = true) -> privacy.age(sender, args[2])
        args.size == 2 && args[1].equals("withdraw", ignoreCase = true) -> privacy.confirmWithdraw(sender)
        args.size == 3 && args[1].equals("withdraw", ignoreCase = true) && args[2] == PrivacyCommands.CONFIRM -> privacy.withdraw(sender)
        // The window's switch: the window again instead of a line in chat.
        args.size == 3 && args[1].equals("full", ignoreCase = true) && args[2].equals("toggle", ignoreCase = true) && player != null -> privacy.toggleFull(player)
        args.size in 2..3 && args[1].equals("full", ignoreCase = true) -> privacy.full(sender, args.getOrNull(2))
        args.size == 2 && args[1].equals("erase", ignoreCase = true) -> privacy.confirmSelf(sender)
        args.size == 2 && args[1].equals("export", ignoreCase = true) -> privacy.exportSelf(sender)
        args.size == 3 && args[1].equals("erase", ignoreCase = true) && args[2] == PrivacyCommands.CONFIRM -> privacy.eraseSelf(sender)
        args.size == 3 && args[1].lowercase() in setOf("export", "erase") ->
          if (!sender.can(Permissions.ADMIN)) lang.send(sender, "messages.no_permission")
          else if (args[1].equals("export", ignoreCase = true)) privacy.export(sender, args[2]) else privacy.erase(sender, args[2])
        else -> lang.send(sender, "privacy.usage")
      }
      word == "link" || word == "status" || word == "locale" || word == "init" -> when {
        !sender.can(Permissions.ADMIN) -> lang.send(sender, "messages.no_permission")
        word == "link" && args.size == 1 -> billing.link(sender)
        word == "init" && args.size == 1 -> billing.init(sender)
        word == "status" && args.size == 1 -> billing.status(sender)
        word == "locale" && args.size <= 2 -> billing.locale(sender, args.getOrNull(1))
        else -> usage(sender, lang)
      }
      !asker -> lang.send(sender, "messages.no_permission")
      word == "cancel" && args.size == 1 -> allay.handler.cancel(sender)
      word == "new" && args.size == 1 -> allay.memory.newSession(sender)
      word == "memory" -> memory(sender, player, args.drop(1))
      else -> usage(sender, lang)
    }
  }

  /**
   * `/allay memory` opens the window; its buttons run the rest: `new` (the field to add one), `add` and `here` with
   * the text, `show` and `forget` with a memory's id, and `clear`, which asks once more before `clear confirm`.
   */
  private fun memory(sender: Audience, player: PlayerAudience?, args: List<String>) {
    val memory = allay.memory
    val word = args.firstOrNull()?.lowercase()
    val rest = args.drop(1).joinToString(" ")
    when {
      word == null -> memory.open(sender)
      word == "add" -> memory.remember(sender, rest)
      player == null -> allay.locales.of(sender).send(sender, "messages.players_only")
      word == "here" -> memory.remember(player, rest, here = true)
      word == "new" -> memory.create(player)
      word == "show" && args.size == 2 -> memory.show(player, args[1])
      word == "forget" && args.size == 2 -> memory.forget(player, args[1])
      word == "clear" && args.size == 1 -> memory.confirmClear(player)
      word == "clear" && args.getOrNull(1) == PrivacyCommands.CONFIRM -> memory.forget(player, null)
      else -> memory.open(sender)
    }
  }

  private fun usage(sender: Audience, lang: Lang) {
    lang.send(sender, "messages.allay_usage")
    if (sender.can(Permissions.ADMIN)) lang.send(sender, "messages.allay_admin_usage")
  }

  /** What `/allay` completes for [args], the last of them being typed; the other commands complete nothing. */
  fun complete(sender: Audience, name: String, args: List<String>): List<String> {
    if (!name.equals("allay", ignoreCase = true) || args.isEmpty()) return emptyList()
    val platform = allay.platform
    val options = when {
      args.size == 1 -> buildList {
        if (sender is PlayerAudience) add("language")
        if (sender.can(Permissions.CHAT)) addAll(listOf("chat", "hints"))
        if (sender.can(Permissions.ASK)) addAll(listOf("cancel", "new", "memory", "privacy"))
        if (sender.can(Permissions.RELOAD)) add("reload")
        if (sender.can(Permissions.ADMIN)) addAll(listOf("status", "link", "locale", "init"))
      }
      args.size == 2 && args[0].equals("locale", ignoreCase = true) && sender.can(Permissions.ADMIN) ->
        platform.players.filter { platform.visible(sender, it) }.map { it.name }
      args.size == 2 && args[0].lowercase() in setOf("chat", "hints") -> listOf("on", "off")
      args.size == 2 && args[0].lowercase() in setOf("language", "lang") -> allay.languages.codes() + "auto"
      args.size == 2 && args[0].equals("privacy", ignoreCase = true) ->
        listOf("agree", "withdraw", "full", "export", "erase")
      args.size == 3 && args[0].equals("privacy", ignoreCase = true) && args[1].equals("full", ignoreCase = true) -> listOf("on", "off")
      args.size == 3 && args[0].equals("privacy", ignoreCase = true) && sender.can(Permissions.ADMIN) ->
        platform.players.filter { platform.visible(sender, it) }.map { it.name }
      else -> return emptyList()
    }
    return options.filter { it.startsWith(args.last(), ignoreCase = true) }
  }
}
