package chat.allay.core.building

import java.util.Base64
import kotlin.math.abs
import kotlin.math.floor
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

  /**
   * What the world grows by itself, besides trees: the builder clears it out of the build's way, as a player would dig
   * it. Players build with some of it too (cobblestone, sandstone, terracotta): a claim keeps theirs, since every break
   * goes through the server's break event or the claims mod.
   */
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
   * leaves the game lets decay; anything else stays, and the builder goes around it.
   */
  fun natural(block: String): Boolean {
    val name = block.removePrefix("minecraft:")
    return name in NATURAL || name.endsWith("_ore") || name.endsWith("_tulip") || name.endsWith("_terracotta") && !name.endsWith("glazed_terracotta") ||
      name.endsWith("_sapling") || name.endsWith("_coral") || name.endsWith("_coral_block") || name.endsWith("_coral_fan")
  }

  /** What decorates a lot: tufts, flowers, ferns, snow layers. It never contends with what stands in the world already. */
  private val DECOR = setOf(
    "short_grass", "tall_grass", "fern", "large_fern", "dead_bush", "bush", "firefly_bush", "snow", "leaf_litter", "pink_petals", "wildflowers",
    "short_dry_grass", "tall_dry_grass", "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "oxeye_daisy", "cornflower",
    "lily_of_the_valley", "torchflower", "wither_rose", "open_eyeblossom", "closed_eyeblossom", "sunflower", "lilac", "rose_bush", "peony",
    "brown_mushroom", "red_mushroom", "crimson_roots", "warped_roots", "nether_sprouts", "moss_carpet", "pale_moss_carpet", "glow_lichen", "vine",
  )

  /**
   * Whether [block] (without `minecraft:`) only decorates the build's lot: where something stands in the world
   * already, the builder passes it by, with no red box and no spot in the way; an empty spot gets it as usual.
   */
  fun decor(block: String): Boolean {
    val name = block.removePrefix("minecraft:")
    return name in DECOR || name.endsWith("_tulip") || name.endsWith("_sapling") || name == "mangrove_propagule"
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

  /**
   * The air of [blueprint] at its lot's [surface] and below that the build closes in: a basement, a cellar, a tunnel,
   * an arena's pit. The builder digs it out of the world's ground. Air that reaches the box's sides or bottom through
   * other air is the world's around the lot, and stays. Cells as [Shell.pack]s.
   */
  fun interior(blueprint: Blueprint, surface: Int): Set<Int> {
    if (surface < 0) return emptySet()
    val top = minOf(surface, blueprint.height - 1)
    val (w, l) = blueprint.width to blueprint.length
    val outside = HashSet<Int>()
    val queue = ArrayDeque<Int>()
    fun reach(x: Int, y: Int, z: Int) {
      if (x !in 0 until w || z !in 0 until l || y !in 0..top || blueprint.filled(x, y, z)) return
      if (outside.add(Shell.pack(x, y, z))) queue += Shell.pack(x, y, z)
    }
    for (y in 0..top) {
      for (x in 0 until w) { reach(x, y, 0); reach(x, y, l - 1) }
      for (z in 0 until l) { reach(0, y, z); reach(w - 1, y, z) }
    }
    for (z in 0 until l) for (x in 0 until w) reach(x, 0, z)
    while (queue.isNotEmpty()) {
      val cell = queue.removeFirst()
      val (x, y, z) = Triple(Shell.x(cell), Shell.y(cell), Shell.z(cell))
      reach(x + 1, y, z); reach(x - 1, y, z); reach(x, y + 1, z); reach(x, y - 1, z); reach(x, y, z + 1); reach(x, y, z - 1)
    }
    val inside = HashSet<Int>()
    for (y in 0..top) for (z in 0 until l) for (x in 0 until w) {
      if (!blueprint.filled(x, y, z) && Shell.pack(x, y, z) !in outside) inside += Shell.pack(x, y, z)
    }
    return inside
  }

  /** Tools' tiers, worst first: a builder wears the worst out before it touches a better one. */
  private val TIERS = listOf("wooden", "stone", "copper", "golden", "iron", "diamond", "netherite")

  /** How soon [item] (`stone_hoe`) wears, lower first; null for anything but a tool of [kind] (hoe, shovel, pickaxe). */
  fun wearOrder(item: String, kind: String): Int? {
    if (!item.endsWith("_$kind")) return null
    return TIERS.indexOf(item.removeSuffix("_$kind")).takeIf { it >= 0 }
  }

  /** Whether a use wears a tool with Unbreaking at [unbreaking], as the game rolls it: one use in level + 1. */
  fun wears(unbreaking: Int, roll: Double = Math.random()) = roll < 1.0 / (unbreaking.coerceAtLeast(0) + 1)

  private val SKIN = Regex("\"url\"\\s*:\\s*\"(https?://textures\\.minecraft\\.net/texture/[0-9a-fA-F]{16,128})\"")

  /** A player head's skin link from its `textures` value; null for anything but the game's own skin server. */
  fun skin(texture: String): String? {
    val json = runCatching { String(Base64.getDecoder().decode(texture.trim()), Charsets.UTF_8) }.getOrNull() ?: return null
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

  /**
   * The world block a hanging [entity] of [blueprint] hangs in, as the game spawns it there, [placement] turned and
   * mirrored. A frame's or an odd painting's is the block its middle is in; a painting of even width reaches a block
   * further to its left, seen from the front, and one of even height a block further up, so its middle lies on the
   * blocks' edge. Mirrored, an even painting reaches the other way, and its block moves one along.
   */
  fun hangs(entity: Blueprint.Entity, blueprint: Blueprint, placement: Placement): Triple<Int, Int, Int> {
    var x = floor(entity.x).toInt()
    var y = floor(entity.y).toInt()
    var z = floor(entity.z).toInt()
    val step = if (entity.id == "painting") step(entity.facing) else null
    var even = false
    if (step != null) {
      // Its left, seen from the front, is the way it faces turned counterclockwise.
      val (leftX, leftZ) = step.second to -step.first
      if (whole(entity.y)) y = Math.round(entity.y).toInt() - 1
      if (leftX != 0 && whole(entity.x)) {
        x = Math.round(entity.x).toInt() - if (leftX > 0) 1 else 0
        even = true
      }
      if (leftZ != 0 && whole(entity.z)) {
        z = Math.round(entity.z).toInt() - if (leftZ > 0) 1 else 0
        even = true
      }
    }
    val at = placement.world(blueprint, x, y, z)
    if (!even || !placement.mirrored) return at
    val (fx, fz) = placement.direction(blueprint, step!!.first, step.second)
    return Triple(at.first - fz, at.second, at.third + fx)
  }

  private fun whole(value: Double) = abs(value - Math.rint(value)) < 1e-4

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
  /** Blocked cells past the ones [blocked] keeps, and entities a claim refused: only counted. */
  val blocked_more: Int = 0,
  /** Blocked cells the player cleared, to try before it goes on. */
  val again: List<Int> = emptyList(),
  /** How far it cleared the box of the world's own blocks, top down, before it builds. */
  val cleared: Int = 0,
  /** The agent said go; before that the job only waits. */
  val started: Boolean = false,
  val paused: Boolean = false,
  val blocks_per_second: Int = 5,
  /** Since when it waits for an item, or 0, and for what, as the player reads it. */
  val starved_since: Long = 0,
  val waiting: String? = null,
) {
  companion object {
    const val TYPE = "build"
  }
}
