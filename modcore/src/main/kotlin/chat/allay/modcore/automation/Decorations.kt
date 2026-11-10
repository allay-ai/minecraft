package chat.allay.modcore.automation

import chat.allay.core.building.Blueprint
import chat.allay.core.building.Construction
import chat.allay.core.building.Placement
import chat.allay.modcore.Claims

import com.google.common.collect.LinkedHashMultimap
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import com.mojang.authlib.properties.PropertyMap
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.decoration.GlowItemFrame
import net.minecraft.world.entity.decoration.HangingEntity
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.entity.decoration.painting.Painting
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ResolvableProfile
import net.minecraft.world.level.block.entity.SkullBlockEntity
import net.minecraft.world.phys.AABB
import java.util.Base64
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * What a builder puts up beside blocks: frames, paintings, and armor stands, empty, only where the player may build,
 * and a player head's skin. Server thread.
 */
object Decorations {
  enum class Result { PLACED, THERE, REFUSED }

  /** Puts [entity] up at [pos], turned as [placement] stands; THERE when one like it is there already. */
  fun place(player: ServerPlayer, level: ServerLevel, pos: BlockPos, entity: Blueprint.Entity, blueprint: Blueprint, placement: Placement): Result {
    if (!Claims.mayBuild(player, level, pos)) return Result.REFUSED
    // A restart may land between the spawn and the snapshot: one like it there already counts.
    if (level.getEntities(null as Entity?, AABB.ofSize(net.minecraft.world.phys.Vec3.atCenterOf(pos), 1.2, 1.2, 1.2)) { id(it) == entity.id }.isNotEmpty()) return Result.THERE
    val spawned: Entity = when (entity.id) {
      "armor_stand" -> ArmorStand(level, pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5).apply {
        val yaw = yaw(entity.yaw, blueprint, placement)
        yRot = yaw
        yBodyRot = yaw
        yHeadRot = yaw
      }
      "item_frame", "glow_item_frame", "painting" -> {
        val face = face(entity.facing, blueprint, placement) ?: return Result.REFUSED
        val hanging: HangingEntity = when (entity.id) {
          "item_frame" -> ItemFrame(level, pos, face)
          "glow_item_frame" -> GlowItemFrame(level, pos, face)
          else -> {
            val variant = entity.variant?.let { Identifier.tryParse("minecraft:$it") }
              ?.let { level.registryAccess().lookupOrThrow(Registries.PAINTING_VARIANT).get(ResourceKey.create(Registries.PAINTING_VARIANT, it)).orElse(null) }
            if (variant != null) Painting(level, pos, face, variant) else Painting.create(level, pos, face).orElse(null) ?: return Result.REFUSED
          }
        }
        // No wall to hang on, or no room: the spot is in the way.
        if (!hanging.survives()) return Result.REFUSED
        hanging
      }
      else -> return Result.REFUSED
    }
    return if (level.addFreshEntity(spawned)) Result.PLACED else Result.REFUSED
  }

  /** A player head at [pos] with the skin at [url], a link to the game's skin server. */
  fun skin(level: ServerLevel, pos: BlockPos, url: String) {
    val skull = level.getBlockEntity(pos) as? SkullBlockEntity ?: return
    val value = Base64.getEncoder().encodeToString("{\"textures\":{\"SKIN\":{\"url\":\"$url\"}}}".toByteArray())
    val properties = LinkedHashMultimap.create<String, Property>()
    properties.put("textures", Property("textures", value))
    val profile = GameProfile(UUID.nameUUIDFromBytes(url.toByteArray()), "Allay", PropertyMap(properties))
    val stack = ItemStack(Items.PLAYER_HEAD)
    stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(profile))
    skull.applyComponentsFromItemStack(stack)
    skull.setChanged()
    level.sendBlockUpdated(pos, skull.blockState, skull.blockState, 3)
  }

  private fun id(entity: Entity) = net.minecraft.world.entity.EntityType.getKey(entity.type).path

  private fun face(facing: Int, blueprint: Blueprint, placement: Placement): Direction? {
    when (facing) {
      0 -> return Direction.DOWN
      1 -> return Direction.UP
    }
    val (dx, dz) = Construction.step(facing) ?: return null
    val (wx, wz) = placement.direction(blueprint, dx, dz)
    return Direction.entries.firstOrNull { it.stepX == wx && it.stepZ == wz && it.stepY == 0 }
  }

  /** A yaw of the schematic as it looks in the world, the build turned and mirrored. */
  private fun yaw(yaw: Float, blueprint: Blueprint, placement: Placement): Float {
    val radians = Math.toRadians(yaw.toDouble())
    val vx = -sin(radians)
    val vz = cos(radians)
    val (ex, ez) = placement.direction(blueprint, 1, 0)
    val (fx, fz) = placement.direction(blueprint, 0, 1)
    val wx = vx * ex + vz * fx
    val wz = vx * ez + vz * fz
    return Math.toDegrees(atan2(-wx, wz)).toFloat()
  }
}
