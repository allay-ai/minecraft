package chat.allay.fabric

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.permissions.Permissions
import net.minecraft.world.entity.Entity

/**
 * Vanished players, as the Vanish mod (`vanish`, by DrexHD) marks them; without it nobody is vanished. Allay never
 * names, counts, or shows a vanished player to anyone but that player and operators. Runs on the server thread.
 */
object Vanish {
  private val api by lazy {
    if (!FabricLoader.getInstance().isModLoaded("melius-vanish") && !FabricLoader.getInstance().isModLoaded("vanish")) null
    else runCatching { Class.forName("me.drex.vanish.api.VanishAPI").getMethod("isVanished", Entity::class.java) }.getOrNull()
  }

  fun vanished(target: Entity): Boolean = api?.let { runCatching { it.invoke(null, target) as Boolean }.getOrDefault(false) } ?: false

  /** Whether [viewer] may learn about [other] through Allay: themself, an operator, or anyone while [other] isn't vanished. */
  fun visible(viewer: ServerPlayer?, other: Entity): Boolean =
    other !is ServerPlayer || other.uuid == viewer?.uuid || viewer?.permissions()?.hasPermission(Permissions.COMMANDS_GAMEMASTER) == true || !vanished(other)

  fun visible(viewer: CommandSourceStack, other: Entity): Boolean =
    viewer.player?.let { visible(it, other) } ?: (other !is ServerPlayer || viewer.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER) || !vanished(other))

  fun <T : Entity> visibleTo(viewer: ServerPlayer?, others: Iterable<T>): List<T> = others.filter { visible(viewer, it) }
}
