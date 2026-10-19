# Allay

<p align="center">
  <a href="https://github.com/allay-ai/minecraft/releases/latest"><img alt="Release" src="https://img.shields.io/github/v/release/allay-ai/minecraft?label=release"></a>
  <img alt="Spigot | Paper 26.3" src="https://img.shields.io/badge/Spigot%20%7C%20Paper-26.3-F7CF0D">
  <img alt="Java 25" src="https://img.shields.io/badge/Java-25-E76F00">
  <a href="https://github.com/allay-ai/minecraft/actions/workflows/build.yml"><img alt="Build" src="https://img.shields.io/github/actions/workflow/status/allay-ai/minecraft/build.yml?branch=master&label=build"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/github/license/allay-ai/minecraft"></a>
</p>

💬 **Allay** приводит в чат сервера ИИ-помощника, названного в честь дружелюбного моба, который приносит игрокам нужное. Игрок пишет `/ask` или просто пишет в чат в режиме чата и получает ответ, привязанный к его настоящей игре: к инвентарю, к тому, на что он смотрит, к сундукам вокруг, к рецептам и правилам этого сервера.

[English version](https://github.com/allay-ai/minecraft/blob/master/README.md)

[![Играйте на Moonlands: чистая ваниль, сложные мобы, AI, приваты. ru.minecraft.mairwunnx.com](https://raw.githubusercontent.com/allay-ai/minecraft/master/media/og-adv-image.ru.png)](https://ru.minecraft.mairwunnx.com)

Allay работает на Moonlands — моём хардкорном выживании с друзьями. Заходите на `ru.minecraft.mairwunnx.com` и спросите его о чём-нибудь.

## Что вы получаете

- 💬 **Ответы из настоящей игры.** «Какой меч лучше?» или «куда я дел редстоун?» — Allay заглядывает в инвентарь, в блок под прицелом и в сундуки вокруг, а не гадает.
- 🗨️ **Режим чата.** `/allay chat` отправляет Allay всё, что пишет игрок, а `/askall` спрашивает при всех.
- 🧭 **Он показывает и подсвечивает.** След из частиц к месту смерти, деревне или другу на локаторе; светящиеся метки на сундуках, где лежит нужное.
- 🤖 **Агенты автоматизации берут рутину.** Они сортируют сундуки, плавят поровну, варят зелья в несколько заходов и крафтят на настоящем верстаке, пока вы играете.
- 🧹 **Порядок в инвентаре.** «Меч первым, еда в последний ряд», а **[Вернуть как было]** отменяет сортировку.
- 🪟 **Кнопки и окна.** Рецепты, шаги варки, самый дешёвый порядок на наковальне, чары и сделки жителей — в один клик под ответом.
- 🌍 **Каждый игрок читает свой язык.** Английский, русский, испанский, португальский и немецкий: по стране или на выбор через `/allay language`.
- 🔒 **Честно и безопасно.** Allay видит только вещи спросившего, не выдаёт предметы обычным игрокам и уважает приваты и ваниш.

## С чего начать

1. Положите [`allay-spigot.jar`](https://github.com/allay-ai/minecraft/releases/latest/download/allay-spigot.jar) в `plugins/` сервера на Spigot или Paper 26.3 с Java 25 и перезапустите его.
2. Оператором выполните `/allay link` для подписки или впишите свой ключ OpenRouter в `provider.byok` и выполните `/allay reload`.
3. Напишите `/ask как скрафтить маяк?`. Дальше ведёт страница [Installation](https://github.com/allay-ai/minecraft/wiki/Installation): Docker, свой бэкенд и лимиты.

## Документация

Руководство живёт в [вики](https://github.com/allay-ai/minecraft/wiki), на английском.

**Игрокам и владельцам серверов:** [Installation](https://github.com/allay-ai/minecraft/wiki/Installation) · [Paying for the model](https://github.com/allay-ai/minecraft/wiki/Paying-for-the-model) · [First steps](https://github.com/allay-ai/minecraft/wiki/First-steps) · [Asking Allay](https://github.com/allay-ai/minecraft/wiki/Asking-Allay) · [What Allay can do](https://github.com/allay-ai/minecraft/wiki/What-Allay-can-do) · [Automation agents](https://github.com/allay-ai/minecraft/wiki/Automation-agents) · [Commands and permissions](https://github.com/allay-ai/minecraft/wiki/Commands-and-permissions) · [Configuration](https://github.com/allay-ai/minecraft/wiki/Configuration) · [Languages](https://github.com/allay-ai/minecraft/wiki/Languages) · [Integrations](https://github.com/allay-ai/minecraft/wiki/Integrations) · [Coming from MCBuddy](https://github.com/allay-ai/minecraft/wiki/Coming-from-MCBuddy) · [FAQ](https://github.com/allay-ai/minecraft/wiki/FAQ)

**Разработчикам:** [Building from source](https://github.com/allay-ai/minecraft/wiki/Building-from-source) · [Architecture](https://github.com/allay-ai/minecraft/wiki/Architecture) · [Releasing](https://github.com/allay-ai/minecraft/wiki/Releasing) · [Development](https://github.com/allay-ai/minecraft/wiki/Development)

## Связанные проекты

- **[Moonlands](https://minecraft.mairwunnx.com)** — хардкорный сервер выживания, где всё это работает.
- **[WorldAPI](https://github.com/MairwunNx/minecraft-worldapi)** — REST и WebSocket API к миру, игрокам и статистике сервера на Paper.
- **[Minecraft MCP](https://github.com/MairwunNx/minecraft-mcp)** — даёт AI-клиенту читать сервер и управлять им через WorldAPI.
- **[Allay Server](https://github.com/allay-ai/server)** — бэкенд Allay.
- **[Mo’Joins](https://github.com/MairwunNx/mojoins)** — сообщения о входе и выходе на языке каждого игрока.
- **[Mo’Afks](https://github.com/MairwunNx/moafks)** — безопасный AFK-режим, который не мешает пропустить ночь.
- **[Mo’Bosses](https://github.com/MairwunNx/mobosses)** — мобы, которые появляются боссами с классами, способностями и добычей.
- **[minecraft-exporter](https://github.com/MairwunNx/minecraft-exporter)** — метрики Prometheus из WorldAPI.
- **[minecraft-grafana](https://github.com/MairwunNx/minecraft-grafana)** — дашборд Grafana для WorldAPI.

[IP Geolocation by DB-IP](https://db-ip.com), лицензия CC BY 4.0.

> 🫡 Сделал Павел Ерохин (Pavel Erokhin), он же mairwunnx.
