package chat.allay.core.building

/**
 * What a block costs to place, as a player in survival pays for it: the items, a tool's wear, a water bucket kept at
 * hand. Pure rules over the game's names; whether an item exists on this server is the platform's to say. The cases:
 * wall variants take the standing item, crops their seeds, a double slab two slabs, candles and pickles their count,
 * a pot its plant too, the top half of a door or a tall plant nothing (the bottom pays), farmland and paths dirt and a
 * tool's use, a water source or a waterlogged block a water bucket (kept, as a player keeps an infinite source), a lava
 * source a lava bucket (its bucket comes back), and blocks nobody can get in survival nothing, listed apart.
 */
object Materials {
  /** One block's price. [items] by item id; [tool] a hoe or shovel used once; [water] needs a water bucket at hand. */
  data class Cost(
    val items: Map<String, Int> = emptyMap(),
    val tool: String? = null,
    val water: Boolean = false,
    val unobtainable: Boolean = false,
    /** Placed by Allay for free: a player's head, which costs nothing anywhere. */
    val free: Boolean = false,
  ) {
    companion object {
      val NOTHING = Cost()
    }
  }

  /** What [blocks] cost together: every item and how many, the tools' uses, and what can't be had. */
  data class Estimate(
    val items: Map<String, Int>,
    val tools: Map<String, Int>,
    val water: Boolean,
    val unobtainable: Map<String, Int>,
    val free: Map<String, Int>,
  )

  /** Blocks no survival player gets as an item. */
  val UNOBTAINABLE = setOf(
    "bedrock", "barrier", "light", "command_block", "chain_command_block", "repeating_command_block", "structure_block", "jigsaw",
    "spawner", "trial_spawner", "vault", "reinforced_deepslate", "budding_amethyst", "end_portal_frame", "end_portal", "nether_portal",
    "end_gateway", "chorus_plant", "petrified_oak_slab", "suspicious_sand", "suspicious_gravel", "frogspawn", "test_block",
    "test_instance_block", "knowledge_book", "debug_stick",
  )

  /** Nothing to place: the game makes it, or it's part of another block. */
  private val NONE = setOf(
    "air", "cave_air", "void_air", "structure_void", "piston_head", "moving_piston", "fire", "soul_fire", "bubble_column",
    "frosted_ice", "tripwire_hook_attached",
  )

  private val ITEM = mapOf(
    "redstone_wire" to "redstone", "tripwire" to "string", "cocoa" to "cocoa_beans", "wheat" to "wheat_seeds", "carrots" to "carrot",
    "potatoes" to "potato", "beetroots" to "beetroot_seeds", "pumpkin_stem" to "pumpkin_seeds", "attached_pumpkin_stem" to "pumpkin_seeds",
    "melon_stem" to "melon_seeds", "attached_melon_stem" to "melon_seeds", "sweet_berry_bush" to "sweet_berries", "cave_vines" to "glow_berries",
    "cave_vines_plant" to "glow_berries", "bamboo_sapling" to "bamboo", "kelp_plant" to "kelp", "twisting_vines_plant" to "twisting_vines",
    "weeping_vines_plant" to "weeping_vines", "big_dripleaf_stem" to "big_dripleaf", "torchflower_crop" to "torchflower_seeds",
    "pitcher_crop" to "pitcher_pod", "powder_snow" to "powder_snow_bucket", "water_cauldron" to "cauldron", "lava_cauldron" to "cauldron",
    "powder_snow_cauldron" to "cauldron", "tall_seagrass" to "seagrass", "wall_torch" to "torch", "soul_wall_torch" to "soul_torch",
    "redstone_wall_torch" to "redstone_torch", "copper_wall_torch" to "copper_torch", "skeleton_wall_skull" to "skeleton_skull",
    "wither_skeleton_wall_skull" to "wither_skeleton_skull", "zombie_wall_head" to "zombie_head", "creeper_wall_head" to "creeper_head",
    "dragon_wall_head" to "dragon_head", "piglin_wall_head" to "piglin_head", "player_wall_head" to "player_head",
  )

  /** Pots by the plant in them, where the plant's name isn't the pot's minus `potted_`. */
  private val POTTED = mapOf("azalea_bush" to "azalea", "flowering_azalea_bush" to "flowering_azalea")

  /** Blocks whose top half the bottom half's item pays for. */
  private val TALL = setOf("sunflower", "lilac", "rose_bush", "peony", "tall_grass", "large_fern", "pitcher_plant", "small_dripleaf", "tall_seagrass")

  /** Blocks that grow from one face per item, each true face one more. */
  private val FACES = setOf("vine", "glow_lichen", "sculk_vein", "resin_clump")
  private val SIDES = listOf("north", "south", "east", "west", "up", "down")

  /** Blocks that stack in one block: their count state, as the game names it. */
  private val COUNTED = mapOf("sea_pickle" to "pickles", "turtle_egg" to "eggs", "pink_petals" to "flower_amount", "wildflowers" to "flower_amount", "leaf_litter" to "segment_amount", "snow" to "layers")

  fun of(state: String): Cost {
    val name = Blueprint.block(state).removePrefix("minecraft:")
    val states = Blueprint.states(state)
    val water = states["waterlogged"] == "true"
    val cost = base(name, states)
    return if (water && !cost.unobtainable) cost.copy(water = true) else cost
  }

