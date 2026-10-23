package chat.allay.modcore

import chat.allay.core.Permissions

import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.permissions.Permissions as Game

/**
 * Allay's permissions in the mods: through the loader's permissions API when a permissions mod answers (LuckPerms
 * does, on Fabric through fabric-permissions-api, on NeoForge through its PermissionAPI), else as `plugin.yml` would
 * give them by default, operators (level 2) getting everything. The owner of a singleplayer
 * world runs Allay there even without cheats: they may link a license and reload, but get no operator tools.
 */
object Perms {
  fun check(source: CommandSourceStack, permission: String): Boolean {
    val fallback = Permissions.byDefault(permission, source.permissions().hasPermission(Game.COMMANDS_GAMEMASTER))
    return Mod.loader.permission(source, permission, fallback)
  }

  fun check(player: ServerPlayer, permission: String): Boolean {
    if (permission in OWNERS && owner(player)) return true
    val fallback = Permissions.byDefault(permission, player.permissions().hasPermission(Game.COMMANDS_GAMEMASTER))
    return Mod.loader.permission(player, permission, fallback)
  }

  private val OWNERS = setOf(Permissions.ADMIN, Permissions.RELOAD)

  /** The player whose game runs this singleplayer world, opened to LAN or not. */
  fun owner(player: ServerPlayer): Boolean = player.level().server.let { !it.isDedicatedServer && it.isSingleplayerOwner(player.nameAndId()) }
}
