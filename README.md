# Allay

<p align="center">
  <a href="https://github.com/allay-ai/minecraft/releases/latest"><img alt="Release" src="https://img.shields.io/github/v/release/allay-ai/minecraft?label=release"></a>
  <img alt="Spigot | Paper 26.3" src="https://img.shields.io/badge/Spigot%20%7C%20Paper-26.3-F7CF0D">
  <img alt="Java 25" src="https://img.shields.io/badge/Java-25-E76F00">
  <a href="https://github.com/allay-ai/minecraft/actions/workflows/build.yml"><img alt="Build" src="https://img.shields.io/github/actions/workflow/status/allay-ai/minecraft/build.yml?branch=master&label=build"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/github/license/allay-ai/minecraft"></a>
</p>

💬 **Allay** puts an AI helper into your server's chat, named after the friendly mob that brings players what they need. A player types `/ask`, or just writes in chat mode, and gets an answer grounded in their real game: their inventory, what they're looking at, the chests around them, this server's recipes and rules.

[Русская версия](https://github.com/allay-ai/minecraft/blob/master/README.RU.md)

[![Play on Moonlands: pure vanilla, hard mobs, AI, claims. minecraft.mairwunnx.com](https://raw.githubusercontent.com/allay-ai/minecraft/master/media/og-adv-image.png)](https://minecraft.mairwunnx.com)

Allay runs on Moonlands, my hard survival server with friends. Join at `minecraft.mairwunnx.com` and ask it something.

## What you get

- 💬 **Answers from the real game.** “Which sword is better?” or “where did I put my redstone?”: Allay looks into the inventory, the block under the crosshair, and the chests around instead of guessing.
- 🗨️ **Chat mode.** `/allay chat` sends whatever a player writes to Allay, and `/askall` asks in front of everyone.
- 🧭 **It shows and marks.** A particle trail to a death point, a village, or a friend on the locator bar; glowing marks on the chests that hold what you look for.
- 🤖 **Automation agents do the chores.** They sort chests, smelt evenly, brew potions in rounds, and craft at a real table while you keep playing.
- 🧹 **A tidy inventory.** “Sword first, food in the last row”, and **[Put back]** undoes it.
- 🪟 **Buttons and windows.** Recipes, brewing steps, the cheapest anvil order, enchantments, and villager trades, one click under the answer.
- 🌍 **Every player reads their own language.** English, Russian, Spanish, Portuguese, and German, picked by country or chosen with `/allay language`.
- 🔒 **Fair and safe.** Allay sees only the asker's things, never gives items to regular players, and respects claims and vanish.
- 🛡️ **Private by design.** Your server signs in to the backend, which keeps each server's players apart. `/allay privacy` tells a player what's kept and erases it on request.

## Get started

1. Put [`allay-spigot.jar`](https://github.com/allay-ai/minecraft/releases/latest/download/allay-spigot.jar) into `plugins/` of a Spigot or Paper 26.3 server on Java 25, and restart it.
2. As an operator, run `/allay link` for a subscription, or put your own OpenRouter key into `provider.byok` and run `/allay reload`.
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
