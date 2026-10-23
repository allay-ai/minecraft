package chat.allay.fabric

import chat.allay.core.Permissions

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.permissions.Permissions as Game

/**
 * Allay's permissions on Fabric: through fabric-permissions-api when a permissions mod brings it (LuckPerms does),
 * else as `plugin.yml` would give them by default, operators (level 2) getting everything.
 */
object Perms {
  private val api by lazy { FabricLoader.getInstance().isModLoaded("fabric-permissions-api-v0") }

  fun check(source: CommandSourceStack, permission: String): Boolean {
    val fallback = Permissions.byDefault(permission, source.permissions().hasPermission(Game.COMMANDS_GAMEMASTER))
    return if (api) Api.check(source, permission, fallback) else fallback
  }

  fun check(player: ServerPlayer, permission: String): Boolean {
    val fallback = Permissions.byDefault(permission, player.permissions().hasPermission(Game.COMMANDS_GAMEMASTER))
    return if (api) Api.check(player, permission, fallback) else fallback
  }

  // Only touched when the API is loaded; its classes aren't there otherwise.
  private object Api {
    fun check(source: CommandSourceStack, permission: String, fallback: Boolean) = me.lucko.fabric.api.permissions.v0.Permissions.check(source, permission, fallback)
    fun check(player: ServerPlayer, permission: String, fallback: Boolean) = me.lucko.fabric.api.permissions.v0.Permissions.check(player, permission, fallback)
  }
}
