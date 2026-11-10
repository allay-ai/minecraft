package chat.allay.core.building

/**
 * Blocks the game renamed since 1.13, for builds saved by an older game: the old name, when this game doesn't know it,
 * becomes the new one, its states kept where they still fit. A name neither knows is left for the estimate to list.
 */
object Renames {
  private val NAMES = mapOf(
    "grass" to "short_grass",
    "grass_path" to "dirt_path",
    "sign" to "oak_sign",
    "wall_sign" to "oak_wall_sign",
    "chain" to "iron_chain",
  )

  /** [state] as this game names it, when [known] doesn't know its block; null when nothing older fits either. */
  fun fix(state: String, known: (String) -> Boolean): String? {
    val block = Blueprint.block(state).removePrefix("minecraft:")
    if (known(block)) return state
    val renamed = NAMES[block] ?: return null
    if (!known(renamed)) return null
    return "minecraft:$renamed" + state.substringAfter('[', "").let { if (it.isEmpty()) "" else "[$it" }
  }
}
