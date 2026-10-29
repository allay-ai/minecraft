package chat.allay.modcore.automation

import chat.allay.core.tools.ToolError
import chat.allay.core.tools.int
import chat.allay.modcore.Claims
import chat.allay.modcore.tools.BlockAt
import chat.allay.modcore.tools.Worlds
import chat.allay.modcore.tools.at

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.BlockTags
import net.minecraft.world.CompoundContainer
import net.minecraft.world.Container
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.EmptyBlockGetter
import net.minecraft.world.level.block.Blocks as GameBlocks
import net.minecraft.world.level.block.ChestBlock
import net.minecraft.world.level.block.HopperBlock
import net.minecraft.world.level.block.WallSignBlock
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity
import net.minecraft.world.level.block.entity.SignBlockEntity
import net.minecraft.world.level.block.entity.SignTextSlot
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.ChestType
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/** What automation needs to know about blocks: which may be used, what's written on them, and who could see them. */
object Blocks {
  private val SIDES = listOf(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)

  /** A container's address for reservations and plans: `world:x,y,z`. */
  fun place(block: BlockAt) = "${Worlds.name(block.level)}:${block.x},${block.y},${block.z}"

  fun inventory(player: ServerPlayer) = "inventory:${player.uuid}"

  /** Chests, barrels, copper chests at every stage, and placed shulker boxes. */
  fun storage(state: BlockState) = state.`is`(GameBlocks.CHEST) || state.`is`(GameBlocks.TRAPPED_CHEST) || state.`is`(GameBlocks.BARREL) ||
    state.`is`(BlockTags.COPPER_CHESTS) || state.`is`(BlockTags.SHULKER_BOXES)

  /** A placed shulker box, which takes no shulker box, as in the game. */
  fun box(block: BlockAt) = block.state.`is`(BlockTags.SHULKER_BOXES)

  fun furnace(state: BlockState) = state.`is`(GameBlocks.FURNACE) || state.`is`(GameBlocks.BLAST_FURNACE) || state.`is`(GameBlocks.SMOKER)

  /** The block entity with an inventory there, as Bukkit's `Container`: chests, barrels, furnaces, hoppers, stands. */
  fun container(block: BlockAt): BaseContainerBlockEntity? = block.entity as? BaseContainerBlockEntity

  /**
   * What the container holds as the game opens it: both halves of a double chest as one, the left half first, as
   * Bukkit's `DoubleChestInventory`.
   */
  fun inventory(block: BlockAt): Container? {
    val state = block.state
    val chest = state.block as? ChestBlock
    if (chest != null) return ChestBlock.getContainer(chest, state, block.level, block.pos, true) ?: container(block)
    return container(block)
  }

