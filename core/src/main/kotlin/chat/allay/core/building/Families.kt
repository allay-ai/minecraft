package chat.allay.core.building

/**
 * Swapping one material for another across a build: cobblestone for stone bricks takes the cobblestone stairs, slabs,
 * and walls along, each keeping where it faces, its half, and whether it holds water. A shape the new material lacks
 * (there's no polished granite fence) is named, never guessed. Pure rules over names; whether a block exists is the
 * platform's to say through `known`.
 */
object Families {
  /** Shapes a material comes in, as the game names them after its prefix. */
  private val SHAPES = listOf("_stairs", "_slab", "_wall", "_fence_gate", "_fence", "_door", "_trapdoor", "_button", "_pressure_plate", "_hanging_sign", "_wall_hanging_sign", "_wall_sign", "_sign")

  /** A full block's name for its shapes, where the game drops a plural or says planks: `stone_bricks` → `stone_brick`. */
  fun prefix(full: String): String {
    val name = full.removePrefix("minecraft:")
    return when {
      name.endsWith("_planks") -> name.removeSuffix("_planks")
      name.endsWith("bricks") -> name.removeSuffix("s")
      name.endsWith("tiles") -> name.removeSuffix("s")
      name == "quartz_block" -> "quartz"
      name == "purpur_block" -> "purpur"
      name == "bamboo_block" || name == "bamboo_mosaic" -> name
      else -> name
    }
  }

  /** A change to one palette entry: from what to what, or why it can't. */
  sealed interface Swap {
    data class To(val state: String) : Swap
    data class Missing(val block: String) : Swap
  }

  /**
   * What [state] becomes when [from] (a full block, like `cobblestone`) is swapped for [to]; null when [state] isn't of
   * [from]'s family. [known] says whether a block id exists here.
   */
  fun swap(state: String, from: String, to: String, known: (String) -> Boolean): Swap? {
    val block = Blueprint.block(state).removePrefix("minecraft:")
    val source = from.removePrefix("minecraft:")
    val target = to.removePrefix("minecraft:")
    val properties = state.substringAfter('[', "").let { if (it.isEmpty()) "" else "[$it" }
    if (block == source) return Swap.To("minecraft:$target$properties")
    val (sourcePrefix, targetPrefix) = prefix(source) to prefix(target)
    val shape = SHAPES.firstOrNull { block == sourcePrefix + it } ?: return null
    val result = targetPrefix + shape
    return if (known(result)) Swap.To("minecraft:$result$properties") else Swap.Missing(result)
  }

  /** Whether [palette] holds [from] (a full block, like `cobblestone`) or one of its shapes. */
  fun has(palette: List<String>, from: String): Boolean = palette.any { swap(it, from, from) { true } != null }

  /**
   * [palette] with [from] swapped for [to]: the new palette and every shape [to] lacks. A state keeps its properties; the
   * platform drops the ones the new block doesn't have when it reads it.
   */
  fun apply(palette: List<String>, from: String, to: String, known: (String) -> Boolean): Pair<List<String>, Set<String>> {
    val missing = sortedSetOf<String>()
    val swapped = palette.map { state ->
      when (val swap = swap(state, from, to, known)) {
        is Swap.To -> swap.state
        is Swap.Missing -> state.also { missing += swap.block }
        null -> state
      }
    }
    return swapped to missing
  }
}