  private fun base(name: String, states: Map<String, String>): Cost {
    if (name in NONE) return Cost.NOTHING
    if (name in UNOBTAINABLE || name.startsWith("infested_")) return Cost(unobtainable = true)
    // The top halves: the bottom half's item places both.
    if (states["half"] == "upper" && (name.endsWith("_door") || name in TALL)) return Cost.NOTHING
    if (name.endsWith("_bed") && states["part"] == "head") return Cost.NOTHING
    if (name == "water") return if (states["level"] == "0") Cost(water = true) else Cost.NOTHING
    if (name == "lava") return if (states["level"] == "0") Cost(mapOf("lava_bucket" to 1)) else Cost.NOTHING
    if (name == "player_head" || name == "player_wall_head") return Cost(free = true)
    if (name == "farmland") return Cost(mapOf("dirt" to 1), tool = "hoe")
    if (name == "dirt_path") return Cost(mapOf("dirt" to 1), tool = "shovel")
    if (name.endsWith("_slab") && states["type"] == "double") return Cost(mapOf(name to 2))
    if (name == "candle_cake") return Cost(mapOf("cake" to 1, "candle" to 1))
    if (name.endsWith("_candle_cake")) return Cost(mapOf("cake" to 1, name.removeSuffix("_cake") to 1))
    if (name.endsWith("candle")) return Cost(mapOf(name to (states["candles"]?.toIntOrNull() ?: 1)))
    COUNTED[name]?.let { key -> return Cost(mapOf(name to (states[key]?.toIntOrNull() ?: 1))) }
    if (name in FACES) return Cost(mapOf(name to SIDES.count { states[it] == "true" }.coerceAtLeast(1)))
    if (name.startsWith("potted_")) {
      val plant = name.removePrefix("potted_")
      return Cost(mapOf("flower_pot" to 1, (POTTED[plant] ?: plant) to 1))
    }
    ITEM[name]?.let { return Cost(mapOf(it to 1)) }
    // Wall signs, banners, and coral fans take the standing item.
    for ((wall, standing) in WALLS) if (name.endsWith(wall)) return Cost(mapOf(name.removeSuffix(wall) + standing to 1))
    return Cost(mapOf(name to 1))
  }

  private val WALLS = listOf("_wall_hanging_sign" to "_hanging_sign", "_wall_sign" to "_sign", "_wall_banner" to "_banner", "_wall_fan" to "_fan")

  private val COLORS = listOf(
    "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue",
    "brown", "green", "red", "black",
  )

  /** Mob heads by the item a player gets; a player's head is free anyway. */
  private val MOB_HEADS = setOf("skeleton_skull", "wither_skeleton_skull", "zombie_head", "creeper_head", "dragon_head", "piglin_head")

  /**
   * [palette] as Allay builds it: a banner goes up plain white, since its patterns would need the loom's order undone.
   * The preview and the estimate show the same.
   */
  fun placed(palette: List<String>): List<String> = palette.map { state ->
    val block = Blueprint.block(state).removePrefix("minecraft:")
    val color = COLORS.firstOrNull { block == "${it}_banner" || block == "${it}_wall_banner" } ?: return@map state
    "minecraft:white" + block.removePrefix(color) + state.substring(Blueprint.block(state).length)
  }

  /**
   * A mob head as Steve's: what goes up free when the player has no such head, its rotation or facing kept. Null for
   * anything but a mob head.
   */
  fun steve(state: String): String? {
    val block = Blueprint.block(state).removePrefix("minecraft:")
    val item = ITEM[block] ?: block
    if (item !in MOB_HEADS) return null
    val wall = "_wall_" in block
    return "minecraft:" + (if (wall) "player_wall_head" else "player_head") + state.substring(Blueprint.block(state).length)
  }

  /** The item an entity of a build takes: a frame, a painting, an armor stand; null for what isn't built. */
  fun item(entity: Blueprint.Entity): String? = entity.id.takeIf { it in ENTITY_ITEMS }

  private val ENTITY_ITEMS = setOf("item_frame", "glow_item_frame", "painting", "armor_stand")

  /** What [blueprint] costs in all; [states] is its palette as the platform may have changed it (substitutions). */
  fun estimate(blueprint: Blueprint, states: List<String> = blueprint.palette): Estimate {
    val counts = blueprint.counts()
    val items = sortedMapOf<String, Int>()
    val tools = sortedMapOf<String, Int>()
    val unobtainable = sortedMapOf<String, Int>()
    val free = sortedMapOf<String, Int>()
    var water = false
    states.forEachIndexed { index, state ->
      val n = counts[index]
      if (n == 0 || blueprint.empty[index]) return@forEachIndexed
      val cost = of(state)
      val block = Blueprint.block(state).removePrefix("minecraft:")
      when {
        cost.unobtainable -> unobtainable.merge(block, n, Int::plus)
        cost.free -> free.merge(block, n, Int::plus)
        else -> cost.items.forEach { (item, each) -> items.merge(item, each * n, Int::plus) }
      }
      cost.tool?.let { tools.merge(it, n, Int::plus) }
      if (cost.water) water = true
    }
    blueprint.entities.forEach { entity -> item(entity)?.let { items.merge(it, 1, Int::plus) } }
    return Estimate(items, tools, water, unobtainable, free)
  }
}
