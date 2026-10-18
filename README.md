# Allay

💬 **Allay** puts an AI helper into your server's chat, named after the friendly mob that brings players what they need. A player types `/ask`, or just writes in chat mode, and gets an answer grounded in their real game: their inventory, what they're looking at, the chests around them, this server's recipes and rules. The assistant talks like a friend who knows Minecraft inside out, and each player reads the plugin in their own language.

[Русская версия](https://github.com/allay-ai/minecraft/blob/master/README.RU.md)

## What you get

- 💬 **`/ask` about anything in the game.** “What should I do next?”, “which sword is better?”, “where did I put my redstone?”, “how many hits for this creeper?”. The assistant looks things up in the game while it answers instead of guessing.
- 🗨️ **Chat mode.** When friends talk on Discord and the game chat is free, `/allay chat` sends everything a player writes to the assistant. `!` in front runs commands, `?` asks in front of everyone, `~` writes to the ordinary chat. After a second `/ask`, a quiet tip offers it, with a button to switch it on and one to hide the tip.
- 📣 **Questions everyone sees.** `/askall` shows the question and the answer to every player online. Public questions share one thread per server, so a friend can follow up on someone else's question. Everyone gets the buttons under a public answer.
- 📖 **It explains itself.** Ask “what can you do?” or “how do I turn off chat mode?”: the assistant reads the commands and settings this player really has, and switches chat mode when asked in words.
- 🧩 **It knows the server's plugins.** Questions about graves, claims, or AFK get answers for the plugin and version that really run.
- 👀 **It sees what the player sees.** One look covers the block under the crosshair with its drops, a chest's contents, an enchanting table's bookshelves, a mob and the hits it takes, a villager's trades.
- 🎒 **It knows the player's things.** Inventory, armor, the ender chest, what's inside bundles and shulker boxes, and any item in full: enchantments, potion durations, trims, a weapon's damage and speed as the tooltip shows them.
- 🔍 **It finds things.** Where an item is and how many there are: in the inventory, the ender chest, and the chests nearby, inside shulker boxes too. The chests it finds light up with what's inside written over them, and when the nearest is far or behind a wall, a button shows the way.
- 🧹 **A tidy inventory.** “Sort my inventory, sword first, water in the second slot”: stacks merge at once, the hotbar and offhand move only when you ask, and a pinned slot takes the best item of its kind. Say how the rest should lie: “iron first”, “food in the last row”, “bundles in the right column”, “by category, each kind on its own row”, “don't touch the books”, or “just merge the stacks”; chests take the same words. Ask the assistant to remember the layout, and next time “sort my inventory” is enough.
- 🧭 **It shows the way.** A compass and a particle trail to a death point, a village, a cherry grove, or a place from the player's notebook. The trail lasts as long as the player asks or the walk needs, comes in a dozen looks from flames to hearts, and goes out once they're there. Ask for the way to another player, and the trail follows them as they run.
- ✨ **It marks blocks for you.** “Where's my redstone?” lights up the chests that hold it, with what's inside written over them; “where do I put torches?” puts glowing ghost torches on the dark spots. Only you see the marks, and they glow through walls, so Allay never marks ores, spawners, or vaults, and only containers you could open yourself.
- 🏗️ **It drafts what you want to build.** “How do I build a small oak house?” or “draw me a sugar cane farm” puts the build in front of you as glowing ghosts, layer by layer, up to 20×15×20. Each block you place goes out, a wrong one turns red and says what belongs there, and the next layer lights up when one is done; a bar counts. Bigger builds come in parts. Ask to turn or move it, or to check it against what you built.
- 🤖 **Automation agents do the chores at your base.** “Sort my chests by category”, “smelt all my raw gold evenly”, “brew three night vision potions”, “craft me a set of diamond tools”. The chat hands the job to an agent that works in the background while you keep chatting. It writes to you under its name, asks with buttons, and waits for furnaces and brewing stands without costing anything. You stay near: it pauses while you're away from the base, and every furnace, stand, and crafting table it uses must be in plain sight, with nothing solid, glass included, in between.
- 📦 **Chests the way you want them.** Left-click the chests to pick them, or press “All nearby”. The agent reads the signs and item frames on them and sorts each chest, groups everything by category across them, or moves what you name; it asks when a sign doesn't say enough. Labels over the chests show what went where, and **[Put back]** undoes it for 5 minutes. Chests a hopper feeds or empties, chests someone has open, and chests you couldn't open yourself stay out.
- 🔥 **Furnaces, brewing stands, and the crafting table.** Smelting spreads evenly over free furnaces, blast furnaces, and smokers, each by what it can cook, with exactly the fuel it needs; coal blocks split into coal. Brewing starts where a stand already is (an awkward potion saves a step), takes only what's needed, and turns a blaze rod into powder. Crafting needs a real crafting table nearby and takes ingredients from the inventory, bundles, the ender chest, and the chests around, never from inside shulker boxes. What's left over goes back. If you or anyone else takes what an agent counts on, it stops and says so.
- 🔌 **It debugs redstone.** “Why doesn't my door open?” reads the machine you look at: dust power, repeater delays, comparators, pistons, torches. The game's rules point at the fault (dust running past the door, a burned-out torch, a jammed piston), and red marks show where. Only in places you may build.
- 📒 **A notebook, goals, and reminders.** “Remember there's an arrow trader here”, “set a goal to collect 16 diamonds” (a boss bar at the top), “remind me in 5 minutes”. The assistant knows which dimension the player is in, so a bastion noted in the Nether stays in the Nether. `/allay memory <text>` keeps something without asking the assistant.
- 🧠 **It remembers what it looked up.** Each answer keeps what the tools found, so “and how many were in that chest?” or “what dropped when I died?” needs no second look. A long talk folds into a short summary, and a talk left alone simply expires. With Allay Server 5.5.
- 🪟 **Buttons and windows.** Answers end with buttons: a follow-up question, the way to a spot, coordinates to copy, a recipe, the brewing steps, the anvil plan, enchantments, the villagers around. The assistant reads potions from the game's own brewing recipes, and plans the anvil with the books and items you really have: which goes left, which right, and what each step costs. The recipe, brewing, anvil, enchanting, and villager windows show pictures only, so nothing can be taken from them.
- 🌍 **Every player reads their own language.** English, Russian, Spanish, Portuguese, and German come bundled; the plugin picks one by the player's country, then by their game's language. The assistant answers in the language of the question.
- 📊 **Plan**, if you have it: chat mode, goals, notes, and language on each player's page, and who pays for the model in the server's overview.
- 🧩 **PlaceholderAPI**, if you have it: `{papi:luckperms_prefix}` in a text, and `%allay_chat_mode%` and friends for TAB and scoreboards.
- 🪽 **It sounds like an allay.** A curious hum while it works, a happy chortle with the answer, a soft sigh when something fails, each a little different from the last.
- ✻ **A live status like Claude Code:** a spinner and a changing verb in the action bar, and a chat line for each tool the assistant uses.
- 🔒 **Private and safe.** Tools know only the player who asked. Other players show up only as the asker's locator bar shows them: a name, a distance, and a direction, and nobody who hides from the bar by crouching, a pumpkin, or invisibility. Tools that give items or change the world are offered to operators only, and the plugin checks again before running one. Automation only moves the player's own things between containers they could open, and crafts only at a real table from real ingredients. Every tool can be switched off.
- 👻 **Vanish stays vanish.** A player hidden by SuperVanish, PremiumVanish, Essentials, or any plugin that marks them `vanished` drops out of the online count, what the assistant sees around a player, and `/allay locale`, for everyone but themself and operators. Their `/askall` or `?` question stays private, with a line telling them why.

## Try it live

Allay runs on Moonlands, my hard survival server with friends. Come in and ask it something.

[![Play on Moonlands: pure vanilla, hard mobs, AI, claims. minecraft.mairwunnx.com](https://raw.githubusercontent.com/allay-ai/minecraft/master/media/og-adv-image.png)](https://minecraft.mairwunnx.com)

Join at `minecraft.mairwunnx.com`. The site has the live map, player stats, and the hall of fame.

## Requirements

- Spigot or Paper 26.3, or a fork of them, like Purpur
- Java 25
- An Allay backend: `https://allay.chat` by default, or your own [Allay Server](https://github.com/allay-ai/server)

## Install

1. Download `allay-spigot-X.Y.Z.jar` from [Releases](https://github.com/allay-ai/minecraft/releases).
2. Put it in your server's `plugins/` folder and restart the server.
3. Edit `plugins/Allay/config.yml` and the texts in `plugins/Allay/lang/` if you like.
4. Pick how to pay for the model (see below): run `/allay link` for a subscription, or put your own key in `provider.byok`.
5. Run `/allay reload`, then try `/ask how do I craft a beacon?`.

## Paying for the model

Every answer costs a little: providers charge for the AI model behind Allay by the word. Your server pays one of two ways, picked by `provider.mode` in `config.yml`.

### Your own key (`byok`)

Bring a key from [OpenRouter](https://openrouter.ai) or any other OpenAI-compatible API, and you pay the provider directly:

```yaml
provider:
  mode: byok
  byok:
    base_url: "https://openrouter.ai/api/v1"   # any OpenAI-compatible API
    api_key: "sk-or-v1-…"
    model: ""             # empty: the backend's default; it must be able to call tools
    search_model: ""      # empty: no web search; on OpenRouter, say, "perplexity/sonar"
```

Run `/allay reload`, then `/allay status` to check: it shows the API, the models, and the key with all but its ends hidden. Allay looks into the game through tools, so a model that can't call them won't work; the note under a failed question tells an operator what to change. On `allay.chat` your own key costs nothing extra, up to 20 questions a minute and 2000 a day per server. A key from before 5.0 in `http.custom_headers` (`X-OpenRouter-Token`) keeps working as your own OpenRouter key.

### A subscription (`hosted`, the default)

Allay's backend answers on its own key and models, and you pay in Telegram Stars:

1. Run `/allay link`. The plugin gets a license, saves it in `plugins/Allay/license.key`, and shows a **[Pay in Telegram]** button (the console gets the plain link). The link works once and for 24 hours.
2. The bot shows the plans. Pay with Stars:

   | Plan | Stars | Period |
   |---|---|---|
   | Week | 150 ⭐ | 7 days |
   | Month | 500 ⭐ | 30 days, renews by itself |
   | Year | 10 000 ⭐ | 365 days, the limit refills every 30 days |

3. `/allay status` shows the plan, the date it's paid until, and a bar of how much of this period's limit is used.

Each plan has a usage limit per period. Allay checks the subscription every 5 minutes and tells operators online when a payment comes in, when the limit passes 80% and 100%, and three days before a subscription that doesn't renew runs out, with a **[Renew]** button. When the subscription lapses, players get a short, friendly note instead of an answer, and operators also get a link to pay. To pay again, run `/allay link`: it keeps the same license.

⚠️ **Keep `license.key` to yourself.** Whoever has it uses your subscription. Back it up with the server, but don't share it or commit it to a public repository. It never goes into the log, and `config.yml` doesn't hold it, so you can share your config when asking for help.

### Your own backend

An [Allay Server](https://github.com/allay-ai/server) started with `SELFHOSTED=1` has no billing: it answers every plugin on its own key. Point `server.url` at it, and there's nothing to pay or link; `/allay status` says so.

### Limits per player

Whoever pays, you can cap what Allay spends. `quota.monthly_usd` is the whole server's budget for a month. Tiers are weights, given by the permission `allay.quota.<tier>`, with any tier names you like:

```yaml
quota:
  enabled: true
  monthly_usd: 20.0
  daily: true          # the budget opens day by day: by the 10th, a third of it
  tiers:
    default: 1          # everyone
    vip: 3              # players with allay.quota.vip weigh three times as much
models:
  vip: "anthropic/claude-sonnet-5.5"   # players with allay.model.vip get this model
```

Nobody books money they don't use. A player's limit is what's open of the budget so far, times their weight, over the weights of everyone who asked this month. A VIP alone may use all of it; with one regular player around, it's 75% and 25%; a third player makes it 60%, 20%, and 20%. There's no guarantee, only a fair split. A player with several tiers gets the biggest weight, and operators get the biggest there is. Whoever reached their limit hears when more comes: tomorrow with `daily`, else next month. A weight that isn't a number above 0 makes the config wrong: then Allay answers nobody, tells admins what's wrong when they join, and asks players to talk to an admin. Spending lives in `quota.json`. A model by tier replaces `provider.byok.model`, and a self-hosted backend honors it too.

## Coming from MCBuddy

Allay is MCBuddy under a new name, from 4.0.0 on. Swap `mcbuddy-spigot-*.jar` for `allay-spigot-*.jar` and start the server; don't run both.

- **The data moves by itself.** On its first start Allay moves `plugins/mcbuddy-spigot/` into `plugins/Allay/`: settings, languages, notes, goals, time zones, players' settings, and the server's id, so the shared thread of public questions carries on. It logs each file. A `config.yml` already in `plugins/Allay/` (say, one your setup copies in at start) wins over MCBuddy's. If both folders already hold players' data, it moves nothing and says so.
- **Texts follow the new name** where you left them as they were: the backend's address (mcbuddy.ru is gone; it's allay.chat now), the book author, the default sounds, and the lines of `lang/` you never changed. Lines you changed stay yours, with MCBuddy and `/buddy` renamed.
- **`/buddy` is `/allay` now**, with the same subcommands and window, and the permissions are `allay.*` instead of `mcbuddy.*`, with the same defaults. Update your permission plugin.

## How a player gets their language

The plugin tries the sources in `locales.sources`, in order:

1. **`ip`:** the country of the player's IP address. `locales.countries` maps languages to country codes: Russia, Belarus, and Kazakhstan get Russian; Spain and Latin America get Spanish; Brazil and Portugal get Portuguese; Germany, Austria, and Switzerland get German.
2. **`client`:** the language set in the player's game, when a file in `lang/` matches it.

When nothing matches, the player gets `locales.default`, English out of the box. The console reads the default too.

The country lookup needs no account and sends no address anywhere. On the first start, the plugin downloads DB-IP's free country database (4 MB) into `plugins/Allay/geoip/` and refreshes it every 30 days. Players who connect through a proxy or a relay show up with its address, so they get the relay's country.

Everything a player reads lives in `lang/<language>.yml`: status verbs, tool lines, buttons, windows, errors. A key missing from a file comes from the default language, then from English; an empty string turns a message off. To add a language, copy `lang/en.yml` to, say, `lang/it.yml`, translate it, add `it: [ IT, SM, VA ]` to `locales.countries`, and run `/allay reload`.

## Commands and permissions

| Command | What it does |
|---|---|
| `/ask <question>` | Asks the assistant; only you see the answer |
| `/askall <question>` | Asks in front of everyone: every player online sees the question and the answer |
| `/askx <what to do>` | Turns a request into commands and runs them as you, minus `blocked_commands` |
| `/allay` | Opens the settings window: chat mode and its tip, your time zone, memory, starting over, and the recipe, enchanting, and villager windows |
| `/allay chat [on\|off]` | Chat mode: what you write in chat goes to the assistant |
| `/allay hints [on\|off]` | The tip about chat mode under answers |
| `/allay cancel` | Stops the question it's working on, or with none, hides the way trail |
| `/allay new` | Starts the conversation over; what it remembers about you stays |
| `/allay memory`, `/allay forget <number\|all>` | Shows and erases what it remembers about you |
| `/allay memory <text>` | Remembers the text without asking the assistant. With coordinates (`x y z` or `x z`), it's a note in your notebook, in the dimension the text names (“nether”, «ад») or the one you stand in |
| `/allay recipe <item>`, `/allay enchants`, `/allay villagers` | Opens the recipe, enchanting, or villager window |
| `/allay brewing <potion> [splash_potion\|lingering_potion]` | Every brewing step from a water bottle to the potion |
| `/allay anvil` | The cheapest order to merge your enchanted books and items onto the item in your hand |
| `/allay reload` | Reloads the settings and the languages |
| `/allay status` | Who pays for the model: your key, masked, or the subscription with its plan, paid-until date, and limit |
| `/allay link` | A one-time link to pay for the subscription in Telegram |
| `/allay locale [player]` | Which language a player reads Allay in, and why: their IP's country, their game's language, or the default |

In chat mode, a line goes by its first character, with or without a space after it:

| You write | It goes |
|---|---|
| `how do I find netherite?` | To the assistant, privately, like `/ask` |
| `! give me a stack of torches` | To the assistant as commands, like `/askx` |
| `? where's the nearest village?` | To everyone, like `/askall` |
| `~ brb` | To the ordinary chat |

Chat mode stays on between visits; the plugin reminds you when you join. `players.json` keeps each player's settings.

| Permission | Who has it | What it allows |
|---|---|---|
| `allay.ask` | everyone | `/ask` and `/allay`'s memory, windows, and cancel |
| `allay.askall` | everyone | `/askall` and `?` in chat mode |
| `allay.chat` | everyone | Chat mode and its tip |
| `allay.askx` | operators | `/askx` and `!` in chat mode |
| `allay.askall.nocooldown` | operators | Asking everyone without the cooldown |
| `allay.reload` | operators | `/allay reload` |
| `allay.automation` | everyone | Automation agents at all; each kind also needs `allay.automation.chests`, `.furnaces`, `.brewing`, or `.crafting` (everyone by default). Take them away from regular players with your permission plugin to keep automation for, say, VIPs |
| `allay.quota.<tier>`, `allay.model.<tier>` | nobody | A tier of `quota.tiers` or `models` in `config.yml`, see “Limits per player” |
| `allay.admin` | operators | `/allay status`, `/allay link`, `/allay locale`, notes about the subscription, and the hint under an error that a payment or `config.yml` fixes |

## PlaceholderAPI

With [PlaceholderAPI](https://modrinth.com/plugin/placeholderapi) installed, Allay adds:

| Placeholder | What it shows |
|---|---|
| `%allay_chat_mode%` | Whether the player's chat mode is on, as PlaceholderAPI's true or false |
| `%allay_thinking%` | Whether Allay is answering the player right now |
| `%allay_goals%` | How many goals the player has |
| `%allay_language%` | The language Allay talks to the player in, like `ru` |

The other way round, write `{papi:name}` in any text in `lang/` to show `%name%` for the player the message is about: the reader for most messages, the asker in a question or answer everyone sees. So `{papi:luckperms_prefix}{player}` puts the asker's rank in front of their name. Allay fills only the tags the text itself holds: a `%` or a `{papi:…}` in a player's question or in an answer stays as written. Without PlaceholderAPI the tags show nothing.

## Plan

With [Plan](https://modrinth.com/plugin/plan) installed, Allay adds its own block to Plan's pages:

- **Each player's page:** whether chat mode is on (also a column in the players table), the language Allay talks to them in, their goals with progress, and how many notes and waiting reminders they have.
- **The server's “Plugins Overview”:** who pays for the model, how many players use chat mode, and a pie of chat mode on and off. With a subscription, its plan, the paid-until date, and how much of the limit is used, as a percentage.

Plan's labels are in English, since its panel is for admins. Allay never shows a key, a license, or prices there.

## Settings

`config.yml` holds settings only; every key has a default, so you can delete the ones you don't change.

| Key | What it sets |
|---|---|
| `server.url` | The Allay backend |
| `provider.*` | Who pays for the model: `hosted` (a subscription) or `byok` (your own key), see “Paying for the model” |
| `locales.*`, `geoip.*` | Where a player's language comes from, and the country database |
| `tools.enabled.<tool>` | Switches a tool off. `scan_nearby_containers`, `find_item`, `look_at`, and `show_marks` see only containers the player could open: outside claims, or with container trust in GriefPrevention, and never an unopened structure chest |
| `tools.show_marks.*` | Marks over blocks: how long they stay (120 s, at most 600), how many at once (64), and how far from the player (48 blocks) |
| `tools.operator.*` | Tools that give items, teleport, heal, or change time and weather; operators only |
| `tools.reminders.max_pending`, `tools.notes.max_per_player`, `tools.goals.max_per_player` | How many reminders (10), notes (200), and goals (5) one player can have |
| `tools.locate_structure_radius`, `tools.locate_biome_radius`, `tools.villager_radius` | Search radii |
| `tools.point_to.default_seconds`, `tools.point_to.follow_seconds`, `tools.point_to.max_seconds` | How long a way trail lasts when nobody says: to a spot (30) and to a player (180); and at most (600) |
| `tools.server.hidden_plugins` | Plugins `get_server` leaves out of its list, such as an anti-cheat |
| `automation.*` | Automation agents: on or off as a whole and per kind (`chests`, `furnaces`, `brewing`, `crafting`); agents per player at once (2); how long one runs (30 minutes) and how many requests it may make (40); how far its blocks may be (16) and how far the player may walk off before it pauses (48); whether blocks must be in plain sight (on). `automation.chests.gap` (6) and `max` (64) set how far “All nearby” reaches and how many chests at once: a bigger gap reaches more chests and takes longer |
| `quota.*`, `models` | Spending limits as weights by tier, opened day by day (`quota.daily`), and a model by tier, see “Limits per player” |
| `askall.cooldown_seconds` | How long a player waits between two questions everyone sees (30) |
| `time.zone` | The players' default time zone; a player can tell the assistant theirs |
| `ui.*` | The action bar, tool lines, particles, sounds, and the author of the assistant's books |
| `blocked_commands` | Commands `/askx` never runs |

A `config.yml` from 2.3 or 2.4 still loads; a 2.3 config's texts are skipped, since they now live in `lang/`.

## How it works

The plugin opens a stream to the Allay backend and shows the live status. When the assistant needs something from the game, the backend sends a tool call; the plugin runs it on the server thread and posts the result back. The protocol is in [docs/protocol-v2.md](https://github.com/allay-ai/server/blob/master/docs/protocol-v2.md) of Allay Server.

## Build

You need JDK 25; Gradle downloads it when it's missing.

```shell
git clone https://github.com/allay-ai/minecraft
cd minecraft
./gradlew build
```

The jar lands in `spigot/build/libs/`, with every dependency inside.

The plugin is two modules. `core` holds everything that doesn't touch a game platform: the backend client, languages, settings, the tool registry, items as the assistant reads them, formulas, notes, goals, and the rest. `spigot` is the adapter for Spigot, Paper, and their forks: commands, events, windows, and the tools that read the world through Bukkit. Another platform would be another adapter next to it. Releases come from the “Release” workflow on GitHub, which takes the version as input.

## Stack

- Kotlin and Gradle Kotlin DSL
- Spigot API 26.3, so the jar runs on Spigot and Paper
- kotlinx-coroutines and kotlinx-serialization
- OkHttp for the backend's Server-Sent Events
- MaxMind DB reader for the country database
- SnakeYAML, so `core` reads YAML without Bukkit

## AI involvement

AI wrote parts of the documentation and some of the code at first. Since 2.0 the plugin is written by an AI agent, Claude, from my specs: the protocol, the tools, the windows, the languages. AI also writes the commit messages from the changes.

## Related projects

[Allay Server](https://github.com/allay-ai/server)—🛠️⚡ the backend for Allay: an agent on Bun and Mastra that answers through OpenRouter, calls the plugin's tools, searches the web, and remembers each player.

## From the same author

[Mo’Joins](https://github.com/MairwunNx/mojoins)—🎉 greets players in their own language: join and quit lines, a welcome for newcomers, sounds, and particles.

[Mo’Afks](https://github.com/MairwunNx/moafks)—🛡️ a safe AFK mode: no damage, no pushing, mobs ignore you, and AFK players don't block the night.

[Mo’Bosses](https://github.com/mairwunnx/mobosses)—🏆 an RPG plugin that turns ordinary mobs into epic bosses, with a deep player progression system.

## License

MIT, see [LICENSE](https://github.com/allay-ai/minecraft/blob/master/LICENSE).

## Credits

[IP Geolocation by DB-IP](https://db-ip.com), licensed under CC BY 4.0. Profession and item names in the windows come from Minecraft's own translations.

> 🫡 Made by Pavel Erokhin (Павел Ерохин), aka mairwunnx.
