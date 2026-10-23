package chat.allay.modcore.gametest

import chat.allay.modcore.Claims

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.concurrent.CopyOnWriteArrayList

/** A claims mod for the tests, straight behind [Claims]: nobody builds in [areas]. */
object Claim : Claims.Provider {
  val areas = CopyOnWriteArrayList<AABB>()

  init {
    Claims.others += this
  }

  private fun inside(pos: BlockPos) = areas.any { it.contains(Vec3.atCenterOf(pos)) }
  override fun claimed(level: Level, pos: BlockPos) = inside(pos)
  override fun open(player: ServerPlayer, level: Level, pos: BlockPos) = true
  override fun build(player: ServerPlayer, level: Level, pos: BlockPos) = !inside(pos)
}
