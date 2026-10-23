package chat.allay.modcore

import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.permissions.Permissions
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player

/**
 * Vanished players, as a vanish mod marks them: Vanish (`vanish`, by DrexHD) on Fabric, Vanishmod (`vmod`, by
 * RedstoneDubstep) on NeoForge; without one nobody is vanished. Allay never names, counts, or shows a vanished player
 * to anyone but that player and operators. Runs on the server thread.
 */
object Vanish {
  private val api by lazy {
    val loader = Mod.loader
    runCatching {
      when {
        loader.loaded("melius-vanish") || loader.loaded("vanish") -> Class.forName("me.drex.vanish.api.VanishAPI").getMethod("isVanished", Entity::class.java)
        loader.loaded("vmod") -> Class.forName("redstonedubstep.mods.vanishmod.VanishUtil").getMethod("isVanished", Player::class.java)
        else -> null
      }
    }.getOrNull()
  }

  fun vanished(target: Entity): Boolean = target is Player && api?.let { runCatching { it.invoke(null, target) as Boolean }.getOrDefault(false) } ?: false

  /** Whether [viewer] may learn about [other] through Allay: themself, an operator, or anyone while [other] isn't vanished. */
  fun visible(viewer: ServerPlayer?, other: Entity): Boolean =
    other !is ServerPlayer || other.uuid == viewer?.uuid || viewer?.permissions()?.hasPermission(Permissions.COMMANDS_GAMEMASTER) == true || !vanished(other)

  fun visible(viewer: CommandSourceStack, other: Entity): Boolean =
    viewer.player?.let { visible(it, other) } ?: (other !is ServerPlayer || viewer.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER) || !vanished(other))

  fun <T : Entity> visibleTo(viewer: ServerPlayer?, others: Iterable<T>): List<T> = others.filter { visible(viewer, it) }
}
