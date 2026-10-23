package chat.allay.fabric.tools

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * A block in a world, as Bukkit's `Block` is: the world and the spot, with what stands there read on use. Tools pass
 * these around instead of bare positions, so a block never loses its world. Read it on the server thread.
 */
class BlockAt(val level: ServerLevel, pos: BlockPos) {
  val pos: BlockPos = pos.immutable()
  val x get() = pos.x
  val y get() = pos.y
  val z get() = pos.z

  val state: BlockState get() = level.getBlockState(pos)
  val entity: BlockEntity? get() = level.getBlockEntity(pos)
  val block: net.minecraft.world.level.block.Block get() = state.block

  /** The block's id as the model reads it: `chest`, or `mod:thing`. */
  val key: String get() = Worlds.key(BuiltInRegistries.BLOCK.getKey(block))

  fun relative(direction: Direction, distance: Int = 1) = BlockAt(level, pos.relative(direction, distance))

  /** The block's lowest corner, where Bukkit's `Block.getLocation` points. */
  val corner: Vec3 get() = Vec3(x.toDouble(), y.toDouble(), z.toDouble())

  /** The middle of the block. */
  val center: Vec3 get() = Vec3.atCenterOf(pos)

  /** What the block's outline takes up, in the world; null for air and anything without a shape. */
  val bounds: AABB? get() = state.getShape(level, pos).takeUnless { it.isEmpty }?.bounds()?.move(pos)

  /** Nothing to bump into: air, grass, a torch, water. */
  val passable: Boolean get() = state.getCollisionShape(level, pos).isEmpty

  val liquid: Boolean get() = state.liquid()

  /** Distance from the block's middle to [point]. */
  fun distance(point: Vec3) = center.distanceTo(point)

  override fun equals(other: Any?) = other is BlockAt && other.level == level && other.pos == pos
  override fun hashCode() = 31 * level.dimension().hashCode() + pos.hashCode()
  override fun toString() = "${Worlds.name(level)}:$x,$y,$z"
}

fun ServerLevel.at(pos: BlockPos) = BlockAt(this, pos)
fun ServerLevel.at(x: Int, y: Int, z: Int) = BlockAt(this, BlockPos(x, y, z))
