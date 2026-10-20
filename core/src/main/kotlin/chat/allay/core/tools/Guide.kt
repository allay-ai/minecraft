package chat.allay.core.tools

import chat.allay.core.Permissions

/** What Allay tells the model about itself: the commands and chat mode's prefixes a player may use. Texts are English, for the model. */
object Guide {
  /** Every command with the permission it needs and a line for the model. */
  private val COMMANDS = listOf(
    Triple("/ask <question>", Permissions.ASK, "a private question; only the player sees it and the answer"),
    Triple("/askall <question>", Permissions.ASKALL, "a question every player online sees, with the answer"),
    Triple("/askx <request>", Permissions.ASKX, "turns a request into commands and runs them as the player"),
    Triple("/allay", Permissions.ASK, "opens a settings window: chat mode and its tip, time zone, memory, starting over, and the windows below"),
    Triple("/allay chat [on|off]", Permissions.CHAT, "chat mode: everything the player writes in chat goes to Allay"),
    Triple("/allay hints [on|off]", Permissions.CHAT, "the tip about chat mode under answers"),
    Triple("/allay cancel", Permissions.ASK, "stops the answer being written; with no answer running, ends the way trail"),
    Triple("/allay new", Permissions.ASK, "starts the conversation over; memories stay"),
    Triple("/allay memory", Permissions.ASK, "lists what Allay remembers about the player"),
    Triple("/allay memory <text>", Permissions.ASK, "remembers the text without asking Allay; with coordinates in it (x y z, or x z), a note in the notebook, in the dimension the text names or where the player stands"),
    Triple("/allay forget <number|all>", Permissions.ASK, "forgets one remembered fact, or all of them"),
    Triple("/allay privacy", Permissions.ASK, "what Allay keeps about the player on this server and for how long; /allay privacy erase erases all of it, after a confirmation"),
    Triple("/allay recipe <item>", Permissions.ASK, "opens the recipe window for an item"),
    Triple("/allay enchants", Permissions.ASK, "opens the enchanting window for the item in hand"),
    Triple("/allay villagers", Permissions.ASK, "opens the window of villagers around and their trades"),
    Triple("/allay brewing <potion> [potion|splash_potion|lingering_potion]", Permissions.ASK, "opens the brewing window: every step from a water bottle to the potion"),
    Triple("/allay anvil", Permissions.ASK, "opens the anvil plan: the cheapest order to merge the enchanted books and items of its kind from the inventory onto the item in hand"),
    Triple("/allay reload", Permissions.RELOAD, "reloads Allay's settings and languages"),
    Triple("/allay status", Permissions.ADMIN, "who pays for the model on this server: the own key, or the subscription with its plan, paid-until date, and how much of the limit is used"),
    Triple("/allay link", Permissions.ADMIN, "a one-time link to pay for Allay's subscription in Telegram Stars (week, month, or year)"),
    Triple("/allay init", Permissions.ADMIN, "on a self-hosted backend, a one-time link that sets up the backend's web admin panel; once it has an account, a one-time link that sets a new password for a forgotten one"),
    Triple("/allay privacy export|erase <player>", Permissions.ADMIN, "for a player who asks: saves everything Allay keeps about them to a file for them, or erases it"),
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
