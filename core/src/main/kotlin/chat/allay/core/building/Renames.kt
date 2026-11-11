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

  /** The game version since which `stone_slab` is a slab of stone: 18w43a, 1.14's first snapshot. */
  const val STONE_SLAB = 1901

  /**
   * Blocks whose name went to another block: a build saved before then means the old one, though this game knows the
   * name. 1.13's stone slab is today's smooth stone slab.
   */
  private val MOVED = listOf(Triple(STONE_SLAB, "stone_slab", "smooth_stone_slab"))

  /**
   * [state] as this game names it: a name that went to another block since [dataVersion] (the game that saved the
   * build) renamed, and a name it doesn't know renamed when one fits. Null when nothing older fits either.
   */
  fun fix(state: String, known: (String) -> Boolean, dataVersion: Int = Int.MAX_VALUE): String? {
    val block = Blueprint.block(state).removePrefix("minecraft:")
    MOVED.firstOrNull { (since, old, _) -> dataVersion in 1 until since && block == old }?.let { (_, _, new) ->
      if (known(new)) return "minecraft:$new" + state.substringAfter('[', "").let { if (it.isEmpty()) "" else "[$it" }
    }
    if (known(block)) return state
    val renamed = NAMES[block] ?: return null
    if (!known(renamed)) return null
    return "minecraft:$renamed" + state.substringAfter('[', "").let { if (it.isEmpty()) "" else "[$it" }
  }
}
