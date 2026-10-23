# Allay

<p align="center">
  <a href="https://github.com/allay-ai/minecraft/releases/latest"><img alt="Release" src="https://img.shields.io/github/v/release/allay-ai/minecraft?label=release"></a>
  <img alt="Spigot | Paper 26.3" src="https://img.shields.io/badge/Spigot%20%7C%20Paper-26.3-F7CF0D">
  <img alt="Fabric 26.3" src="https://img.shields.io/badge/Fabric-26.3-DBD0B4">
  <img alt="NeoForge 26.3" src="https://img.shields.io/badge/NeoForge-26.3-E68C37">
  <img alt="Java 25" src="https://img.shields.io/badge/Java-25-E76F00">
  <a href="https://github.com/allay-ai/minecraft/actions/workflows/build.yml"><img alt="Build" src="https://img.shields.io/github/actions/workflow/status/allay-ai/minecraft/build.yml?branch=master&label=build"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/github/license/allay-ai/minecraft"></a>
</p>

💬 **Allay** puts an AI helper into your server's chat, named after the friendly mob that brings players what they need. A player types `/ask`, or just writes in chat mode, and gets an answer grounded in their real game: their inventory, what they're looking at, the chests around them, this server's recipes and rules.

[Русская версия](https://github.com/allay-ai/minecraft/blob/master/README.RU.md)

[![Play on Moonlands: pure vanilla, hard mobs, AI, claims. minecraft.mairwunnx.com](https://raw.githubusercontent.com/allay-ai/minecraft/master/media/og-adv-image.png)](https://minecraft.mairwunnx.com)

Allay runs on Moonlands, my hard survival server with friends. Join at `minecraft.mairwunnx.com` and ask it something.

## In the game

<p align="center"><img src="https://raw.githubusercontent.com/allay-ai/minecraft/master/media/screenshots/en/status.gif" width="760" alt="Allay answering: the status above the hotbar, the tools it uses, then the answer with buttons"></p>

<p align="center">
  <img src="https://raw.githubusercontent.com/allay-ai/minecraft/master/media/screenshots/en/answer.webp" width="49%" alt="Allay’s answer with Recipe, Way, Copy, Remember, and Yes buttons in the text">
  <img src="https://raw.githubusercontent.com/allay-ai/minecraft/master/media/screenshots/en/say.webp" width="49%" alt="A polite message with a Send button, and the line it sent">
  <br><sub>Buttons sit right in the answer · Allay writes to the others for you, and nothing goes out until you press Send</sub>
</p>

<p align="center">
  <img src="https://raw.githubusercontent.com/allay-ai/minecraft/master/media/screenshots/en/memory.webp" width="49%" alt="The memory window">
  <img src="https://raw.githubusercontent.com/allay-ai/minecraft/master/media/screenshots/en/consent.webp" width="49%" alt="The data notice window">
  <br><sub>What Allay remembers, in the game’s own window · Every player reads what Allay sends and keeps before the first question</sub>
</p>

## What you get

- 💬 **Answers from the real game.** “Which sword is better?” or “where did I put my redstone?”: Allay looks into the inventory, the block under the crosshair, and the chests around instead of guessing.
- 🗨️ **Chat mode.** `/allay chat` sends whatever a player writes to Allay, and `/askall` asks in front of everyone.
- 🧭 **It shows and marks.** A particle trail to a death point, a village, or a friend on the locator bar; glowing marks on the chests that hold what you look for.
- 🤖 **Automation agents do the chores.** They sort chests, smelt evenly, brew potions in rounds, and craft at a real table while you keep playing.
- 🧹 **A tidy inventory.** “Sword first, food in the last row”, and **[Put back]** undoes it.
- 🪟 **Buttons and windows.** Buttons sit right in the answer: the way somewhere, a recipe, brewing steps, the cheapest anvil order, enchantments, villager trades, or a message Allay wrote for you to send. Memory and privacy open as the game's own windows.
- 🌍 **Every player reads their own language.** English, Russian, Spanish, Portuguese, and German, picked by country or chosen with `/allay language`.
- 🔒 **Fair and safe.** Allay sees only the asker's things, never gives items to regular players, and respects claims and vanish.
- 🧱 **A plugin and a mod.** The same Allay runs as a plugin on Spigot and Paper, and as a mod on Fabric and NeoForge: on a dedicated server, or in your own singleplayer world, where it helps just you.
- 🛡️ **Private by design.** Your server signs in to the backend, which keeps each server's players apart. `/allay privacy` tells a player what's kept and erases it on request, and full privacy hides their name from the backend's admin panel.

## Privacy and the law

Allay is **EU (GDPR) and US privacy law friendly**. Here is what it does with players' data on the hosted backend, [allay.chat](https://allay.chat/privacy/), under the GDPR, Spain's LOPDGDD, COPPA, and the CCPA/CPRA:

- 🇪🇺 **Stored in the EU**, on servers in Helsinki, Finland. Encrypted nightly backups sit with Cloudflare R2 for up to 12 months.
- 🔐 **Sealed with AES-256-GCM:** conversations, memories and places, deaths, settings and goals, and records of questions. Not sealed: the players directory (name, UUID, operator status), the numbers of each question, and the record of each agreement.
- ⏳ **Kept for:** a conversation 30 days; memories, places, deaths, and settings a year after the player's last question; records of questions 30 days; the numbers and the players directory 2 years.
- 🌐 **Every question goes through OpenRouter in the USA** to the AI provider that writes the answer.
- ✋ **Age and agreement first.** Before the first question a window asks a player from the EU or the US their age, which must reach their country's age of consent (13 to 16 in the EU, 14 in Spain, 13 in the US), and every player agrees to a short notice, again whenever it changes. Below that age, Allay answers nothing and sends nothing. With allay.chat no setting in `config.yml` switches this off.
- 📦 **Players control their data.** `/allay privacy` shows what's kept, gives a 24-hour download link, and erases it; `/allay privacy withdraw` takes the agreement back and erases what it covered.
- 👥 **Other players stay unnamed.** A player who didn't agree reaches the AI only as “Player 1”, “Player 2”.
- 🧹 **Your server keeps almost nothing:** a player's data stays in memory while they play, and Allay's folder holds only sealed lists of who agreed and who was too young.

## Get started

1. Put Allay in, on Java 25:
   - **Spigot or Paper 26.3:** [`allay-spigot.jar`](https://github.com/allay-ai/minecraft/releases/latest/download/allay-spigot.jar) into `plugins/`, then restart the server.
   - **Fabric 26.3:** [`allay-fabric.jar`](https://github.com/allay-ai/minecraft/releases/latest/download/allay-fabric.jar) and [Fabric API](https://modrinth.com/mod/fabric-api) into `mods/` of a server or of your own game. Kotlin is inside; nothing else is needed.
   - **NeoForge 26.3:** [`allay-neoforge.jar`](https://github.com/allay-ai/minecraft/releases/latest/download/allay-neoforge.jar) into `mods/` of a server or of your own game. Kotlin is inside; nothing else is needed.
2. As an operator, run `/allay link` for a subscription, or put your own OpenRouter key into `provider.byok` and run `/allay reload`. In singleplayer the world's owner can do it, cheats or not.
3. Type `/ask how do I craft a beacon?`. [Installation](https://github.com/allay-ai/minecraft/wiki/Installation) goes further: Docker, your own backend, and limits.

## Documentation

The [wiki](https://github.com/allay-ai/minecraft/wiki) holds the manual.

**For players and server owners:** [Installation](https://github.com/allay-ai/minecraft/wiki/Installation) · [Paying for the model](https://github.com/allay-ai/minecraft/wiki/Paying-for-the-model) · [First steps](https://github.com/allay-ai/minecraft/wiki/First-steps) · [Asking Allay](https://github.com/allay-ai/minecraft/wiki/Asking-Allay) · [What Allay can do](https://github.com/allay-ai/minecraft/wiki/What-Allay-can-do) · [Automation agents](https://github.com/allay-ai/minecraft/wiki/Automation-agents) · [Commands and permissions](https://github.com/allay-ai/minecraft/wiki/Commands-and-permissions) · [Configuration](https://github.com/allay-ai/minecraft/wiki/Configuration) · [Languages](https://github.com/allay-ai/minecraft/wiki/Languages) · [Integrations](https://github.com/allay-ai/minecraft/wiki/Integrations) · [Coming from MCBuddy](https://github.com/allay-ai/minecraft/wiki/Coming-from-MCBuddy) · [FAQ](https://github.com/allay-ai/minecraft/wiki/FAQ)

**For developers:** [Building from source](https://github.com/allay-ai/minecraft/wiki/Building-from-source) · [Architecture](https://github.com/allay-ai/minecraft/wiki/Architecture) · [Releasing](https://github.com/allay-ai/minecraft/wiki/Releasing) · [Development](https://github.com/allay-ai/minecraft/wiki/Development)

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

> 🫡 Made by Pavel Erokhin (Павел Ерохин), aka mairwunnx.
