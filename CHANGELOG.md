# Changelog

The “Release” workflow takes the notes for a version from its section here, unless you type them into its `changelog` field.

## 6.12.0

Allay answers from what the server really has, finds the anvil's item wherever it lies, and other plugins and mods can extend it. Search and expansions need Allay Server 6.14 (allay.chat runs it); an older backend answers as before.

- 🔎 **This server's things, by any name.** The new `search_items` finds items and potions by their name in any language the game has, or by id: the game's, mods', data packs' items built on the game's own (by their item model), plugins' (Red Bull's cans), and expansions'. `get_recipes` and `get_brewing_recipe` take the same names, so “Potion of Infestation”, `infestation`, and «зелье заражения» all brew `infested`; before, the model asked for `infestation`, found no such id, and said the potion didn't exist. A miss names what this server has under close names. Allay downloads the game's names in Russian, German, Spanish, and Portuguese once per game version from Mojang, as the launcher does (`catalog.languages`, `catalog.download`).
- 🧪 **The server's brews and recipes.** Brewing comes from the server's recipes: mods' and data packs' brews count on Fabric and NeoForge, and on Spigot and Paper the world's data packs' brewing files and Paper plugins' potion mixes made of items. Recipes for a plugin's item show its own id (`redbull:original`), and a water bottle in a recipe reads as `potion water`, not `potion`.
- ⚒️ **The anvil finds the item.** `get_anvil_plan` works on any item the player has, in the inventory, worn, or in the offhand: by `slot`, by `item` (an id, a part like `sword`, or a name like «меч»), or, with neither, the one in hand if the books improve it, else the only item they improve; several, and the model asks which with buttons. The anvil button takes `slot`, and the window says when a piece is worn or in the offhand. Data packs' enchantments count (`mypack:frost`).
- 📦 **Data packs and mods.** `get_server` lists the data packs the world runs and the game's experiments turned on; the mods list mods as mods.
- 🧩 **Expansions.** Other plugins and mods extend Allay through `chat.allay:allay-expansion` 1.0.0 on Maven Central: tools the model calls as `x_<id>_<tool>`, items and recipes, and up to 2000 characters for the model's prompt. One class runs on Spigot, Paper, Fabric, and NeoForge; Allay finds it through `META-INF/services` or `AllayExpansions.register`. Their tools follow Allay's rules: only after a player agreed to the notice, operator ones for operators, reading ones only in `/askx`, none for automation agents or the console, each behind its own permission. A tool line names whose it is: “Asking Mana”. `expansions.enabled` and `expansions.disabled` switch them off. The wiki's “Expansions” shows how to write one.

## 6.11.0

Allay comes to Fabric and NeoForge. The plugin for Spigot and Paper works as before.

- 🧱 **Two mods.** `allay-fabric.jar` for Fabric 26.3 with Fabric API, and `allay-neoforge.jar` for NeoForge 26.3 with nothing else; Kotlin is inside both. They run on a dedicated server and in a singleplayer world, opened to LAN or not, with every tool, window, button, and automation agent the plugin has, and the same names, arguments, and answers, so Allay answers the same everywhere. The backend hears `fabric` or `neoforge`, and `server` or `integrated`.
- 🙅 **Only where Allay runs.** With a mod in their game, a player on a server without Allay gets one line in their language for `/ask`, `/askall`, `/askx`, and `/allay`: Allay can't answer there, and where it can. On a server with Allay, the plugin or a mod, those commands go to the server as before, and nothing clashes: the mods add no blocks or items, so a game with one joins any server.
- 📁 **Where the mods keep their files.** `config/allay/`: `config.yml`, the texts, `license.key`, and the country database, shared by every singleplayer world, so one license and one setup serve them all. A singleplayer world keeps only its own server id in its save.
- 🔑 **The owner of a singleplayer world runs Allay there**, cheats or not: they link a license and reload, but get no operator tools without cheats.
- 🧩 **Integrations in the mods**, each optional: claims from Flan and Open Parties and Claims on both loaders, and from the mods behind Common Protection API on Fabric, in place of GriefPrevention; Text Placeholder API in place of PlaceholderAPI (`{papi:…}` takes its ids, `%allay:chat_mode%` and the rest), on NeoForge through its NeoForge port; LuckPerms for permissions, on Fabric through fabric-permissions-api and on NeoForge through its PermissionAPI (operators get everything without one); the Vanish mod on Fabric and Vanishmod on NeoForge; Plan's Fabric build on Fabric only, since Plan has no NeoForge build.
- 🧪 **The mods' differences, where the game leaves no choice.** Worlds are named by dimension (`overworld`), not by folder. `get_server` lists mods, and `tools.server.hidden_plugins` hides a mod by its id or name. Chat mode turns `~text` into a plain line sent as the player, since the game signs chat. On NeoForge a quota or model tier added to `config.yml` reaches LuckPerms after the server restarts.
- 🔊 **Sounds by the game's id.** `ui.sounds.*_sound` takes `entity.allay.item_given` as well as Bukkit's `ENTITY_ALLAY_ITEM_GIVEN`, on every platform.
- 🔁 **Nothing doubles after a death.** Furnace, brewing, and other automation jobs take ingredients from the player's inventory as it is after a respawn; with `keepInventory`, a refill after a death could copy items.
- 📕 **`give_item` makes a working enchanted book**: the enchantments are stored in it, so it works on an anvil, instead of sitting on the book.
- 🏛️ **Spawn protection counts** for `inspect_redstone` and drafts: a non-operator can't read or check builds inside it, as they can't build there.
- 🔒 **Brewing stands follow claims** on every platform: `get_brewing_items` lists only stands the player could open themself.
- 📦 **Releases carry all three jars**, each also without its version: `releases/latest/download/allay-spigot.jar`, `allay-fabric.jar`, and `allay-neoforge.jar`. On Modrinth the plugin's versions read `X.Y.Z+spigot` and the mods' `X.Y.Z+fabric` and `X.Y.Z+neoforge`.

## 6.10.0

Ready for the Fabric and NeoForge mods to come. Nothing changes in the game.

- 🧱 **More of Allay in its core.** The flow of a question, the commands, chat mode, billing, memory, privacy, the agents' wakes, and the tools that need no world now live in the core, apart from Bukkit, behind one seam a mod implements. The plugin is a thin adapter around it.
- 🧩 **Where Allay runs.** The plugin tells the backend it's the `spigot` build on a `server` (Allay Server 6.12 shows it in the admin panel and tells the model). A player who meets a Solo license that belongs to someone else reads why.

## 6.9.0

Needs Allay Server 6.11, which writes buttons into the answer; with an older backend answers come without buttons.

- 🔘 **Buttons in the answer.** Allay writes its buttons where they belong in the text instead of a row under it: a follow-up question, the way, coordinates to copy, a note, a window. A button Allay can't use here shows as plain text. The `add_buttons` tool is gone.
- ✉️ **Messages to send.** Ask Allay to write to the others for you (“ask politely in chat for 12 diamonds”) and it shows the message with a **Send** button; nothing goes out until you press it. In your own answers Allay can also offer a command you could type yourself; `blocked_commands` still apply. Neither kind ever comes in an answer everyone reads.
- 🧠 **Memory in a window.** `/allay memory` opens what Allay remembers: open one to see its place, get the way there, or forget it; add one by hand, with your place if you like; forget everything after a confirmation. `/allay forget` is gone.
- 🛡️ **Your data in a window.** `/allay privacy` opens what's kept, with full privacy as a switch and buttons to download it (the link still comes to chat), take your agreement back, or erase it all, each after a confirmation.
- 🛠️ **Admins in `/allay`.** Reload, the subscription's status (in a window), and the payment link while nothing is paid, as buttons.
- ✂️ **Fewer commands.** `/allay recipe`, `enchants`, `villagers`, `brewing`, and `anvil` are gone: Allay offers those windows as buttons when they help.

## 6.8.0

- 🪟 **The notice and the age question in a window.** Allay asks for agreement in the game's own window instead of thirty-odd lines of chat: the age bands as buttons, then what Allay sends and keeps, with “I agree”, “No, thanks”, and the privacy policy at the bottom. Escape closes it; one line in chat opens it again (`/allay privacy review`). Addresses like allay.chat in it are links. Paper draws it with its own dialog API, Spigot with BungeeCord's.
- 🌍 **The age question only where the law asks it.** Players from the EU, the EEA, and the US, or from an unknown country, say their age first; everyone else agrees without it, and an agreement without an age no longer asks them again.

## 6.7.1

- 🏷️ **Your server's name, not its MOTD.** `/allay link` sends `server.name` to the bot when it's set, and the MOTD only otherwise, so @AllayChatBot and the admin panel call your server what you named it.
- 📝 **The MOTD keeps its lines.** The plugin sends the backend's admin panel the MOTD line by line, up to 128 characters, instead of one line cut at 64.

## 6.7.0

Needs Allay Server 6.9, which keeps players' data. Against an older backend Allay keeps working, but settings, goals, and time zones last only until the server stops, deaths aren't kept, exports have no link, and the log says so once.

- 🔐 **Nothing personal on your server.** The plugin no longer writes `notes/`, `goals/`, `deaths/`, `players.json`, `timezones.json`, or `exports/`. Each player's settings, time zone, and goals live on Allay's backend, encrypted: they load when a player who agreed joins, stay in memory while they play, and go back a few seconds after a change, when they leave, and at shutdown, never on the server thread. A file API that serves the plugin folder, WorldAPI's included, finds nothing about players there.
- 📍 **Notes are memories with a place.** “Remember there's a fletcher here” keeps the spot with the memory, `/allay memory` lists places with their coordinates, and coordinates in `/allay memory <text>` give it one. The note button under an answer does the same. The notebook tools are gone from the plugin: Allay Server 6.9 finds memories nearest first itself, so `tools.enabled.add_note`, `find_notes`, and `delete_note` and `tools.notes.max_per_player` do nothing anymore.
- 💀 **Deaths go to the backend.** Each death goes out as it happens, and the backend keeps the history Allay pages through. `get_death_info` reports the last death as it lies now: what dropped, whether the area is loaded, and when the items despawn. `tools.deaths.max_per_player` is gone; the backend keeps the newest 100.
- 📦 **Export by link.** `/allay privacy export` gives a player a link to everything Allay keeps about them, good for 24 hours; an admin gets the same link for a player with `/allay privacy export <player>`. The link shows only to whoever asked.
- ✋ **Asked again when the notice changes.** The agreement carries the hash of the notice and of the backend's privacy policy revision. When either changes, a player who agreed before reads that it changed, since when, and what's new, and agrees again. The notice now names who answers for Allay on the server, says every question goes through OpenRouter in the USA, and lists what's kept, encrypted or not, and for how long.
- 🧒 **Every player says their age first.** “How old are you?” with five neutral bands, under a line on why the law asks: a player must reach their country's age of consent (13 to 16 in the EU, 14 in Spain, 13 in the US, 16 when the country is unknown). Too young, Allay answers nothing, sends nothing, and keeps only a sealed note in `plugins/Allay/ages.bin`, so it asks again a year later.
- 👥 **Other players stay unnamed.** Tools that name other players (`get_nearby_players`, `point_to` with a player, way buttons, operator teleports) show one who didn't agree on this server as “Player 1”, “Player 2”…, and the label still works in the next tool call. Their names never reach the backend or the AI provider.
- 🔒 **allay.chat always asks.** With the hosted backend, `privacy.consent: false` and `geoip.enabled: false` no longer skip the notice or the age question, and the log says so once; with `server.token` they work as before.
- ↩️ **Withdrawing erases.** `/allay privacy withdraw` now erases what the agreement covered: memories, places, deaths, settings, and the conversation (with Allay Server 6.9).
- 🤐 **Nobody else is looked up.** `plugins/Allay/consented.bin`, sealed like `pending.bin`, lists the UUIDs of players who agreed on this server. Only they are looked up when they join, and only their deaths go out; anyone else reaches the backend only when they use Allay themselves. Withdrawing, declining, erasure, and an agreement the backend no longer has take a player off the list.
- 📨 **Nothing is lost when the backend is away.** What can't be sent waits, trying again after growing pauses; at shutdown it goes into `plugins/Allay/pending.bin`, sealed with AES-256-GCM under a key derived from the license or `server.token`, and out on the next start.
- 📐 **The player's position goes with each question**, so Allay measures distances to memories and deaths from where the player stands.
- 🧹 **The move from MCBuddy is gone.** A server coming straight from MCBuddy runs Allay 6.6 once first.

## 6.6.0

For Allay Server 6.8, which pages the history; an older backend sees the last death only.

- 💀 **Every death, remembered.** Allay keeps each player's last 100 deaths in `plugins/Allay/deaths/`: when, where, the cause and the killer, the game's death message, the level, and what dropped. Ask “when did I die?” or “what killed me last time?”, and Allay reads the history page by page. Drops of the last death now survive a restart too. `tools.deaths.max_per_player` in `config.yml` sets how many stay.
- 🧭 **The right world spawn.** `get_locations` reads the spawn from the server's respawn world, which can be any dimension since 1.21.9, instead of the first world, and adds the `respawn_radius` game rule, so Allay knows where new players land.
- 🔒 **Your data, all of it.** `/allay privacy export` adds what the plugin keeps on the server under `plugin`: the player's settings, time zone, notes, goals, and deaths. Erasure deletes all of it, goals' boss bars included, even when the backend can't be reached; before, it all stayed on the server. `/allay privacy` lists them.

## 6.5.0

For Allay Server 6.7; an older backend ignores it.

- 🏷️ **Name your server.** `server.name` in `config.yml` (like `Moonlands`) is how the backend's admin panel heads your server, with the address and the MOTD under it. Allay registers it at start and on `/allay reload`. No two servers on a backend share a name: a taken one shows as an error in the log, and the panel shows the server's random id until you pick another.

## 6.4.0

For Allay Server 6.6, which shows it; older backends ignore it.

- 🌐 **Your server's address in the admin panel.** `server.address` in `config.yml` (like `mc.example.com`) goes to the backend with the game port, so its admin panel shows where players join. Empty: the game's `server-ip`, if set.

## 6.3.0

For Allay Server 6.5, which honors full privacy; an older backend keeps showing names in its admin panel.

- 🕶️ **Full privacy.** A player turns it on in the `/allay` window or with `/allay privacy full on`, and the backend's admin panel knows them by a pseudonym instead of their name, UUID, and skin. Allay still remembers what they told it, and the conversation works as before. The switch goes with every question and reaches the backend at once.
- 🪟 **A tidier `/allay` window.** It holds settings only: chat mode and its tip, time zone, language, memory, starting over, full privacy, and “My data”. The recipe, enchanting, and villager windows, which needed an item in hand, open from buttons under Allay's answers and from `/allay recipe|enchants|villagers`.
- 🛡️ `/allay privacy` says whether full privacy is on, and that the admin panel sees your name unless it is.

## 6.2.0

- 🚫 **A blocked player hears why.** Allay Server 6.2 lets its operators block a player on one server; that player now reads “Allay is switched off for you on this server” in their language instead of a general error.

## 6.1.2

- 🌐 **The backend hears the language Allay speaks to you in.** Allay sent your game's language unless you picked one with `/allay language`, so a player whose country picked their language could get buttons in another one. Now the backend gets the language of Allay's own messages for you; the language you write a question in still wins.

## 6.1.1

- 🔁 **An updated backend starts asking within the hour.** A question to a backend before 6.1 made Allay ask nobody until `/allay reload` or a restart, even after the backend moved to 6.1. Now it checks the backend again after an hour.

## 6.1.0

Pairs with Allay Server 6.1, which keeps each player's consent; with an older backend Allay asks nobody, as before.

- ✅ **Players agree before Allay answers.** Before a player's first question, Allay says what it sends and keeps, where (allay.chat: the EU, under the GDPR and Spain's LOPDGDD; or the server's own backend), that AI providers write the answers, and that answers can be wrong, with **[I agree]**, **[No, thanks]**, and a link to the privacy policy. Nothing about the player reaches the backend before they agree; their question goes on once they do. The backend keeps the agreement, so a player is asked once per server. `privacy.consent` turns it off, `privacy.policy_url` links your own policy.
- ↩️ **`/allay privacy withdraw`** takes the agreement back: Allay stops answering and the player's agents stop. `/allay privacy agree` gives it without a question waiting. Erasing a player's data erases their agreement too, so they're asked again.
- 🇷🇺 **Russian speaks to every player as «ты»**, the vanished-player line and `/allay privacy` included.
- 🔑 **A `license.key` Allay couldn't read counts as soon as it can.** A failed read was kept until the file changed, so a license put in place by another user stayed unread after `chown`. The log now says when the file can't be read.

## 6.0.0

Pairs with Allay Server 6.0, which wants every request signed in. **Breaking:** update the plugin before the backend. A self-hosted backend needs its `PLUGIN_TOKEN` in the new `server.token`; with `allay.chat` nothing changes for a subscription.

- 🔐 **The plugin signs in to the backend.** Every request carries `Authorization`: `server.token` for a self-hosted backend, else the license. Allay 5.x gets `401` from Server 6.0.
- 🔑 **Your own key needs a license as identity on `allay.chat`.** Allay gets one by itself on the first question, free, and keeps it in `license.key`; it pays for nothing. `/allay status` shows it.
- 🛡️ **`/allay privacy`.** A player reads what Allay keeps about them on this server and for how long, and erases all of it after a confirmation. Admins export a player's data to an owner-only file in `exports/` (`/allay privacy export <player>`) or erase it (`/allay privacy erase <player>`) when the player asks them.
- 🧭 **Clear messages on a refused sign-in.** Players read that Allay can't sign in; admins read what to set: `server.token`, the backend's `PLUGIN_TOKEN`, or why the identity license is missing.
- 🙈 **Nothing secret travels or shows.** The key and the token go only to the backend's own host, on every redirect too; the token is masked in the log, and `http.logging` leaves players' ids out of URLs.

## 5.14.3

Pairs with Allay Server 5.12.3; older servers work too.

- 🧪 **Stands kept ready stay ready.** Stands of water bottles brew only as many potions as asked: “brew 3 fire resistance” with four stands of water bottles uses one, where it brewed all twelve and asked for four nether warts. Stands of water suit any potion of a `potions` start, and a stand with one or two bottles of a step is filled up to three from the player's bottles of that kind before another stand takes a reagent.
- 🛑 **Taking bottles out mid-brew stops the agent.** The game stops a stand when its bottles go and leaves the reagent in; the agent took that for brewing and waited forever. Now a job's stand that stands still for two seconds stops the agent with the reason.
- 🔍 **Scan and start say why a stand doesn't fit.** A stand brewing says how long it has left, a reagent left in its top slot or mixed bottles says what to take out, and a stand of finished potions says to collect them. When no stand fits, start lists each one and why instead of one line for all; when some are skipped, the result names them.
- 🔥 **Blaze powder in the fuel slot counts.** A stand with fuel 0 and powder waiting in its slot no longer asks for more powder. The missing blaze powder adds up the reagents and the fuel instead of one replacing the other, and counts the blaze rods at hand.
- 💧 **Water bottles fill only when the brew can start.** Glass bottles turn into water bottles once nothing else is missing, so a start short of nether wart leaves them as they were and says how many it will fill. Flowing water no longer counts as water near, as in the game; kelp, seagrass, and bubble columns do. Filled bottles go back where their glass bottles were, glass bottles another agent holds stay put, and when the inventory is full and filled bottles fall at the player's feet, start says so instead of “not enough glass bottles”. A start without water near says how many glass bottles the player has.
- 📋 **status** names the potion's form and how many bottles each stand brews.

## 5.14.2

Pairs with Allay Server 5.12.2; older servers work too.

- 📏 **Agents and the chat reach 32 blocks.** `automation.radius` defaults to 32 (at most 52), find_item and scan_nearby_containers look 32 blocks around by default, up to 52, and crafting tables and anvils count up to 24 blocks away. `automation.pause_distance` defaults to 64, so a player at the edge doesn't pause their agent. A server that set `radius: 16` keeps it.
- 🏷️ **Item names forgive.** Crafting, smelting, find_item, get_recipes, goals, buttons, and chest selectors take plurals (“torches”, “potatoes”, “shulker boxes”), spaces, `minecraft:` and `#`, and common words (“iron” for iron_ingot, “seeds”, “bottle”). A name that fits nothing comes back with the ids that come close, like oak_planks and birch_planks for “planks”, instead of “none here”.
- 🔥 **Smelt what you want out.** `smelt` with iron_ingot (or “iron”), glass, or cooked_beef smelts everything that cooks into it the player has: raw iron first, then the ores, sharing the count. Before, it said the player had no iron_ingot to smelt. Fuel names forgive plurals too: “coal blocks” is coal_block.
- 🧪 **Potions by any name.** “speed”, “jump boost”, “instant health”, “healing II”, and “extended night vision” find swiftness, leaping, healing, strong_healing, and long_night_vision; “splash healing” or “Splash Potion of Healing” brews the splash form, and `form` takes `splash`, `lingering`, and `drinkable`.
- ✨ **Enchantments with their level.** The anvil agent and get_anvil_plan take “Sharpness V”, `unbreaking_3`, and “Silk Touch”; before, a name with a space or a level failed.

## 5.14.1

Pairs with Allay Server 5.12.1; older servers work too.

- 🔁 **A backend restart no longer fails questions.** When the backend restarts or is busy, a question goes again up to 3 times, 15 seconds apart, and the asker sees “Allay is restarting or busy; trying again…”. Automation agents wait the same 15 seconds between tries.
- 🪵 **Chest selectors know “log” and “planks”.** `log` and `logs` take every kind of log, `planks` every plank, `ingot` every ingot. Before, “take 2 logs” found nothing in a chest of 439 oak logs.
- 📭 **An empty plan doesn't pass for done.** `chests` apply refuses a plan that moves nothing, so an agent no longer tells the player it took what wasn't there.
- 🔎 **find_item and scan_nearby_containers look 16 blocks around** by default, up to 24, as far as automation agents reach.

## 5.14.0

Pairs with Allay Server 5.12.0, which teaches the agents to wait for their sub-agents and steer them; older servers keep working.

- 🔗 **Sub-agents report what they really did.** When a helper agent ends, the agent that started it wakes with the summary and the plugin's own record of what the helper's tools did: “crafted 3 × glass_bottle into the player's inventory”, “smelting done: 12 × glass … collected to the inventory”. A brewing agent short of bottles now brews on with facts instead of guessing.
- ⏳ **No leaving a helper behind.** An agent can't finish while a sub-agent it started still runs: it waits for the report, unless the helper does the rest by itself (`finish` with `handoff`). At the task limit, `start_task` tells it to wait for its own sub-agent instead of “the player has too many automations”.
- 🗣️ **An agent steers its own helpers.** `message_task` and `cancel_task` work from an agent for its own sub-agents: they wake with `[parent «name»] …`, and a stopped one doesn't wake the parent with news it already has.
- ❓ **Questions don't get lost.** An agent's question stays open until you answer, so a finished furnace or a timer in between no longer hides it from the chat. A line like “smelting takes three minutes” no longer counts as a question.
- 🛑 **Cancel means stop.** `/allay cancel`, interference, and the limits now cancel the agent's request that is out, and its tools refuse to act from then on: before, a cancelled crafting agent could still craft once its answer came in.
- 🔁 **A backend restart doesn't kill an agent.** When the backend is busy, rate-limited, or unreachable before a wake starts, the agent tries again up to three times, 30 seconds apart (or as long as the backend asks).
- 🐛 Wake messages over 2000 characters are no longer cut: what doesn't fit waits for the next wake. A task ending at the moment a question went out no longer gets the request refused. Tool errors over 2000 characters are cut instead of refused, so the model hears them at once. No two players' running agents share an id, so one's reservations never cover the other's.

## 5.13.1

Pairs with Allay Server 5.11.2; older servers work too.

- 🧪 **“My infestation potions” finds the splash ones.** In chest selectors, `potion:infestation` matches drinkable, splash, and lingering potions alike; `splash_potion:infestation` still means only splash. Before, a chests agent asked for `potion:infestation` found nothing among splash potions and gave up. Pins in the hotbar keep telling the forms apart.

## 5.13.0

Pairs with Allay Server 5.11.0, which gives each agent only its own tools; older servers keep working.

- ✅ **A click answers back.** When an agent asks “go ahead?” and you press a button, the chat shows at once “Got it: “Yes”. Passing it on.”, so you know it went through while the agent thinks.
- 👀 **Glass doesn't hide a furnace.** An agent counts a furnace, stand, or crafting table as in sight unless a wall of opaque blocks stands in between: glass, leaves, slabs, fences, and the thin brewing stand itself no longer make it “unsuitable”. The way button of `find_item` follows the same rule.
- 🏗️ **Drafts are experimental and off.** `show_draft` costs many tokens and often draws a build wrong, so `tools.enabled.show_draft` is `false` by default, and a config without the key keeps it off. Turn it on to try it.
- 💬 **Plainer tool lines.** “Running the numbers” instead of “Calculating exactly”, “Checking your health and hunger”, “Looking for players nearby”, and more, in every language.

## 5.12.1

Pairs with Allay Server 5.10.1, which teaches the agents what the plugin now tells them; older servers ignore it.

- 🔗 **An agent hears when its sub-agent ends.** A brewing agent that handed the glass to a furnaces agent used to wait forever and tell you the helper still worked. Now the sub-agent's finish or stop wakes it with `[sub-agent] …` and the summary, so it goes on with the next step or tells you what failed. Each wake also lists the agent's running sub-agents.
- 🔥 **Out of reach isn't busy.** When `smelt` or `brew` finds nothing within `automation.radius` of you, the scan names the nearest furnace or stand beyond it, and the agent asks you to come closer instead of saying none are free.

## 5.12.0

Pairs with Allay Server 5.10.0, which hands out recovery links; older servers keep answering as before.

- 🔑 **A forgotten panel password.** Once the admin panel has an account, `/allay init` gives a link that sets a new password for it, good for 30 minutes, instead of only where to sign in. Like the setup link, it goes only to whoever ran the command, never to the log.

## 5.11.1

Pairs with Allay Server 5.9.1, which tells the chests agent about `amounts`.

- 🔢 **A number for each item.** “Get me 6 lapis and 8 coal” is one plan now: `take` and `move` accept `amounts`, each item with its own `count` (or `up_to` for take: “top my torches and steaks up to a stack”). Before, `count` capped everything together, so 14 could all be lapis. The plan says what each item got and what fell short.

## 5.11.0

Pairs with Allay Server 5.9.0, which tells the agents about the new modes and options; older servers keep working without them.

- 📥 **Take things out of chests.** “Get me 64 iron” or “top my torches up to a stack” is the chests mode `take`: it searches the chests you name, or every chest around, and puts what it finds into your main inventory first, then the hotbar. `count` caps it, `up_to` counts what you already carry.
- 🗃️ **Put your loot away.** “Put my loot away” is the chests mode `stash`: each item goes into the chests around that already hold it, then into chests a sign or an item frame names for it, merging into stacks first. The hotbar and the offhand stay unless you ask, and what no chest is for stays with you and comes back in `no_chest_for`.
- 🎯 **Say what to move the way you'd say it.** `move` and `take` understand the same words as a layout: “all the iron” is raw iron, ingots, nuggets, and blocks; “tools”, “enchanted”, a category, or an id. `count` moves at most that many, and `keep` leaves things alone (“except the enchanted”). Moving from your inventory never takes the item in your hand or your offhand.
- ↩️ **Undo goes five steps back, and covers the inventory.** **[Put back]** undoes the last five chest changes, one by one, each while nothing it touched has changed. Sorting the inventory gets the same button under the answer, and “put it back” asks `sort_inventory` with `undo`.
- 🧰 **Copper chests and shulker boxes count.** Copper chests at every stage, and placed shulker boxes, are chests for every mode, for “All nearby”, and for ingredients; a shulker box never gets a shulker box put inside.
- ⬇️ **A sorter's input chest takes things.** A chest a hopper feeds or empties was left out of everything. Now `move`, `stash`, and outputs may put things into it, and a click may pick it, while sorting it or taking from it is still refused, so the machine never races the agent.
- 🔥 **Smelting delivers by itself, with the experience.** With `to` on start, a full furnace empties itself into the output and everything goes there at the end, so a big load never stalls at 64 waiting for the agent. Collecting pays the experience the furnace kept, as taking the output by hand does (on Paper; on Spigot it stays in the furnace for whoever takes from it next). Empty buckets lava leaves behind come back too.
- ➕ **Add to a running smelt.** “Add 20 more ore” is `smelt` action `add`: more of what the job cooks, spread over its furnaces with the fuel that takes, and a furnace that already finished starts again.
- 🪵 **More fuels.** Without coal the agent burns your planks, then sticks or bamboo; logs and a bucket of lava when you ask (one bucket a furnace, 100 items). Crimson and warped wood never counted as fuel in the game, and now it doesn't here either. A furnace burning another fuel is left out and listed instead of failing the whole start, and a furnace with output waiting takes a load that makes the same.
- 🧪 **Several potions at once, in rounds.** `brew` start takes `potions`, each with its form and count, in stands of its own. More than the stands hold, up to 64, brews in rounds on the same stands, each round's potions going to the output before the next goes in.
- 💧 **Water bottles from your glass bottles.** When water bottles run short, the agent fills your glass bottles at water or a cauldron near, a cauldron losing a level a bottle.
- 📍 **It remembers where things go.** `to` takes `auto`: the chests that already hold the same, the rest to your inventory. Say “always into my inventory” once, and the agent keeps that choice per kind of work in `players.json`; `scan` shows it as `player_output`.

## 5.10.2

Pairs with Allay Server 5.8.2, which tells the chests agent about `from`; older servers keep working.

- 📦 **Move from your inventory and from every chest around.** “Put all my wood into this chest” now works: a chests move takes `from: inventory` out of your main inventory, hotbar included, and `from: nearby` out of every chest around you may open, with no clicking each one. Before, it moved only between chests you picked, so the agent gave up and told you to carry it yourself. Undo puts your inventory back too.

## 5.10.1

Pairs with Allay Server 5.8.1, which tells the brewing agent what `bottles` means now; older servers keep working.

- 🧪 **Your own potions get upgraded.** “Make my night vision potions longer” puts the night vision potions you carry, or keep in chests near, into the stands and adds redstone. Before, it brewed new ones from water bottles. It works for any step of the way, like turning potions into splash potions.
- 🍶 **Three potions take one stand.** `bottles` now counts potions in all, three to a stand in as few stands as it takes. Three night vision potions took three bottles in every stand in sight before, twelve potions and four sets of ingredients.

## 5.10.0

The fuel action, the anvil, and the new crafting stations need Allay Server 5.8.

- 🪨 **A stonecutter when there's one.** With a stonecutter in sight, `craft` cuts what it can there: stone brick stairs one per block instead of four from six. 2×2 recipes still need nothing, 3×3 ones a crafting table.
- 🛡️ **Netherite gear on a smithing table.** “Make my diamond chestplate netherite” works with a smithing table in sight: the richest diamond piece, worn ones too, becomes netherite in place and keeps its enchantments, name, and wear. The template and the ingot come from what the player has, and a missing ingot is crafted from scrap and gold.
- ⚒️ **Anvil work.** The crafting agent's new `anvil` tool puts enchanted books and items of the same kind onto an item in the cheapest order, finds the books in the inventory, bundles, the ender chest, and chests near, and renames the item. It shows the result and the levels first and works only after the player's yes. The player pays the levels, nothing costs 40 or more in survival, and the anvil wears down like the game's.

- 🌍 **Pick your language.** `/allay language <code>` or the new Language button in the `/allay` window sets the language Allay speaks to you, and `auto` hands the choice back: IP country, then game language. The choice lives in `players.json`, beats every other rule, and goes to the assistant too. `/allay locale` shows it as chosen.
- 🪨 **Fuel alone, the way you say it.** `smelt` action `fuel` puts fuel in with nothing to cook: 18 coal spread evenly, 8 in each furnace, every slot filled up to a level, or just what each furnace needs to finish what it's cooking. It takes the furnaces named or picked, or every usable one around, of the kinds asked (only blast furnaces, say), and the fuel asked, else what they already burn, else the cheapest at hand. Coal blocks split into coal when coal runs short, and furnaces burning another fuel are skipped with the reason. Before, the agent could only load fuel together with something to smelt, so “put the coal in the furnaces” failed.
- 👀 **A furnace you can click counts as in sight.** The line-of-sight check looked along one ray to the block's center, which grazed the glass or the furnace beside it, so a blast furnace in a wall next to glass read as hidden and got skipped. Now any part of a face you could click is enough: a furnace behind a slab shows its upper half, and water never blocks the view. The same goes for brewing stands and crafting tables.
- ⏱️ **Marks glow 30 seconds by default**, not 120: `tools.show_marks.default_seconds`. The assistant can still ask for longer, up to `max_seconds`.

## 5.9.1

- 🪵 **Crafting uses the wood you carry.** A recipe that takes any planks, like a chest, now makes them from your jungle, birch, or any other logs, several kinds together when one isn't enough. Before, the agent always asked for the first wood on the list: “8 chests” with 37 jungle logs said 15 dark oak logs were missing.
- 🧰 **2×2 recipes need no crafting table**, as in the game: planks, sticks, and the crafting table itself. Before, `craft` wanted a table for everything, so a player without one couldn't get one from Allay.
- 📖 **`get_recipes` names whole groups**: a chest takes `#planks (any of 12)`, not three planks that read as the only ones. Long lists of alternatives say how many more there are.

## 5.9.0

`/allay init` needs Allay Server 5.7; older backends say they have no panel.

- 🖥️ **`/allay init`** sets up the web admin panel of a self-hosted backend. It shows a **[Open the panel]** button with a link that works once and for 30 minutes, where you create the panel's first account and become its owner. The link goes only to whoever ran the command, never to the log; the console gets it as text. Once the panel has an admin, the command shows where to sign in. A backend with billing on sets up its panel from its own log instead, and the command says so. Needs `allay.admin`.

## 5.8.0

Layouts and main inventory pins need Allay Server 5.6.

- 🗂️ **Layouts, the same for the inventory and chests.** Say how things should lie and `sort_inventory` or the chests agent's `sort` and `group` follow it: an order (“iron first, then gold”, every stack of each), zones (“food in the last row”, “bundles in the right column”, “slots 1–5”, “at the start”, “at the end”), the rest by name, category, or the biggest pile, each category on its own row while there's room, items that stay where they are (“don't touch the books”), or only merging stacks. Items are named by id, group, material (`iron` is raw iron, ingots, nuggets, blocks, and ore, not tools), chest category, or `enchanted`, `named`, `damaged`. In `group` the order also gives its categories the nearest chests. A zone's empty cells fill last, what a zone can't hold flows on, and a layout where any item's total would change isn't applied.
- 📌 **Pins in the main inventory.** “Three bundles at the start of the inventory” pins `inventory:1`–`inventory:27`, row by row from the top left, as the hotbar's 1–9 do. What the slot held joins the sort.
- 🔎 **`sort_inventory` reports the main inventory's rows** besides the hotbar and offhand, so the assistant checks a layout against what's really there.

## 5.7.1

- 🧹 **A sorted hotbar holds what you asked for.** With pins, the hotbar keeps just them: everything else goes into the main inventory, and a hotbar slot fills only when that's full. Before, the free hotbar slots took the first items of the alphabet, so “sword first, bow second” left arrows and a bundle between the pins. Without pins, a sorted hotbar keeps its weapons, tools, food, torches, buckets, and pearls, weapons first.
- 🧱 **A `block` pin takes the fullest stack**, so “64 blocks in slot 4” no longer picks 61 cobbled deepslate over 64 nether bricks.
- 🔎 **`sort_inventory` reports the hotbar and offhand as they end up**, so the assistant tells what's really there.

## 5.7.0

Works with any Allay Server 5.x; automation agents, pinned slots, and models by tier need Allay Server 5.5.

- 🤖 **Automation agents.** “Sort my chests by category”, “smelt all my raw gold evenly”, “brew three night vision potions”, “craft me a set of diamond tools”: the chat hands the job to an agent that works in the background (`start_task`), so the player keeps chatting. It runs in wakes, each one request to the model, and between them waits for free: for the player's answer or button, for its furnaces or brewing stands, or for a time. It talks under its own name (“Automation agent: chests”) with buttons, and the chat passes free-text answers on. An agent may start one more level of agents, which carries on after its parent finishes: “smelt the iron, then make blocks” ends with a crafting agent doing the blocks. At most `automation.max_tasks` (2) per player, for `automation.max_minutes` (30) and `automation.max_wakes` (40). It pauses while its player is away from where it started (`automation.pause_distance`, 48), stops when they leave, and `/allay cancel` stops it.
- 📦 **Chests.** The player picks chests with left clicks (they glow, a double chest is one, a second click unpicks; nothing breaks), or presses **[All nearby]** to take every chest reached across gaps of up to `automation.chests.gap` (6), at most `automation.chests.max` (64). The agent reads the signs on a chest (on its sides, hanging, or on top, any text) and its item frames, and sorts each chest, groups everything by category across them with frequent categories nearest and overflow into the next chest, or moves given items or categories into given chests. It reads in one tick, plans off the server thread, checks that nothing changed and every total matches, and writes in one tick. Labels show what went where; **[Put back]** undoes it for `automation.chests.undo_minutes` (5) if nobody touched the chests. Grouping chests outside claims waits for the player's yes. Chests a hopper feeds or empties, chests someone has open, and chests the player couldn't open stay out and glow red with why.
- 🔥 **Furnaces.** Smelting spreads over free furnaces, blast furnaces, and smokers near the player, each by what it can cook and how fast: a blast furnace takes twice the ore, so all finish about together. Several items go at once (“the iron and the gold”), each furnace on one of them, and an item the player has none of is skipped; the scan lists what the player could smelt, with exactly the fuel the load needs (coal blocks split into coal, leftovers go back where they came from), and refills from what it reserved until everything is smelted. Busy furnaces glow red. The output goes where the player says.
- 🧪 **Brewing.** The agent brews from the game's own recipes in the stands that suit, starting where a stand already is: an awkward potion skips a step. It takes only what's needed, turns a blaze rod into powder, puts the next reagent in when each step ends, and says what's missing when something is.
- 🛠️ **Crafting** at a real crafting table near the player, from this server's recipes, with the parts it lacks crafted first (planks, sticks). Ingredients come from the inventory, bundles (nested too), the ender chest when one stands near, and chests the player may open; never from inside shulker boxes, and never named or enchanted items.
- 🛡️ **Nobody gets robbed.** Automation touches only what the player could open themself (GriefPrevention's container trust), and every furnace, brewing stand, and crafting table must be within `automation.radius` (16) and in plain sight, nothing solid between it and the player's eyes, glass included (`automation.line_of_sight`). An agent reserves what it will take later; when the player, another player, or another agent takes it or touches its blocks, it stops and says “you or another player got in the way of the automation” with the reason.
- 🔑 **Permissions.** `allay.automation` plus `allay.automation.chests`, `.furnaces`, `.brewing`, and `.crafting`, everyone's by default, so an admin can keep automation for some players. `automation.enabled` and `automation.<kind>.enabled` switch it off for everyone.
- 💸 **Spending limits by tier**, whoever pays. `quota.monthly_usd` is the server's budget a month, opened day by day with `quota.daily` (on). Tiers are weights (`quota.tiers`, like `default: 1`, `vip: 3`) given by `allay.quota.<tier>`; a player gets the biggest they hold, operators the biggest there is. Nobody books money: a player's limit is the open budget times their weight over the weights of everyone who asked this month, so a VIP alone may use it all and three players split it 60/20/20. Whoever reached their limit hears when more comes. A weight that isn't a number above 0 stops Allay for everyone: admins hear why, also when they join, and players are told to ask an admin. Spending lives in `quota.json`.
- 🎚️ **A model by tier.** `models` in config.yml maps a tier to a model, given by `allay.model.<tier>`. It replaces `provider.byok.model`, and a self-hosted backend (`SELFHOSTED=1`) honors it too.
- 🧹 **Pinned slots.** `sort_inventory` sorts the hotbar and the offhand when the player asks, and puts items in given slots first: “sword first, water second” takes the best sword by material, enchantments, and durability. Potions pin by kind, `potion:healing` or `splash_potion:fire_resistance`, the strongest first, and any item by its id, like a golden apple. The assistant can remember the layout. In creative the inventory closes first, so the client can't write its old copy back.
- 🔦 **find_item lights up what it finds**, with “diamond ×12” over each chest, and puts the way button under the answer when the nearest is far or behind a wall.
- 📦 A double chest counts once. `find_item` and `scan_nearby_containers` read both halves, and each half holds the whole chest, so everything in it was counted twice.
- New texts in `lang/`: the `automation` and `quota` sections, `tools.start_task`, `tools.message_task`, `tools.cancel_task`, `tools.say`, `tools.wait`, `tools.finish`, `tools.pick_blocks`, `tools.chests`, `tools.smelt`, `tools.brew`, `tools.craft`, `summary.task_started`, `summary.task_messaged`, `summary.task_cancelled`, `summary.chests_read`, `summary.chests_planned`, `summary.chests_applied`, `summary.chests_restored`, `summary.picked`, `summary.picking`, `summary.furnaces`, `summary.smelting`, `summary.smelting_started`, `summary.stands`, `summary.brewing_status`, `summary.brewing_started`, `summary.brewing_missing`, `summary.collected`, `summary.craft_plan`, `summary.craft_missing`, `summary.crafted`, `buttons.way_to_chest`, `buttons.hover.reply`, `buttons.hover.undo`, and `buttons.hover.pick_done`, `pick_all`, `pick_cancel`, and `quota.no_tier`.

## 5.6.0

Works with any Allay Server 5.x; drafts and the redstone debugger need Allay Server 5.4.

- 🏗️ **Drafts of any build.** A new tool, `show_draft`, takes a build the assistant writes as layers of characters over a palette, up to 20×15×20, and puts it in front of you as glowing ghosts only you see, turned the way you look. One layer lights up at a time from the bottom. Every half second the plugin compares it with the world: a block in place goes out, a wrong one gets a red box naming what belongs there, and once a layer is done the next one lights up, with a sound and a boss bar counting. The assistant adds bigger builds in parts, moves and turns a draft, and checks it against what you built. Only where you may build; one draft per player, dropped on `/allay cancel`, when you leave, or on a restart.
- 🔌 **A redstone debugger.** `inspect_redstone` reads the machine under the crosshair: dust power and where it connects, repeater delays with where the signal comes from and goes, comparator modes, torches, observers, pistons, levers, lamps, doors, hoppers, and the signal each mechanism receives. The plugin finds the faults the game's rules make certain: a dot of dust, a burned-out torch, a piston jammed by an immovable block or more than 12, a locked hopper or repeater, dust that runs past a lamp or a door. Only where you may build: outside claims, or with build trust in GriefPrevention.
- 👻 Ghosts of chests, beds, signs, banners, heads, and shulker boxes show as their item: a block display can't draw blocks the client renders as entities.
- New texts in `lang/`: `tools.inspect_redstone`, `tools.show_draft`, `summary.redstone`, `summary.draft`, `summary.draft_check`, `summary.draft_cleared`, `messages.draft_cleared`, and the `drafts` section.

## 5.5.0

Works with any Allay Server 5.x; marks need Allay Server 5.3.

- ✨ **Marks over blocks.** A new tool, `show_marks`, draws what only the asker sees: a glowing box around a block that's there, a smaller glowing ghost of a block to place, and a label over either. “Where's my redstone?” lights up the chests that hold it, labeled with what's inside; “where do I put torches?” puts ghost torches on the dark spots, and each goes out as you place a block there. The marks are display entities shown to the asker alone, never saved with the world, and gone after their time (`tools.show_marks.default_seconds` 120, `max_seconds` 600), on `/allay cancel`, or when you leave. `tools.show_marks.max_marks` (64) and `radius` (48) bound them.
- 🔒 **Only containers you could open.** Marks glow through walls, so ores, spawners, and vaults never glow, and Allay now tells a player about a container only when they could open it themselves: outside a GriefPrevention claim, or with container trust in it. An unopened structure chest stays hidden from everyone, since Paper's anti-xray hides it too. This holds for `scan_nearby_containers`, `find_item`, `look_at`, and `inspect_item` as well; `look_at` says why a container is locked instead of reading it. GriefPrevention is optional.
- New texts in `lang/`: `tools.show_marks`, `summary.marks`, `summary.marks_cleared`, and `messages.marks_cleared`.

## 5.4.0

Works with any Allay Server 5.x; the way to players needs Allay Server 5.2.

- 📍 **The way to another player.** “Who's around?” and “take me to Sergey” now work. A new tool, `get_nearby_players`, tells the assistant who is on your locator bar, the dots above the experience bar, and no more: a name, the distance, and the direction, by the game's own rules. Whoever crouches, wears a carved pumpkin or a mob head, or drinks invisibility is off the bar, and off Allay's too; spectators see everyone, and nobody sees them. With the `locator_bar` game rule off, Allay leads nobody. Players elsewhere show up only by name, never where they are; vanished players stay hidden.
- 🏃 **The trail follows them.** `point_to` takes `player` instead of coordinates: the trail and the compass follow that player as they run, a pillar of particles stands over their head once they're near, and it ends when you reach them. While they hide, the trail goes dark, as their dot does; it ends after 15 seconds off the bar or when they leave. `tools.point_to.follow_seconds` (180) sets how long it lasts when the assistant names no length.
- 🔘 **Up to six buttons under an answer**, so “to whom?” can offer the way to each player around. A way button to a player checks your locator bar again when you press it, and under a public answer nobody gets the way to themselves.
- New texts in `lang/`: `tools.get_nearby_players`, `summary.players`, `summary.players_none`, `summary.distance_to`, `messages.found`, `messages.trail_lost`, `buttons.hover.follow`, `buttons.path_to_player`, and `buttons.player_hidden`.

## 5.3.0

Works with any Allay Server 5.x; the new parts need Allay Server 5.1.

- 🧭 **The assistant knows which dimension you're in.** Every question carries it, so “remember the portal here” or “the bastion at -498 68 -629” from the Nether goes to the Nether. The tool line under a note says where it went: “noted: -20 95 120, Nether”.
- ✨ **Way trails as long as you need, in many looks.** Ask for “the way for 5 minutes”, or let the assistant pick by the distance. The trail goes out once you arrive, a new one replaces the old, and `/allay cancel` hides it when no answer is running. Twelve looks: sparkle, flame, soul, glow, magic, spark, cherry, notes, hearts, snow, totem, and happy. `tools.point_to.default_seconds` (30) and `max_seconds` (600) set the length; way buttons use the default instead of 15 seconds.
- 🔘 **Buttons under public answers are for everyone.** Every player online gets them; a question button asks everyone again, and the way, note, and copy buttons work for whoever presses them.
- 📋 **A copy button.** The assistant can put coordinates or any short text into your clipboard.
- 🧪 **Brewing, from the game itself.** 26.3 keeps potions as recipes in its data, which Spigot shows only by their result, so “how do I brew night vision?” used to find nothing. Allay now reads the recipes from the server jar: a new tool, `get_brewing_recipe`, gives every step from a water bottle, and a brewing window (`/allay brewing <potion>` or a button) shows them, the bottle turned by gunpowder and dragon's breath last.
- ⚒️ **The anvil plans with what you have.** `get_anvil_plan` without enchantments takes the enchanted books and the enchanted items of the same kind from your inventory and tries every order, priced exactly as the game's anvil does: equal levels go one up, only the right side is paid for, conflicts cost a level. Two bows get “the weaker one on the left, 9 levels”, not a guess. The anvil window (`/allay anvil` or a button) shows each step with your actual items and where they lie.
- 🔥 **Smelting gets a recipe button** too: the recipe window already drew furnaces, smokers, and blast furnaces.
- 📝 **`/allay memory <text>`** keeps something without asking the assistant. Coordinates in the text (`x y z` or `x z`) make a note in your notebook, in the dimension the text names (“nether”, «ад», “the end”) or the one you stand in; anything else becomes a remembered fact.
- New texts in `lang/`: `messages.arrived`, `messages.trail_stopped`, `memory.remembered`, `memory.noted`, `memory.full`, `memory.remember_usage`, `buttons.hover.copy`, `buttons.hover.brewing`, `buttons.hover.anvil`, `messages.no_brewing`, `messages.anvil_nothing`, `messages.brewing_usage`, `summary.anvil_nothing`, `summary.brewing_steps`, `tools.get_brewing_recipe`, the `menus.brewing` and `menus.anvil` sections, and the `dimensions` section; `summary.note_saved_at` gained `{dimension}`.

## 5.2.0

- 📊 **Plan.** With Plan installed, each player's page gets an Allay block: chat mode (also a column in the players table), the language Allay talks to them in, their goals with progress, notes, and waiting reminders. The server's “Plugins Overview” shows who pays for the model, how many players use chat mode with a pie of on and off, and, with a subscription, its plan, paid-until date, and the share of the limit used. Plan is optional.
- 👻 **Vanish support.** A player that SuperVanish, PremiumVanish, Essentials, or another vanish plugin hides no longer shows through Allay: not in the online count, not among the players the assistant sees nearby or looks at, not in `/allay locale` or its completion. Only the player themself and operators still see them. A vanished player's `/askall` or `?` question goes private instead, with a new line, `public.vanished`, telling them why.

## 5.1.0

- 🧩 **PlaceholderAPI.** Write `{papi:name}` in any text in `lang/` to show `%name%` for the player the message is about: the reader, or the asker in a question or answer everyone sees. `{papi:luckperms_prefix}{player}` puts a rank in front of a name. Allay fills only the tags your texts hold, never a `%` or a `{papi:…}` in a player's question or in an answer. Your texts don't change.
- 📊 **`%allay_chat_mode%`, `%allay_thinking%`, `%allay_goals%`, and `%allay_language%`** for TAB, scoreboards, and other plugins. PlaceholderAPI is optional; without it the tags show nothing.

## 5.0.2

- ✦ **Your own line in chat mode gets the star too.** What you write to Allay in chat mode now shows as “✦ your question”, without your name, so your side of the talk matches Allay's. The text has a new key, `chat_mode.own`, so servers with their own `lang/` files get it without editing them; the old `chat_mode.echo` is no longer read.

## 5.0.1

- ✦ **Every answer carries Allay's name.** Private answers and `/askx` notes now start with “✦ Allay »”, as answers everyone sees already did, so they stand out in a busy chat.

## 5.0.0

Your server now chooses who pays for the AI model: its own key, or Allay's subscription paid in Telegram Stars. Needs Allay Server 5.0 for both; an older backend answers as before.

- 💳 **A subscription in Telegram Stars** (`provider.mode: hosted`, the default). `/allay link` gets a license, keeps it in `plugins/Allay/license.key`, and shows a **[Pay in Telegram]** button and a **[Copy link]** one; the link works once and for 24 hours. Plans: a week for 150 ⭐, a month for 500 ⭐ that renews by itself, a year for 10 000 ⭐. The bot sees your server's MOTD, so you know which server you pay for.
- 🔑 **Your own key** (`provider.mode: byok`): `provider.byok` takes the `base_url` of any OpenAI-compatible API, the `api_key`, the `model`, and the `search_model` (empty: no web search). An `X-OpenRouter-Token` in `http.custom_headers` keeps working.
- 📊 **`/allay status`** shows who pays. For your key: the API, the models, and the key with all but its ends hidden. For the subscription: linked or not, active or not, the plan, the date it's paid until in your language, a bar of this period's limit (“62% of this period's limit used, refills 14 November”), and whether it renews.
- 🔔 **Operators hear about the subscription.** Every 5 minutes Allay checks it and tells operators online when a payment comes in, when the limit passes 80% and 100% (with a **[Renew]** button), and three days before a subscription that doesn't renew ends. `subscription.json` remembers what they heard, so a restart doesn't repeat it.
- ☕ **Friendly notes when Allay can't answer.** A lapsed subscription reads “Allay is taking a break on this server: the subscription isn't paid. Your admins know what to do.” A spent limit says when it refills. Operators also get a **[/allay link]** button, or what to fix in `config.yml` when the provider refuses the key, doesn't know the model, the model can't call tools, or the API address isn't allowed. Too many questions at once says how many seconds to wait.
- 🌍 **`/allay locale [player]`** shows which language a player reads Allay in and why: their IP's country, their game's language, or the default.
- 🔒 The key and the license never go into the log; anywhere they show, only their ends do. `license.key` is readable by the server's user only.
- New permission `allay.admin` (operators) for `/allay status`, `/allay link`, `/allay locale`, and the notes about the subscription.

## 4.0.1

- The move from MCBuddy keeps only the language lines you wrote yourself. A server that has run since MCBuddy 2.4 kept that release's texts in `lang/`, and 4.0.0 took them for your own; now texts any MCBuddy release shipped give way to Allay's.

## 4.0.0

MCBuddy is now **Allay**, after the friendly mob that brings players what they need. Needs Allay Server 4.0 for the renamed tools; MCBuddy Server 3.0 still answers, but the assistant then knows the old names.

What an admin does: swap `mcbuddy-spigot-*.jar` for `allay-spigot-*.jar` (don't keep both) and update the permissions in your permission plugin.

- 📦 **The data folder moves by itself.** On the first start Allay moves `plugins/mcbuddy-spigot/` into `plugins/Allay/`: `config.yml`, `lang/`, `notes/`, `goals/`, `timezones.json`, `players.json`, `server-id`, and `geoip/`, logging each file. The server's id stays, so the shared thread of public questions carries on. Settings already in `plugins/Allay/` (a `config.yml` your setup copies in at start) win over MCBuddy's. If a move fails, everything goes back and the old folder stays as it was; if both folders already hold players' data, nothing moves and the log says so.
- ✏️ **Texts take the new name where you didn't change them.** The default book author becomes “Allay”, default sounds become the new ones, and lines in `lang/` you never touched give way to Allay's texts. Lines you changed stay, with MCBuddy and `/buddy` renamed.
- ⚠️ **`/buddy` is `/allay`**, with the same subcommands and settings window; there's no `/buddy` alias. **Permissions are `allay.*`** instead of `mcbuddy.*`: `allay.ask`, `allay.askall`, `allay.askall.nocooldown`, `allay.chat`, `allay.askx`, `allay.reload`, with the same defaults.
- 🪽 **Allay sounds like an allay.** A curious hum while it works (`ENTITY_ALLAY_AMBIENT_WITH_ITEM`), a happy chortle with the answer (`ENTITY_ALLAY_ITEM_GIVEN`), and a soft sigh on errors (`ENTITY_ALLAY_AMBIENT_WITHOUT_ITEM`). Each pitch moves a little (`ui.sounds.pitch_variation`), and players who read a public answer hear a quiet chortle too (`ui.sounds.public_answer`).
- 🌐 **The backend lives at `https://allay.chat`**; mcbuddy.ru is gone. A moved `config.yml` that still points at mcbuddy.ru points at allay.chat.
- 💬 Texts in all five languages say Allay; public lines read “✦ Allay »” in the allay's blue, and some status verbs are the mob's own: humming a tune, circling the note block, looking for something shiny.
- 🧰 The tools `get_buddy_guide` and `buddy_settings` are `get_allay_guide` and `allay_settings`; their switches in `config.yml` move along with the data.
- 🧱 **Two modules.** `core` holds everything that doesn't touch a game platform; `spigot` is the plugin for Spigot, Paper, and their forks. The jar is `allay-spigot-X.Y.Z.jar`, and the libraries inside are relocated under `chat.allay.libs`.
- The User-Agent is `Allay-Spigot/<version>`.

## 3.1.0

- ⚙️ **`/buddy` opens a settings window**, built like the recipe window: switches for chat mode and its tip with a green or red pane under each, your time zone and the time there, what MCBuddy remembers, starting over (shift-click, so a stray click can't wipe the conversation), and the recipe, enchanting, and villager windows. Like the other windows, it only shows pictures.
- `get_buddy_guide` tells the assistant about the window, so it can point players there.

## 3.0.0

Needs MCBuddy Server 3.0. An older backend still answers, but it doesn't know a question is public, keeps no shared thread, and lacks the new tools.

- 🗨️ **Chat mode.** `/buddy chat` sends everything a player writes in chat to the assistant instead of the other players. `!` in front runs commands like `/askx`, `?` asks in front of everyone, `~` writes to the ordinary chat; a space after the prefix doesn't matter. It stays on between visits, and the plugin reminds the player when they join.
- 💡 **A tip under the second `/ask`** of a visit, quiet and gray like Claude Code's: one button turns chat mode on, the other hides the tip for good (`/buddy hints` brings it back).
- 📣 **`/askall`**: every player online sees the question and the answer. Public questions share one thread per server on the backend, so a friend can follow up on someone else's question. `askall.cooldown_seconds` (30) keeps the chat from flooding; `mcbuddy.askall.nocooldown` skips it.
- 📖 **The assistant explains MCBuddy.** `get_buddy_guide` tells it the commands, chat prefixes, settings, and features this player has; `buddy_settings` switches chat mode and the tip when the player asks in words.
- 🧩 **`get_server` lists the server's plugins** with versions and short descriptions; `tools.server.hidden_plugins` leaves some out.
- ⚠️ **New command layout.** `/ask` only asks now. `cancel`, `new`, `memory`, `forget`, `recipe`, `enchants`, `villagers`, and `reload` moved to `/buddy`. New permissions: `mcbuddy.askall`, `mcbuddy.chat`, `mcbuddy.askall.nocooldown`.
- The server tool line under `get_server` shows its text again instead of a key.

## 2.4.0

- 🌍 **Each player reads their own language.** Every text moved from `config.yml` to `lang/<language>.yml`: English, Russian, Spanish, Portuguese, and German. The plugin picks a player's language by the country of their IP, with DB-IP's free database it downloads and refreshes itself, then by their game's language, then `locales.default`. Tool lines, buttons, windows, boss bars, and errors all follow it.
- ⚙️ **`config.yml` holds settings only, in English.** A 2.3 config still loads; its texts (`messages`, `menus`, `error_messages`, and the text keys under `ui`) are ignored, so move your changes to `lang/`. `/ask reload` reloads the languages too.
- 👀 **`look_at` replaces seven tools**: `get_target`, `get_block_info`, `get_container`, `get_enchant_options`, `get_mob_info`, `get_villager_trades`, and, for maps, `get_map_info` (now part of `inspect_item`). One call tells the assistant whatever is under the crosshair.
- 🔍 **`find_item`** finds where an item is and how many there are: inventory, armor, ender chest, and containers around, inside shulker boxes and bundles too. `scan_nearby_containers` now only lists what's around.
- 🔎 **`inspect_item`** reads one item in full: attack damage and speed as the tooltip shows them, potion effects with their seconds, trims, dye color, the anvil's repair cost, a signed book's title and author (never its pages), fireworks, maps, and what a shulker box or bundle holds; `inner_slot` reads an item inside one.
- 🎒 **Bundles and shulker boxes count.** The inventory and containers say what the bundles and shulker boxes in them hold, so “how many arrows do I have?” counts the ones in a bundle too.
- 🧭 **Absolute coordinates everywhere.** Notable blocks, containers, dark spots, and brewing stands come with x, y, z, distance, and direction instead of offsets. Dimensions are `overworld`, `the_nether`, and `the_end`; notes and goals saved with the old names still match. `point_to` takes a dimension and refuses a spot in another one.
- ⏰ **`reminders` and `goals`** each replace three tools with one `action`. Advancements now carry the key a goal needs. `get_item_durability` covers the whole inventory, not just the hotbar.
- Tool switches in `config.yml` follow the new names; switches for the old ones are ignored.
- 📦 **One jar per release**, `mcbuddy-spigot-X.Y.Z.jar`, with everything inside; there is no `-all` jar any more. Releases are made by hand from the “Release” workflow.
- Needs MCBuddy Server 2.4: the backend serves only the new tool names.
- README in English with a Russian copy, and the MIT license.

## 2.3.0

- Buttons under answers, the recipe, enchanting, and villager windows (`/ask recipe`, `/ask enchants`, `/ask villagers`), goals with a boss bar, long answers as books, and each player's time zone.

## 2.2.0

- A notebook of places, tools that read containers, blocks, maps, enchanting tables, mobs, dark spots, brewing, farms, gear wear, experience, and food, and `ping_me`.

## 2.1.0

- Places, villager trades, `point_to`, `sort_inventory`, reminders with ids, operator-only tools, `/ask new`, `/ask memory`, and `/ask forget`.

## 2.0.0

A rewrite for Spigot 26.3 and Java 25.

- Answers stream over protocol v2, and the assistant calls game tools about the asking player.
- A live status in the action bar and chat while it works; `/ask cancel` and tab completion.

## 1.4.2

- Build fixes and newer dependencies.

## 1.3.0

- Clear messages for backend, network, and plugin errors; configurable timeouts.

## 1.2.0

- `/ask reload` and `/askx reload` reload the config; sounds load reliably.

## 1.0.0

- `/ask` and `/askx` with the MCBuddy backend.
