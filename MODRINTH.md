# Allay AI

💬 **Allay** puts an AI helper into your server's chat, named after the friendly mob that brings players what they need. A player types `/ask`, or just writes in chat mode, and gets an answer grounded in their real game: their inventory, what they're looking at, the chests around them, your server's recipes and rules. Each player reads the plugin in their own language.

## What you get

- 💬 **`/ask` about anything in the game.** “What should I do next?”, “which sword is better?”, “where did I put my redstone?”. The assistant looks things up in the game while it answers instead of guessing.
- 🗨️ **Chat mode:** with `/allay chat`, everything a player writes goes to the assistant. `!` runs commands, `?` asks in front of everyone, `~` writes to the ordinary chat.
- 📣 **Questions everyone sees** with `/askall`, in one thread per server, so friends can follow up on each other's questions; everyone gets the buttons under the answer.
- 📖 **It explains itself** from the commands and settings each player really has, and knows your server's plugins and their versions.
- 👀 **It sees what the player sees:** the block under the crosshair with its drops, a chest's contents, an enchanting table's bookshelves, a mob and the hits it takes, a villager's trades.
- 🎒 **It knows the player's things:** inventory, armor, the ender chest, bundles and shulker boxes, and any item in full, down to a weapon's damage and speed as the tooltip shows them.
- 🔍 **It finds things** in the inventory, the ender chest, and the chests nearby, inside shulker boxes too, and lights up the chests it found.
- 🧹 **A tidy inventory** with pinned slots: “sword first, water second”, the best item of a kind in each, the hotbar only when asked.
- 🧭 **It shows the way** with the compass and a particle trail: to a death point, a village, a biome, or a place from the player's notebook. The trail lasts as long as the walk needs, in a dozen looks, and goes out on arrival. The way to another player follows them as they run.
- ✨ **It marks blocks** that only the asker sees, glowing through walls: the chests that hold what they look for, labeled with what's inside, and ghost torches on dark spots. Ores, spawners, and vaults never glow, and containers only when the player could open them.
- 🏗️ **Drafts of any build** up to 20×15×20 as glowing ghosts, layer by layer, checked against what the player places, with a bar counting; bigger builds come in parts.
- 🤖 **Automation agents** take the chores at the base in the background: sort or group chests by category (the player picks them with left clicks; signs and item frames say what goes where), smelt evenly with exactly the fuel needed, brew potions step by step, and craft at a real crafting table. They talk under their own name with buttons, wait for furnaces for free, work only near the player and with blocks in plain sight, and stop when someone takes what they count on. **[Put back]** undoes chest sorting for 5 minutes.
- 💸 **Spending limits** per player tier (`allay.quota.<tier>`) and a model per tier (`allay.model.<tier>`), for any way of paying.
- 🔌 **A redstone debugger** that reads the machine under the crosshair and finds the faults the game's rules make certain, only where the player may build.
- 📒 **A notebook, goals with a boss bar, and reminders.**
- 🧠 **It remembers what it looked up**, so a follow-up like “and how many were in that chest?” needs no second look (Allay Server 5.5).
- 🪟 **Buttons under answers** and **recipe, brewing, anvil, enchanting, and villager windows** that only show pictures, so nothing can be taken from them. Potions come from the game's own brewing recipes; the anvil plan uses the books and items the player really has.
- 🌍 **Every player reads their own language.** English, Russian, Spanish, Portuguese, and German come bundled, picked by the player's country, then by their game's language.
- 📊 **Plan**, if you have it: chat mode, goals, notes, and language on each player's page, and who pays for the model in the server's overview.
- 🧩 **PlaceholderAPI**, if you have it: `{papi:luckperms_prefix}` in a text, and `%allay_chat_mode%` and friends for TAB and scoreboards.
- 🪽 **It sounds like an allay:** a curious hum while it works, a happy chortle with the answer.
- ✻ **A live status like Claude Code** in the action bar, and a chat line for each tool the assistant uses.
- 🔒 **Private and safe.** Tools know only the player who asked. Other players show up only as the asker's locator bar shows them, by the game's rules: crouching, a pumpkin, or invisibility hides them from Allay too. Tools that give items or change the world go to operators only. Every tool can be switched off. Vanished players (SuperVanish, PremiumVanish, Essentials) stay hidden from everyone but themselves and operators, and their public questions stay private.

## Install

1. Put the jar in your server's `plugins/` folder and restart the server.
2. Edit `plugins/Allay/config.yml` and the texts in `plugins/Allay/lang/` if you like.
3. Run `/allay reload`, then try `/ask how do I craft a beacon?`.

Coming from MCBuddy? Allay is its new name from 4.0.0 on. Swap the jar: Allay moves `plugins/mcbuddy-spigot/` into `plugins/Allay/` by itself, `/buddy` becomes `/allay`, and the permissions become `allay.*`.

Allay runs on Spigot, Paper 26.3, and their forks, on Java 25. It talks to the Allay backend, `https://allay.chat` by default (`server.url`).

