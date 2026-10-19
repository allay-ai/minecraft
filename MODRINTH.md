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
