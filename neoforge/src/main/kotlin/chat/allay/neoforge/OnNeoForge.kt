package chat.allay.neoforge

import chat.allay.core.Permissions
import chat.allay.core.YamlConfig
import chat.allay.modcore.Loader
import chat.allay.modcore.Folders

import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.crafting.Ingredient
import net.neoforged.fml.ModList
import net.neoforged.fml.loading.FMLPaths
import net.neoforged.neoforge.server.permission.PermissionAPI
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent
import net.neoforged.neoforge.server.permission.nodes.PermissionNode
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes
import org.slf4j.LoggerFactory
import java.nio.file.Path
import net.minecraft.server.permissions.Permissions as Game

/** NeoForge as the shared mod's loader: FML's mod list, NeoForge's ingredients, and its PermissionAPI. */
object OnNeoForge : Loader {
  override val id = "neoforge"
  override val software = "NeoForge"

  // FML wants a number, so a dev build carries 0.0.0-dev; Allay calls it dev on every platform.
  override val version: String
    get() = ModList.get().getModContainerById("allay").map { it.modInfo.version.toString() }.orElse("dev").removePrefix("0.0.0-")

  override val config: Path get() = FMLPaths.CONFIGDIR.get()
  override fun loaded(mod: String) = ModList.get().isLoaded(mod)

  /** The game and the loader itself: the list shows mods a player installed. */
  private val BUILT_IN = setOf("minecraft", "neoforge")

  override fun mods(): List<Loader.ModInfo> = ModList.get().mods
    .filter { it.modId !in BUILT_IN }
    .map { Loader.ModInfo(it.modId, it.displayName, it.version.toString(), it.description.trim().takeIf(String::isNotEmpty)) }

  override fun custom(ingredient: Ingredient) = ingredient.isCustom

  /**
   * NeoForge's PermissionAPI answers only for nodes named before the server starts: every permission Allay knows, and
   * the tiers `config.yml` names then. A tier added later counts once the server starts again; until then it has its
   * default, as without a permissions mod.
   */
  @Volatile private var nodes: Map<String, PermissionNode<Boolean>> = emptyMap()

  fun nodes(event: PermissionGatherEvent.Nodes) {
    val file = Folders.shared.resolve("config.yml")
    val config = runCatching { if (file.exists()) YamlConfig.read(file) else null }.getOrNull()
    nodes = Permissions.all(config).associateWith { name ->
      PermissionNode("allay", name.removePrefix("allay."), PermissionTypes.BOOLEAN, { player, _, _ ->
        Permissions.byDefault(name, player?.permissions()?.hasPermission(Game.COMMANDS_GAMEMASTER) == true)
      })
    }
    event.addNodes(nodes.values)
  }

  // A command block or the console has no player to ask PermissionAPI about.
  override fun permission(source: CommandSourceStack, permission: String, fallback: Boolean) =
    source.player?.let { permission(it, permission, fallback) } ?: fallback

  override fun permission(player: ServerPlayer, permission: String, fallback: Boolean): Boolean {
    val node = nodes[permission] ?: return fallback
    return try {
      PermissionAPI.getPermission(player, node)
    } catch (error: Exception) {
      LoggerFactory.getLogger("Allay").warn("Couldn't ask NeoForge's PermissionAPI about $permission", error)
      fallback
    }
  }
}