## Paying for the model

Providers charge for the AI model behind Allay by the word. Your server pays one of two ways, picked by `provider.mode` in `config.yml`:

- **Your own key (`byok`).** Put a key from OpenRouter or any OpenAI-compatible API in `provider.byok.api_key`, with `base_url`, `model` (it must be able to call tools), and `search_model` (empty: no web search). You pay the provider directly; on `allay.chat` it costs nothing extra, up to 20 questions a minute and 2000 a day per server.
- **A subscription (`hosted`, the default).** Run `/allay link`: the plugin saves a license in `plugins/Allay/license.key` and shows a **[Pay in Telegram]** button, a link that works once and for 24 hours. Pay in Telegram Stars: a week for 150 ⭐, a month for 500 ⭐ (renews by itself), or a year for 10 000 ⭐ (the limit refills every 30 days). `/allay status` shows the plan, the date it's paid until, and how much of this period's limit is used.

Operators online hear when a payment comes in, when the limit passes 80% and 100%, and three days before a subscription that doesn't renew runs out. When it lapses, players get a short, friendly note, and operators get a link to pay.

**Keep `license.key` to yourself:** whoever has it uses your subscription. It never goes into the log or `config.yml`.

Running your own [Allay Server](https://github.com/allay-ai/server) with `SELFHOSTED=1`? It has no billing: point `server.url` at it, and there's nothing to pay.

## How a player gets their language

1. **By IP.** The plugin looks up the player's country and checks `locales.countries`: Russia gets Russian, Brazil gets Portuguese, Germany gets German, and so on. You can change the lists.
2. **By game language.** When the country isn't listed, the plugin takes the language set in the player's game, if a file in `lang/` matches it.
3. **Default.** Everyone else gets `locales.default`, English out of the box.

The country lookup needs no account and sends no address anywhere. On the first start, the plugin downloads a free country database (4 MB) and refreshes it every month. Players who connect through a proxy show up with the proxy's address, so they get the proxy's country.

**Add a language:** copy `lang/en.yml` to, say, `lang/it.yml`, translate it, add `it: [ IT, SM, VA ]` to `locales.countries`, and run `/allay reload`.

## Commands and permissions

| Command | What it does |
|---|---|
| `/ask <question>` | Asks the assistant privately |
| `/askall <question>` | Asks in front of everyone online |
| `/askx <what to do>` | Turns a request into commands, minus `blocked_commands` |
| `/allay` | Opens the settings window |
| `/allay chat [on\|off]`, `/allay hints [on\|off]` | Chat mode and the tip about it |
| `/allay cancel`, `/allay new` | Stops the current question; starts the conversation over |
| `/allay memory`, `/allay forget <number\|all>` | Shows and erases what the assistant remembers about you |
| `/allay memory <text>` | Remembers the text by hand; with coordinates, a note in the notebook |
| `/allay recipe <item>`, `/allay enchants`, `/allay villagers` | Opens a window |
| `/allay brewing <potion>`, `/allay anvil` | Opens the brewing steps or the anvil plan for what you have |
| `/allay reload` | Reloads the settings and the languages |
| `/allay status`, `/allay link` | Who pays for the model and how the subscription stands; a link to pay in Telegram |
| `/allay locale [player]` | Which language a player reads Allay in, and why |

`allay.ask`, `allay.askall`, `allay.chat`, and `allay.automation` with `.chests`, `.furnaces`, `.brewing`, and `.crafting` are everyone's by default; `allay.askx`, `allay.askall.nocooldown`, `allay.reload`, and `allay.admin` (status, link, locale, and the subscription notes) belong to operators. Allay tells a player only about containers they could open themselves: outside claims, or with container trust in GriefPrevention, and never an unopened structure chest.

## Plan

With [Plan](https://modrinth.com/plugin/plan) installed, each player's page shows their chat mode, language, goals with progress, notes, and waiting reminders. The server's “Plugins Overview” shows who pays for the model, how many players use chat mode, and, with a subscription, its plan, paid-until date, and the share of the limit used. Never a key, a license, or a price.

## PlaceholderAPI

With [PlaceholderAPI](https://modrinth.com/plugin/placeholderapi) installed, Allay adds `%allay_chat_mode%` and `%allay_thinking%` (true or false: chat mode is on, Allay is answering right now), `%allay_goals%` (how many goals the player has), and `%allay_language%` (the language Allay talks to them in).

In any text in `lang/`, `{papi:name}` shows `%name%` for the player the message is about: the reader, or the asker in a question everyone sees. Allay fills only the tags the text itself holds, so a `%` in a player's question stays a percent sign.

## Links

- Source code, issues, and the full config: [GitHub](https://github.com/allay-ai/minecraft)
- From the same author: [Mo’Joins](https://modrinth.com/plugin/mojoins), join and quit lines in each player's language, and [Mo’Afks](https://modrinth.com/plugin/moafks), a safe AFK mode

[IP Geolocation by DB-IP](https://db-ip.com), licensed under CC BY 4.0.
