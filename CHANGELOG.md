# Changelog

The “Release” workflow takes the notes for a version from its section here, unless you type them into its `changelog` field.

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
