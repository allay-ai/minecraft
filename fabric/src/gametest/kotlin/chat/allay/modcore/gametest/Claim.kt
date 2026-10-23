package chat.allay.modcore.gametest

import eu.pb4.common.protection.api.CommonProtection
import eu.pb4.common.protection.api.ProtectionProvider
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.server.players.NameAndId
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import java.util.concurrent.CopyOnWriteArrayList

/** A claims mod for the tests on Fabric, behind Common Protection API as GOML is: nobody builds in [areas]. */
object Claim : ProtectionProvider {
  val areas = CopyOnWriteArrayList<AABB>()

  init {
    CommonProtection.register(Identifier.fromNamespaceAndPath("allay_gametest", "claims"), this)
  }

  private fun inside(pos: BlockPos) = areas.any { it.contains(Vec3.atCenterOf(pos)) }
  override fun isProtected(level: Level, pos: BlockPos) = inside(pos)
  override fun isAreaProtected(level: Level, area: AABB) = areas.any { it.intersects(area) }
  override fun canPlaceBlock(level: Level, pos: BlockPos, profile: NameAndId, player: Player?) = !inside(pos)
  override fun canBreakBlock(level: Level, pos: BlockPos, profile: NameAndId, player: Player?) = !inside(pos)
}
