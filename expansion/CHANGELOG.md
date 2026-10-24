# Allay Expansion API

`chat.allay:allay-expansion` on Maven Central. Within a major version the API only grows: an expansion built against an older version runs on every newer Allay of that major. The wiki's “Expansions” says how to use it.

## 1.3.0

Names in the language Allay answers in, with Allay 6.17.0.

- `Asker.language()`: the language Allay talks to the asker in, as a game language like `ru_ru`. It may differ from `locale()`, their game's: a player whose game is in English may read Allay in Russian. Name your items in it, so the model doesn't translate their names itself. It falls back to `locale()` by default.

## 1.2.0

Tools that suit only some players, with Allay 6.15.0.

- `AllayTool.offeredTo(asker)`: whether to offer the tool to this asker in this question, beyond its access and permission, like only in your worlds or while they hold your item. Allay asks on the server thread when a question starts and again before each call; an exception counts as no. True by default.

## 1.1.0

Tools that wait on the network or a database no longer hold the server, with Allay 6.14.0.

- `ToolResult.later(stage)`: a tool reads the game on the server thread, returns at once, and completes the stage from its own thread; Allay waits off the server thread for as long as for any tool (`http.tool_timeout`), then cancels it. A `ToolException` completing the stage refuses, as a thrown one does. `pending()` gives the stage.
- `Asker` takes its name, locale, operator status, dimension, and coordinates when the call starts, so they're safe on any thread; `hasPermission`, `unwrap`, `ToolCall.nameOf`, and `canSee` stay on the server thread.
- `Schema`: `string` with a `maxLength`, `number` with bounds, `object` with arguments of its own, and `objects`, a list of them; `Args.objects` reads that list back.

## 1.0.0

The first version, with Allay 6.12.0.

- `AllayExpansion`: an id, the API version it needs, a name and a version, tools, items, and a few lines for the model's prompt (`promptExpansion`).
- `AllayTool`: a name, a description, a JSON Schema of arguments (`Schema`), what it may do (`READ`, `OWN`, `OPERATOR`), a permission, whether it runs in public questions, and the call itself, with the asker (`Asker`, `unwrap` to the platform's player), the arguments (`Args`), and the result (`ToolResult`) or a refusal (`ToolException`).
- `ItemInfo`, `Recipe`, `Ingredient`: items with names in the game's languages, a description, and crafting, cooking, stonecutting, and brewing recipes the server's registries don't show.
- `AllayExpansions`: register and unregister in code, or name the class in `META-INF/services/chat.allay.expansion.AllayExpansion`; on Paper, Spigot, Fabric, and NeoForge alike.
