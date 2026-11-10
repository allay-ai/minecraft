package chat.allay.core.building

import kotlinx.serialization.Serializable

/**
 * What both platforms' builders share: the order blocks go up in, what a build job keeps across a restart, and which
 * hoe or shovel wears first. The placing itself is the platform's.
 */
object Construction {
  /** The stages, in order: what holds itself up, then what hangs on it (torches, doors, plants, rails), then water and lava. */
  const val SOLID = 0
  const val ATTACHED = 1
  const val LIQUID = 2

  /**
   * Every block of [blueprint] in the order a builder places it, as WorldEdit does: by [stage] of its palette entry,
   * then layer by layer from the bottom, rows from z 0, cells from x 0. Cells as [Shell.pack]s; air is left out.
   */
  fun order(blueprint: Blueprint, stage: IntArray): IntArray {
    val counts = IntArray(3)
    blueprint.forEach { _, _, _, index -> counts[stage[index]]++ }
    val starts = intArrayOf(0, counts[0], counts[0] + counts[1])
    val out = IntArray(counts.sum())
    blueprint.forEach { x, y, z, index -> out[starts[stage[index]]++] = Shell.pack(x, y, z) }
    return out
  }

  /** Tools' tiers, worst first: a builder wears the worst out before it touches a better one. */
  private val TIERS = listOf("wooden", "stone", "copper", "golden", "iron", "diamond", "netherite")

  /** How soon [item] (`stone_hoe`) wears, lower first; null for anything but a hoe or a shovel. */
  fun wearOrder(item: String, kind: String): Int? {
    if (!item.endsWith("_$kind")) return null
    return TIERS.indexOf(item.removeSuffix("_$kind")).takeIf { it >= 0 }
  }

  private val SKIN = Regex("\"url\"\\s*:\\s*\"(https?://textures\\.minecraft\\.net/texture/[0-9a-fA-F]{16,128})\"")

  /** A player head's skin link from its `textures` value; null for anything but the game's own skin server. */
  fun skin(texture: String): String? {
    val json = runCatching { String(java.util.Base64.getDecoder().decode(texture.trim()), Charsets.UTF_8) }.getOrNull() ?: return null
    return SKIN.find(json)?.groupValues?.get(1)?.replaceFirst("http://", "https://")
  }

  /** A facing as the game numbers it (2 north, 3 south, 4 west, 5 east) as a step on x and z; null for up and down. */
  fun step(facing: Int): Pair<Int, Int>? = when (facing) {
    2 -> 0 to -1
    3 -> 0 to 1
    4 -> -1 to 0
    5 -> 1 to 0
    else -> null
  }

  /** The base block a player names for a whole kind of block in the build: `oak_stairs` for any of its states. */
  fun kind(state: String) = Blueprint.block(state).removePrefix("minecraft:")
}

/**
 * A build job as it goes into its agent's snapshot: the build by its catalog source, where it stands, what the player
 * changed (swaps, skipped kinds), and how far it got. The blueprint itself is downloaded again on the way back.
 */
@Serializable data class BuildSave(
  val type: String = TYPE,
  val id: String,
  val url: String,
  val sha256: String,
  val bytes: Int,
  val title: String,
  val world: String,
  val x: Int,
  val y: Int,
  val z: Int,
  val facing: String,
  val mirrored: Boolean = false,
  /** Swaps in order, each `from` and `to`. */
  val swaps: List<List<String>> = emptyList(),
  /** Kinds of block the player said to leave out. */
  val skips: List<String> = emptyList(),
  /** The next cell in [Construction.order]. */
  val next: Int = 0,
  val placed: Int = 0,
  /** The next of the build's entities, once every block is up. */
  val entity_next: Int = 0,
  /** Cells something stood in the way of, or a claim refused, as [Shell.pack]s. */
  val blocked: List<Int> = emptyList(),
  /** Blocked cells the player cleared, to try before it goes on. */
  val again: List<Int> = emptyList(),
  /** The agent said go; before that the job only waits. */
  val started: Boolean = false,
  val paused: Boolean = false,
  val blocks_per_second: Int = 5,
  /** Since when it waits for an item, or 0. */
  val starved_since: Long = 0,
) {
  companion object {
    const val TYPE = "build"
  }
}
