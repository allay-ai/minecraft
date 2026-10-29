package chat.allay.core.tools

import chat.allay.core.Permissions

/** What Allay tells the model about itself: the commands and chat mode's prefixes a player may use. Texts are English, for the model. */
object Guide {
  /** Every command with the permission it needs and a line for the model. */
  private val COMMANDS = listOf(
    Triple("/ask <question>", Permissions.ASK, "a private question; only the player sees it and the answer"),
    Triple("/askall <question>", Permissions.ASKALL, "a question every player online sees, with the answer"),
    Triple("/askx <request>", Permissions.ASKX, "turns a request into commands and runs them as the player"),
    Triple("/allay", Permissions.ASK, "opens a settings window: chat mode and its tip, time zone, language, memory, starting over, full privacy, and the player's data; admins also get reload, the subscription's status, and the payment link there"),
    Triple("/allay chat [on|off]", Permissions.CHAT, "chat mode: everything the player writes in chat goes to Allay"),
    Triple("/allay hints [on|off]", Permissions.CHAT, "the tip about chat mode under answers"),
    Triple("/allay language [code|auto]", Permissions.ASK, "picks the language Allay answers in, such as en or ru; auto goes back to the one picked by the player's country and game"),
    Triple("/allay cancel", Permissions.ASK, "stops the answer being written; with no answer running, ends the way trail"),
    Triple("/allay new", Permissions.ASK, "starts the conversation over; memories stay"),
    Triple("/allay memory", Permissions.ASK, "opens a window of what Allay remembers about the player: each memory opens with the way to its place and a button to forget it; buttons there add one (with the player's place, if they like) or forget all"),
    Triple("/allay privacy", Permissions.ASK, "opens a window of what Allay keeps about the player on this server and for how long, with full privacy as a switch and buttons to download it all (a 24-hour link in chat), take the agreement back, or erase it all, each after a confirmation"),
    Triple("/allay privacy full on|off", Permissions.ASK, "full privacy: the backend's admin panel knows the player by a pseudonym instead of their name, UUID, and skin; memories and the conversation work as before. Also a switch in the /allay window"),
    Triple("/allay privacy agree|withdraw", Permissions.ASK, "agrees to Allay's data notice, which it shows before the first question, or takes the agreement back; without it Allay doesn't answer the player"),
    Triple("/allay reload", Permissions.RELOAD, "reloads Allay's settings and languages; also a button in the /allay window"),
    Triple("/allay status", Permissions.ADMIN, "who pays for the model on this server: the own key, or the subscription with its plan, paid-until date, and how much of the limit is used"),
    Triple("/allay link", Permissions.ADMIN, "a one-time link to pay for Allay's subscription in Telegram Stars (week, month, or year)"),
    Triple("/allay init", Permissions.ADMIN, "on a self-hosted backend, a one-time link that sets up the backend's web admin panel; once it has an account, a one-time link that sets a new password for a forgotten one"),
    Triple("/allay privacy export|erase <player>", Permissions.ADMIN, "for a player who asks: a 24-hour link to everything Allay keeps about them, to hand over, or erases it"),
    Triple("/allay locale [player]", Permissions.ADMIN, "which language a player reads Allay in, and why: their IP's country, their game's language, or the default"),
  )

  /** The commands a player with these permissions may use, as usage to what it does. */
  fun commands(can: (String) -> Boolean): List<Pair<String, String>> = COMMANDS.filter { can(it.second) }.map { it.first to it.third }

  /** Chat mode's prefixes this player may use; none without chat mode. */
  fun prefixes(can: (String) -> Boolean): Map<String, String> = if (!can(Permissions.CHAT)) emptyMap() else buildMap {
    put("plain text", "a private question, like /ask")
    if (can(Permissions.ASKX)) put("!", "commands, like /askx")
    if (can(Permissions.ASKALL)) put("?", "a question everyone sees, like /askall")
    put("~", "an ordinary chat message to the other players")
  }
}
