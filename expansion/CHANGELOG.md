# Allay Expansion API

`chat.allay:allay-expansion` on Maven Central. Within a major version the API only grows: an expansion built against an older version runs on every newer Allay of that major. The wiki's “Expansions” says how to use it.

## 1.0.0

The first version, with Allay 6.12.0.

- `AllayExpansion`: an id, the API version it needs, a name and a version, tools, items, and a few lines for the model's prompt (`promptExpansion`).
- `AllayTool`: a name, a description, a JSON Schema of arguments (`Schema`), what it may do (`READ`, `OWN`, `OPERATOR`), a permission, whether it runs in public questions, and the call itself, with the asker (`Asker`, `unwrap` to the platform's player), the arguments (`Args`), and the result (`ToolResult`) or a refusal (`ToolException`).
- `ItemInfo`, `Recipe`, `Ingredient`: items with names in the game's languages, a description, and crafting, cooking, stonecutting, and brewing recipes the server's registries don't show.
- `AllayExpansions`: register and unregister in code, or name the class in `META-INF/services/chat.allay.expansion.AllayExpansion`; on Paper, Spigot, Fabric, and NeoForge alike.
