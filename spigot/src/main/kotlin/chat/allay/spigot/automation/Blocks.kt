package chat.allay.spigot.automation

import chat.allay.core.tools.ToolError
import chat.allay.core.tools.int
import chat.allay.spigot.Claims

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Container
import org.bukkit.block.Sign
import org.bukkit.block.data.Directional
import org.bukkit.block.data.type.Hopper
import org.bukkit.block.data.type.WallHangingSign
import org.bukkit.block.data.type.WallSign
import org.bukkit.block.sign.Side
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.inventory.DoubleChestInventory
import org.bukkit.inventory.ItemStack

/** What automation needs to know about blocks: which may be used, what's written on them, and who could see them. */
object Blocks {
  private val SIDES = listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)

  /** A container's address for reservations and plans: `world:x,y,z`. */
  fun place(block: Block) = "${block.world.name}:${block.x},${block.y},${block.z}"

  fun inventory(player: Player) = "inventory:${player.uniqueId}"

  /** Chests, barrels, copper chests at every stage, and placed shulker boxes. */
  fun storage(type: Material) = type == Material.CHEST || type == Material.TRAPPED_CHEST || type == Material.BARREL ||
    Tag.COPPER_CHESTS.isTagged(type) || Tag.SHULKER_BOXES.isTagged(type)

  /** A placed shulker box, which takes no shulker box, as in the game. */
  fun box(block: Block) = Tag.SHULKER_BOXES.isTagged(block.type)

  fun furnace(type: Material) = type == Material.FURNACE || type == Material.BLAST_FURNACE || type == Material.SMOKER

  /** Both halves of a double chest, the left one first, or the block alone. */
  fun halves(block: Block): List<Block> {
    val inventory = (block.state as? Container)?.inventory as? DoubleChestInventory ?: return listOf(block)
    return listOfNotNull(inventory.leftSide.location?.block, inventory.rightSide.location?.block).ifEmpty { listOf(block) }
  }

  /** The block that stands for a chest: the left half of a double one. */
  fun root(block: Block): Block = halves(block).first()

  /**
   * Whether the player could see part of [block] that they could click: glass and leaves block the view, open doors,
   * grass, and water don't, and a slab in front leaves the upper half in sight. With [required] off
   * (`automation.line_of_sight: false`) every block counts as seen.
   */
  fun sees(player: Player, block: Block, required: Boolean = true): Boolean {
    if (!required) return true
    val eyes = player.eyeLocation
    if (eyes.world != block.world) return false
    // Any part of a face the player could click counts, as in the game: a ray to the center alone grazes the glass
    // or the furnace beside it, and a slab in front hides only the lower half. Water never blocks the view.
    return SIGHT_POINTS.any { (x, y, z) ->
      val target = block.location.add(x, y, z)
      val direction = target.toVector().subtract(eyes.toVector())
      val distance = direction.length()
      if (distance < 0.01) return@any true
      val hit = eyes.world!!.rayTraceBlocks(eyes, direction.normalize(), distance + 0.5, FluidCollisionMode.NEVER, true) ?: return@any true
      hit.hitBlock == block
    }
  }

  /** The center, then a 3×3 grid just inside each face, so a ray aimed at a face ends in this block, not its neighbor. */
  private val SIGHT_POINTS: List<Triple<Double, Double, Double>> = buildList {
    add(Triple(0.5, 0.5, 0.5))
    val grid = listOf(0.15, 0.5, 0.85)
    for (inside in listOf(0.05, 0.95)) for (a in grid) for (b in grid) {
      add(Triple(inside, a, b))
      add(Triple(a, inside, b))
      add(Triple(a, b, inside))
    }
  }

  /** A hopper feeds the block or pulls from it: a farm's output or a sorting machine, which automation leaves alone. */
  fun hopperLinked(block: Block): Boolean = halves(block).any { half ->
    if (half.getRelative(BlockFace.DOWN).type == Material.HOPPER) return@any true
    (SIDES + BlockFace.UP).any { face ->
      val next = half.getRelative(face)
      next.type == Material.HOPPER && (next.blockData as? Hopper)?.facing == face.oppositeFace
    }
  }

  /** Someone has the container open, the player included: the client would undo or race what automation writes. */
  fun open(block: Block): Boolean = (block.state as? Container)?.inventory?.viewers?.isNotEmpty() == true

  /** Every sign on the chest: wall signs and hanging signs on its sides, a hanging sign under it, a sign on top. Both faces, all lines. */
  fun signs(block: Block): List<String> = halves(block).flatMap { half ->
    val found = mutableListOf<Block>()
    SIDES.forEach { face ->
      val next = half.getRelative(face)
      val data = next.blockData
      if ((data is WallSign && data.facing == face) || data is WallHangingSign) found += next
    }
    half.getRelative(BlockFace.UP).takeIf { Tag.STANDING_SIGNS.isTagged(it.type) }?.let(found::add)
    half.getRelative(BlockFace.DOWN).takeIf { Tag.CEILING_HANGING_SIGNS.isTagged(it.type) }?.let(found::add)
    found.mapNotNull { sign ->
      val state = sign.state as? Sign ?: return@mapNotNull null
      Side.entries.flatMap { side -> state.getSide(side).lines.toList() }.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { null }
    }
  }.distinct()

  /** Items shown in item frames hung on the chest. */
  fun frames(block: Block): List<ItemStack> = halves(block).flatMap { half ->
    half.world.getNearbyEntities(half.boundingBox.expand(0.6)).filterIsInstance<ItemFrame>()
      .filter { frame -> frame.location.block.getRelative(frame.attachedFace) == half }
      .mapNotNull { it.item.takeUnless { item -> item.type.isAir } }
  }

  /** Block entities within [radius] of [origin], reading only loaded chunks. */
  fun near(origin: Location, radius: Int, filter: (Block) -> Boolean): List<Block> {
    val world = origin.world ?: return emptyList()
    val found = mutableListOf<Block>()
    for (cx in ((origin.blockX - radius) shr 4)..((origin.blockX + radius) shr 4)) for (cz in ((origin.blockZ - radius) shr 4)..((origin.blockZ + radius) shr 4)) {
      if (!world.isChunkLoaded(cx, cz)) continue
      world.getChunkAt(cx, cz).tileEntities.forEach { state ->
        if (state.location.distanceSquared(origin) <= radius * radius && filter(state.block)) found += state.block
      }
    }
    return found.sortedBy { it.location.distanceSquared(origin) }
  }

  /** For a scan that found nothing in [radius]: the nearest such block within [far], so the agent can say where to go. */
  fun beyond(origin: Location, radius: Int, far: Int = 64, filter: (Block) -> Boolean): Block? =
    near(origin, maxOf(far, radius), filter).firstOrNull { it.location.distanceSquared(origin) > radius * radius }

  /** Why a block can't take part, with a line for the model; players read `automation.unfit.<name>`. */
  enum class Unfit(val model: String) {
    TYPE("not the kind of block asked for"),
    CLAIM("the player can't open it (someone's claim, or an unopened structure chest)"),
    HOPPER("a hopper feeds it or pulls from it (a farm or a sorter)"),
    OPEN("someone has it open"),
    FAR("too far from the player"),
    SIGHT("something solid stands between it and the player's eyes"),
    BUSY("it's working on something else");

    val key get() = "automation.unfit.${name.lowercase()}"
  }

  /**
   * Why a storage container can't be used, or null when it can. With [adding] a hopper is fine: putting things into a
   * sorter's input chest is what it's for, only rearranging or taking from one would race the machine.
   */
  fun unsuitable(player: Player, block: Block, adding: Boolean = false): Unfit? {
    if (!storage(block.type)) return Unfit.TYPE
    val state = block.state as? Container ?: return Unfit.TYPE
    if (!Claims.mayOpen(player, state)) return Unfit.CLAIM
    if (!adding && hopperLinked(block)) return Unfit.HOPPER
    if (open(block)) return Unfit.OPEN
    return null
  }

  /**
   * Why a furnace, brewing stand, or crafting table can't be used, or null when it can: someone's claim, farther than
   * [radius], or out of the player's sight when [sight] is on.
   */
  fun unusable(player: Player, block: Block, radius: Int, sight: Boolean): Unfit? {
    (block.state as? Container)?.let { if (!Claims.mayOpen(player, it)) return Unfit.CLAIM }
    if (block.world != player.world || block.location.add(0.5, 0.5, 0.5).distance(player.eyeLocation) > radius) return Unfit.FAR
    if (!sees(player, block, sight)) return Unfit.SIGHT
    if (open(block)) return Unfit.OPEN
    return null
  }

  /**
   * The blocks an argument names: `[{x, y, z}, …]` in the player's world, each within [limit] blocks of them. Checked
   * before the block is touched, so a far spot never loads a chunk and no base across the map can be reached.
   */
  fun list(player: Player, value: Any?, limit: Int): List<Block> = (value as? JsonArray)?.mapNotNull { element ->
    val spot = element as? JsonObject ?: return@mapNotNull null
    val x = spot.int("x") ?: throw ToolError("each block needs x, y, and z")
    val y = spot.int("y") ?: throw ToolError("each block needs x, y, and z")
    val z = spot.int("z") ?: throw ToolError("each block needs x, y, and z")
    val here = player.location
    if (Location(player.world, x + 0.5, y + 0.5, z + 0.5).distance(here) > limit) throw ToolError("the block at $x $y $z is farther than $limit blocks from the player")
    player.world.getBlockAt(x, y, z)
  }.orEmpty()
}

/**
 * In a scan's result: no [what] within [radius] of the player, and the nearest one beyond, so the agent asks them to come
 * closer instead of calling them busy.
 */
internal fun JsonObjectBuilder.unreached(player: Player, radius: Int, what: String, filter: (Block) -> Boolean) {
  val nearest = Blocks.beyond(player.location, radius, filter = filter)
  put("none_within", radius)
  if (nearest == null) {
    put("reach_note", "no $what within $radius blocks of the player: ask them to go to theirs, then wait")
    return
  }
  put("nearest_beyond_reach", buildJsonObject {
    put("x", nearest.x); put("y", nearest.y); put("z", nearest.z); put("distance", nearest.location.distance(player.location).toInt())
  })
  put("reach_note", "no $what within $radius blocks of the player, so none is busy or free: ask them in a line to come within $radius blocks of the one at ${nearest.x} ${nearest.y} ${nearest.z}, then wait")
}