  /**
   * Both halves of a double chest, the left one first, or the block alone. The left half is the one the game names
   * first when it joins them (its type says `right`), as Bukkit's `DoubleChestInventory.getLeftSide` gives it.
   */
  fun halves(block: BlockAt): List<BlockAt> {
    val state = block.state
    if (state.block !is ChestBlock || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return listOf(block)
    val other = block.level.at(ChestBlock.getConnectedBlockPos(block.pos, state))
    if (other.state.block !is ChestBlock) return listOf(block)
    return if (state.getValue(ChestBlock.TYPE) == ChestType.RIGHT) listOf(block, other) else listOf(other, block)
  }

  /** The block that stands for a chest: the left half of a double one. */
  fun root(block: BlockAt): BlockAt = halves(block).first()

  /**
   * Whether the player could see [block] from where they stand or after a step aside: only a full opaque block (stone,
   * planks, a wall) in between hides it. Glass, leaves, slabs, fences, stands, and water let the view through, and a ray
   * to any point of the block counts, so a thin brewing stand or a chest behind a pane is seen. A pillar or a post in a
   * room hides nothing the player could walk around: the view also counts from [ASIDE] blocks to each side, wherever
   * the way there is open, but never through a wall. With [required] off (`automation.line_of_sight: false`) every
   * block counts as seen.
   */
  fun sees(player: ServerPlayer, block: BlockAt, required: Boolean = true): Boolean {
    if (!required) return true
    if (player.level() != block.level) return false
    val from = player.eyePosition
    val views = listOf(from) + STEPS.map { (x, z) -> from.add(x * ASIDE, 0.0, z * ASIDE) }
      .filter { view -> clear(null, block.level, from, view) && !occluding(block.level.getBlockState(BlockPos.containing(view).below())) }
    return views.any { view -> SIGHT_POINTS.any { (x, y, z) -> clear(block, block.level, view, Vec3(block.x + x, block.y + y, block.z + z)) } }
  }

  /** Whether any face of [block] touches a block that lets the view through: one sealed in stone can't be seen at all. */
  fun exposed(block: BlockAt): Boolean =
    net.minecraft.core.Direction.entries.any { !occluding(block.level.getBlockState(block.pos.relative(it))) }

  /**
   * Walks the line from [from] to [to] in small steps; true when it reaches [target] (or, without one, the end) before
   * any opaque full block.
   */
  private fun clear(target: BlockAt?, level: net.minecraft.world.level.Level, from: Vec3, to: Vec3): Boolean {
    val line = to.subtract(from)
    val steps = (line.length() / SIGHT_STEP).toInt().coerceAtLeast(1)
    var last: BlockPos? = null
    for (i in 0..steps) {
      val cell = BlockPos.containing(from.add(line.scale(i.toDouble() / steps)))
      if (cell == last) continue
      last = cell
      if (target != null && cell == target.pos) return true
      if (occluding(level.getBlockState(cell))) return false
    }
    return true
  }

  /** How far a step aside goes, in blocks: around a pillar two blocks wide. */
  private const val ASIDE = 2.0

  /** The eight ways a step aside goes, as x and z. */
  private val STEPS = listOf(1.0 to 0.0, -1.0 to 0.0, 0.0 to 1.0, 0.0 to -1.0, 0.7 to 0.7, 0.7 to -0.7, -0.7 to 0.7, -0.7 to -0.7)

  /**
   * Bukkit's `Material.isOccluding`: by the block's type, whether it conducts redstone as a full block does. A sea
   * lantern or an observer lets the view through, a shulker box doesn't.
   */
  private fun occluding(state: BlockState) = state.block.defaultBlockState().isRedstoneConductor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)

  private const val SIGHT_STEP = 0.1

  /** The center and the corners' insides: a line to any of them that reaches the block means it's in sight. */
  private val SIGHT_POINTS: List<Triple<Double, Double, Double>> = buildList {
    add(Triple(0.5, 0.5, 0.5))
    val near = listOf(0.1, 0.9)
    for (x in near) for (y in near) for (z in near) add(Triple(x, y, z))
    for (face in listOf(0.05, 0.95)) {
      add(Triple(face, 0.5, 0.5))
      add(Triple(0.5, face, 0.5))
      add(Triple(0.5, 0.5, face))
    }
  }

  /** A hopper feeds the block or pulls from it: a farm's output or a sorting machine, which automation leaves alone. */
  fun hopperLinked(block: BlockAt): Boolean = halves(block).any { half ->
    if (half.relative(Direction.DOWN).state.`is`(GameBlocks.HOPPER)) return@any true
    (SIDES + Direction.UP).any { face ->
      val next = half.relative(face).state
      next.`is`(GameBlocks.HOPPER) && next.getValue(HopperBlock.FACING) == face.opposite
    }
  }

  /** A hopper under the block pulls from it: taking from it would race the machine draining it. */
  fun drained(block: BlockAt): Boolean = halves(block).any { it.relative(Direction.DOWN).state.`is`(GameBlocks.HOPPER) }

