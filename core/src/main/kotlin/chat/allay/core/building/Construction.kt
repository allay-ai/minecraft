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

  /** What the ground of a lot is made of, as the world's is: the lot's ground may stand on the world's own. */
  private val GROUND = setOf(
    "dirt", "grass_block", "coarse_dirt", "rooted_dirt", "podzol", "mycelium", "mud", "moss_block", "dirt_path", "farmland",
    "stone", "deepslate", "granite", "diorite", "andesite", "tuff", "gravel", "sand", "red_sand", "clay", "netherrack",
  )

  /** Whether [block] (without `minecraft:`) is ground, as a lot or the world has it. */
  fun ground(block: String) = block.removePrefix("minecraft:") in GROUND

  /** What the world grows by itself, besides trees: the builder clears it out of the build's way, as a player would dig it. */
  private val NATURAL = GROUND + setOf(
    "snow", "snow_block", "ice", "packed_ice", "blue_ice", "powder_snow", "sandstone", "red_sandstone", "terracotta", "calcite", "dripstone_block",
    "pointed_dripstone", "smooth_basalt", "basalt", "blackstone", "soul_sand", "soul_soil", "magma_block", "obsidian", "suspicious_sand", "suspicious_gravel",
    "cobblestone", "mossy_cobblestone", "moss_carpet", "pale_moss_block", "pale_moss_carpet", "cactus", "sugar_cane", "bamboo", "vine", "glow_lichen",
    "brown_mushroom", "red_mushroom", "brown_mushroom_block", "red_mushroom_block", "mushroom_stem", "sweet_berry_bush", "lily_pad", "big_dripleaf",
    "big_dripleaf_stem", "small_dripleaf", "spore_blossom", "azalea", "flowering_azalea", "hanging_roots", "mangrove_roots", "muddy_mangrove_roots",
    "cocoa", "short_grass", "tall_grass", "fern", "large_fern", "dead_bush", "pumpkin", "melon", "kelp", "kelp_plant", "seagrass", "tall_seagrass", "sea_pickle", "firefly_bush", "bush", "leaf_litter",
    "wildflowers", "cactus_flower", "short_dry_grass", "tall_dry_grass", "pale_hanging_moss", "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet",
    "oxeye_daisy", "cornflower", "lily_of_the_valley", "torchflower", "pink_petals", "sunflower", "lilac", "rose_bush", "peony", "open_eyeblossom",
    "closed_eyeblossom",
  )

  /**
   * Whether [block] (without `minecraft:`) is the world's own, which the builder may break for the build: ground,
   * stone, sand, ores, plants, and the like. Leaves and logs only as part of a tree, which the platform tells by
   * leaves the game lets decay; anything a player could have built stays, and the builder goes around it.
   */
  fun natural(block: String): Boolean {
    val name = block.removePrefix("minecraft:")
    return name in NATURAL || name.endsWith("_ore") || name.endsWith("_tulip") || name.endsWith("_terracotta") && !name.endsWith("glazed_terracotta") ||
      name.endsWith("_sapling") || name.endsWith("_coral") || name.endsWith("_coral_block") || name.endsWith("_coral_fan")
  }

  /** A tree's log or its wood: what a felled tree loses with its leaves. */
  fun log(block: String): Boolean {
    val name = block.removePrefix("minecraft:")
    return !name.startsWith("stripped_") && (name.endsWith("_log") || name.endsWith("_wood") || name == "mangrove_roots")
  }

  /**
   * The layer of [blueprint] its lot's surface lies in: the highest layer that ground covers at least two fifths of,
   * or -1 for a build without a lot. A preview sinks the build so this layer meets the world's ground.
   */
  fun surface(blueprint: Blueprint): Int {
    val ground = BooleanArray(blueprint.palette.size) { ground(Blueprint.block(blueprint.palette[it])) }
    val area = blueprint.width * blueprint.length
    var top = -1
    for (y in 0 until blueprint.height) {
      var count = 0
      for (z in 0 until blueprint.length) for (x in 0 until blueprint.width) if (ground[blueprint.at(x, y, z)]) count++
      if (count * 5 >= area * 2) top = y
    }
    return top
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
  /** How far it cleared the box of the world's own blocks, top down, before it builds. */
  val cleared: Int = 0,
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
