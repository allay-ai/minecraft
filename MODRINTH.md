# Allay AI

💬 **Allay** puts an AI helper into your server's chat, named after the friendly mob that brings players what they need. A player types `/ask`, or just writes in chat mode, and gets an answer grounded in their real game: their inventory, what they're looking at, the chests around them, your server's recipes and rules.

## What you get

- 💬 **Answers from the real game.** “Which sword is better?” or “where did I put my redstone?”: Allay looks into the inventory, the block under the crosshair, and the chests around instead of guessing.
- 🗨️ **Chat mode.** `/allay chat` sends whatever a player writes to Allay, and `/askall` asks in front of everyone.
- 🧭 **It shows and marks.** A particle trail to a death point, a village, or a friend on the locator bar; glowing marks on the chests that hold what you look for.
- 🤖 **Automation agents do the chores.** They sort chests, smelt evenly, brew potions in rounds, and craft at a real table while you keep playing.
- 🧹 **A tidy inventory.** “Sword first, food in the last row”, and **[Put back]** undoes it.
- 🪟 **Buttons and windows.** Recipes, brewing steps, the cheapest anvil order, enchantments, and villager trades, one click under the answer.
- 🌍 **Every player reads their own language.** English, Russian, Spanish, Portuguese, and German, picked by country or chosen with `/allay language`.
- 🔒 **Fair and safe.** Allay sees only the asker's things, never gives items to regular players, and respects GriefPrevention claims and vanish. PlaceholderAPI and Plan work too.
- 🛡️ **Private by design.** Your server signs in to the backend, which keeps each server's players apart. `/allay privacy` tells a player what's kept and erases it on request, and full privacy hides their name from the backend's admin panel.

## Privacy and the law

Allay is **EU (GDPR) and US privacy law friendly**. Here is what it does with players' data on the hosted backend, [allay.chat](https://allay.chat/privacy/), under the GDPR, Spain's LOPDGDD, COPPA, and the CCPA/CPRA:

- 🇪🇺 **Stored in the EU**, on servers in Helsinki, Finland. Encrypted nightly backups sit with Cloudflare R2 for up to 12 months.
- 🔐 **Sealed with AES-256-GCM:** conversations, memories and places, deaths, settings and goals, and records of questions. Not sealed: the players directory (name, UUID, operator status), the numbers of each question, and the record of each agreement.
- ⏳ **Kept for:** a conversation 30 days; memories, places, deaths, and settings a year after the player's last question; records of questions 30 days; the numbers and the players directory 2 years.
- 🌐 **Every question goes through OpenRouter in the USA** to the AI provider that writes the answer.
- ✋ **Age and agreement first.** Before the first question a player says their age, which must reach their country's age of consent (13 to 16 in the EU, 14 in Spain, 13 in the US), and agrees to a short notice, again whenever it changes. Below that age, Allay answers nothing and sends nothing. With allay.chat no setting in `config.yml` switches this off.
- 📦 **Players control their data.** `/allay privacy` shows what's kept, gives a 24-hour download link, and erases it; `/allay privacy withdraw` takes the agreement back and erases what it covered.
- 👥 **Other players stay unnamed.** A player who didn't agree reaches the AI only as “Player 1”, “Player 2”.
- 🧹 **Your server keeps almost nothing:** a player's data stays in memory while they play, and the plugin's folder holds only sealed lists of who agreed and who was too young.

## Get started

1. Put the jar into `plugins/` of a Spigot or Paper 26.3 server on Java 25, and restart it.
2. As an operator, run `/allay link` for a subscription, or put your own OpenRouter key into `provider.byok` in `plugins/Allay/config.yml` and run `/allay reload`.
3. Type `/ask how do I craft a beacon?`.

Everything else, from your own backend to every config key, lives in the [wiki on GitHub](https://github.com/allay-ai/minecraft/wiki).

## Related projects

- **[Moonlands](https://minecraft.mairwunnx.com)**—the hard-survival server where all of these run.
- **[WorldAPI](https://github.com/MairwunNx/minecraft-worldapi)**—REST and WebSocket API to a Paper server's world, players, and stats.
- **[Minecraft MCP](https://github.com/MairwunNx/minecraft-mcp)**—lets an AI client read and run a server through WorldAPI.
- **[Allay Server](https://github.com/allay-ai/server)**—the backend behind Allay.
- **[Mo’Joins](https://github.com/MairwunNx/mojoins)**—join and quit messages in each player's language.
- **[Mo’Afks](https://github.com/MairwunNx/moafks)**—a safe AFK mode that doesn't block the night.
- **[Mo’Bosses](https://github.com/MairwunNx/mobosses)**—monsters that spawn as bosses with classes, abilities, and loot.
- **[minecraft-exporter](https://github.com/MairwunNx/minecraft-exporter)**—Prometheus metrics from WorldAPI.
- **[minecraft-grafana](https://github.com/MairwunNx/minecraft-grafana)**—the Grafana dashboard for WorldAPI.

[IP Geolocation by DB-IP](https://db-ip.com), licensed under CC BY 4.0.
