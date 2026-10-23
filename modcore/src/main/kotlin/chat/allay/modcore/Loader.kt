package chat.allay.modcore

import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.crafting.Ingredient
import java.nio.file.Path

/**
 * What differs between the mod loaders, behind one face. Fabric and NeoForge each hand theirs to [Mod.start] when the
 * game starts a server; nothing else in the mod touches a loader's API. Everything here may run on the server thread.
 */
interface Loader {
  /** A mod as a player would name it in the server's list. */
  data class ModInfo(val id: String, val name: String, val version: String, val description: String?)

  /** What the backend hears as `loader`: `fabric` or `neoforge`. */
  val id: String

  /** What the server runs, where Bukkit says CraftBukkit or Paper: `Fabric` or `NeoForge`. */
  val software: String

  /** Allay's version, as the loader read it from the mod's descriptor. */
  val version: String

  /** The game's `config/` folder. */
  val config: Path

  /** Whether the mod with this id is loaded. */
  fun loaded(mod: String): Boolean

  /** The mods a player installed: not the game, Java, the loader, or the libraries mods carry inside them. */
  fun mods(): List<ModInfo>

  /** Whether the ingredient is the loader's own kind (components, custom matching), which automation never crafts with. */
  fun custom(ingredient: Ingredient): Boolean

  /** A permissions mod's answer for a command's sender, or [fallback] without one. */
  fun permission(source: CommandSourceStack, permission: String, fallback: Boolean): Boolean

  /** A permissions mod's answer for a player, or [fallback] without one. */
  fun permission(player: ServerPlayer, permission: String, fallback: Boolean): Boolean

  /** What only this loader integrates, started with Allay on a server and closed when it stops. */
  fun integrations(game: ModGame): List<AutoCloseable> = emptyList()
}