  /** Someone has the container open, the player included: the client would undo or race what automation writes. */
  fun open(block: BlockAt): Boolean {
    val container = container(block) ?: return false
    return block.level.server.playerList.players.any { player ->
      player.containerMenu !== player.inventoryMenu && player.containerMenu.slots.any { slot ->
        slot.container === container || (slot.container as? CompoundContainer)?.contains(container) == true
      }
    }
  }

  /** Every sign on the chest: wall signs and hanging signs on its sides, a hanging sign under it, a sign on top. Both faces, all lines. */
  fun signs(block: BlockAt): List<String> = halves(block).flatMap { half ->
    val found = mutableListOf<BlockAt>()
    SIDES.forEach { face ->
      val next = half.relative(face)
      val state = next.state
      if ((state.`is`(BlockTags.WALL_SIGNS) && state.getValue(WallSignBlock.FACING) == face) || state.`is`(BlockTags.WALL_HANGING_SIGNS)) found += next
    }
    half.relative(Direction.UP).takeIf { it.state.`is`(BlockTags.STANDING_SIGNS) }?.let(found::add)
    half.relative(Direction.DOWN).takeIf { it.state.`is`(BlockTags.CEILING_HANGING_SIGNS) }?.let(found::add)
    found.mapNotNull { sign ->
      val entity = sign.entity as? SignBlockEntity ?: return@mapNotNull null
      listOf(entity.getText(SignTextSlot.FRONT), entity.getText(SignTextSlot.BACK)).flatMap { text -> text.getMessages(false).map { it.string.trim() } }
        .filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { null }
    }
  }.distinct()

  /** Items shown in item frames hung on the chest. */
  fun frames(block: BlockAt): List<ItemStack> = halves(block).flatMap { half ->
    half.level.getEntitiesOfClass(ItemFrame::class.java, AABB(half.pos).inflate(0.6))
      .filter { frame -> frame.pos.relative(frame.direction.opposite) == half.pos }
      .mapNotNull { it.item.takeUnless { item -> item.isEmpty } }
  }

  /** Block entities within [radius] of [origin], reading only loaded chunks. */
  fun near(level: ServerLevel, origin: Vec3, radius: Int, filter: (BlockAt) -> Boolean): List<BlockAt> {
    val found = mutableListOf<BlockAt>()
    val x = origin.x.toInt()
    val z = origin.z.toInt()
    for (cx in ((x - radius) shr 4)..((x + radius) shr 4)) for (cz in ((z - radius) shr 4)..((z + radius) shr 4)) {
      val chunk = level.chunkSource.getChunkNow(cx, cz) ?: continue
      chunk.blockEntities.keys.forEach { pos ->
        val block = level.at(pos)
        if (block.corner.distanceToSqr(origin) <= radius.toDouble() * radius && filter(block)) found += block
      }
    }
    return found.sortedBy { it.corner.distanceToSqr(origin) }
  }

  fun near(player: ServerPlayer, radius: Int, filter: (BlockAt) -> Boolean) = near(player.level(), player.position(), radius, filter)

  /** Chunks the radius touches that aren't loaded, so a search there read nothing. */
  fun unloaded(player: ServerPlayer, radius: Int): Int {
    val x = player.position().x.toInt()
    val z = player.position().z.toInt()
    var unloaded = 0
    for (cx in ((x - radius) shr 4)..((x + radius) shr 4)) for (cz in ((z - radius) shr 4)..((z + radius) shr 4)) {
      if (player.level().chunkSource.getChunkNow(cx, cz) == null) unloaded++
    }
    return unloaded
  }

  /** For a scan that found nothing in [radius]: the nearest such block within [far], so the agent can say where to go. */
  fun beyond(player: ServerPlayer, radius: Int, far: Int = 96, filter: (BlockAt) -> Boolean): BlockAt? =
    near(player, maxOf(far, radius), filter).firstOrNull { it.corner.distanceToSqr(player.position()) > radius.toDouble() * radius }

