package chat.allay.modcore.tools

import chat.allay.core.tools.Dimensions

import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level

/**
 * Worlds and ids as the model reads them in the mods. A world is named by its dimension, `overworld` or `the_nether`
 * (Bukkit names them by their folders); a mod's dimension keeps its namespace.
 */
object Worlds {
  /** An id without the game's namespace, as Bukkit's `key.key` gives it; a mod's keeps its own. */
  fun key(id: Identifier): String = if (id.namespace == Identifier.DEFAULT_NAMESPACE) id.path else id.toString()

  fun name(level: Level): String = name(level.dimension())

  fun name(dimension: ResourceKey<Level>): String = key(dimension.identifier())
}

/** A world's dimension as the game's id: `overworld`, `the_nether`, `the_end`. */
fun Dimensions.id(level: Level): String = id(level.dimension())

fun Dimensions.id(dimension: ResourceKey<Level>): String = when (dimension) {
  Level.OVERWORLD -> "overworld"
  Level.NETHER -> "the_nether"
  Level.END -> "the_end"
  else -> Worlds.key(dimension.identifier())
}