  /** Why a block can't take part, with a line for the model; players read `automation.unfit.<name>`. */
  enum class Unfit(val model: String) {
    TYPE("not the kind of block asked for"),
    CLAIM("the player can't open it (someone's claim, or an unopened structure chest)"),
    HOPPER("a hopper feeds it or pulls from it (a farm or a sorter)"),
    OPEN("someone has it open"),
    FAR("too far from the player"),
    SIGHT("a wall stands between it and the player's eyes"),
    BUSY("it's working on something else");

    val key get() = "automation.unfit.${name.lowercase()}"
  }

  /**
   * Why a storage container can't be used, or null when it can. With [adding] a hopper is fine: putting things into a
   * sorter's input chest is what it's for, only rearranging or taking from one would race the machine. With [taking]
   * only a hopper draining it counts: a sorter's output chest, which hoppers fill, is where the player takes from.
   */
  fun unsuitable(player: ServerPlayer, block: BlockAt, adding: Boolean = false, taking: Boolean = false): Unfit? {
    if (!storage(block.state)) return Unfit.TYPE
    container(block) ?: return Unfit.TYPE
    if (!Claims.mayOpen(player, block.level, block.pos)) return Unfit.CLAIM
    if (!adding && (if (taking) drained(block) else hopperLinked(block))) return Unfit.HOPPER
    if (open(block)) return Unfit.OPEN
    return null
  }

  /**
   * Why a furnace, brewing stand, or crafting table can't be used, or null when it can: someone's claim, farther than
   * [radius], or out of the player's sight when [sight] is on.
   */
  fun unusable(player: ServerPlayer, block: BlockAt, radius: Int, sight: Boolean): Unfit? {
    container(block)?.let { if (!Claims.mayOpen(player, block.level, block.pos)) return Unfit.CLAIM }
    if (block.level != player.level() || block.distance(player.eyePosition) > radius) return Unfit.FAR
    if (!sees(player, block, sight)) return Unfit.SIGHT
    if (open(block)) return Unfit.OPEN
    return null
  }

  /**
   * The blocks an argument names: `[{x, y, z}, …]` in the player's world, each within [limit] blocks of them. Checked
   * before the block is touched, so a far spot never loads a chunk and no base across the map can be reached.
   */
  fun list(player: ServerPlayer, value: Any?, limit: Int): List<BlockAt> = (value as? JsonArray)?.mapNotNull { element ->
    val spot = element as? JsonObject ?: return@mapNotNull null
    val x = spot.int("x") ?: throw ToolError("each block needs x, y, and z")
    val y = spot.int("y") ?: throw ToolError("each block needs x, y, and z")
    val z = spot.int("z") ?: throw ToolError("each block needs x, y, and z")
    if (Vec3(x + 0.5, y + 0.5, z + 0.5).distanceTo(player.position()) > limit) throw ToolError("the block at $x $y $z is farther than $limit blocks from the player")
    player.level().at(x, y, z)
  }.orEmpty()
}

/**
 * In a scan's result: no [what] within [radius] of the player, and the nearest one beyond, so the agent asks them to come
 * closer instead of calling them busy.
 */
internal fun JsonObjectBuilder.unreached(player: ServerPlayer, radius: Int, what: String, filter: (BlockAt) -> Boolean) {
  val nearest = Blocks.beyond(player, radius, filter = filter)
  put("none_within", radius)
  if (nearest == null) {
    put("reach_note", "no $what within $radius blocks of the player: ask them to go to theirs, then wait")
    return
  }
  put("nearest_beyond_reach", buildJsonObject {
    put("x", nearest.x)
    put("y", nearest.y)
    put("z", nearest.z)
    put("distance", nearest.corner.distanceTo(player.position()).toInt())
  })
  put("reach_note", "no $what within $radius blocks of the player, so none is busy or free: ask them in a line to come within $radius blocks of the one at ${nearest.x} ${nearest.y} ${nearest.z}, then wait")
}
