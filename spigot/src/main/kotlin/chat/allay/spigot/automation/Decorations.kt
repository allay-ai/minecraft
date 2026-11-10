package chat.allay.spigot.automation

import chat.allay.core.building.Blueprint
import chat.allay.core.building.Construction
import chat.allay.core.building.Placement

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.Skull
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.entity.GlowItemFrame
import org.bukkit.entity.Hanging
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Painting
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityPlaceEvent
import org.bukkit.event.hanging.HangingPlaceEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.Material
import java.net.URI
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * What a builder puts up beside blocks: frames, paintings, and armor stands, empty, as a player would place them, and a
 * player head's skin. Server thread.
 */
object Decorations {
  enum class Result { PLACED, THERE, REFUSED }

  /** Puts [entity] up at [block], turned as [placement] stands; THERE when one like it is there already. */
  fun place(player: Player, block: Block, entity: Blueprint.Entity, blueprint: Blueprint, placement: Placement): Result {
    val world = block.world
    val type = when (entity.id) {
      "item_frame" -> ItemFrame::class.java
      "glow_item_frame" -> GlowItemFrame::class.java
      "painting" -> Painting::class.java
      "armor_stand" -> ArmorStand::class.java
      else -> return Result.REFUSED
    }
    val center = block.location.add(0.5, 0.5, 0.5)
    // A restart may land between the spawn and the snapshot: one like it there already counts.
    if (world.getNearbyEntities(center, 0.6, 0.6, 0.6).any { it.type.key.key == entity.id }) return Result.THERE
    val spawned: Entity = try {
      if (type == ArmorStand::class.java) {
        world.spawn(Location(world, block.x + 0.5, block.y.toDouble(), block.z + 0.5, yaw(entity.yaw, blueprint, placement), 0f), ArmorStand::class.java)
      } else {
        val face = face(entity.facing, blueprint, placement) ?: return Result.REFUSED
        world.spawn(block.location, type) { hanging ->
          (hanging as Hanging).setFacingDirection(face, true)
          if (hanging is Painting) entity.variant?.let { Registry.ART.get(NamespacedKey.minecraft(it)) }?.let { hanging.setArt(it, true) }
        }
      }
    } catch (_: IllegalArgumentException) {
      // No wall to hang on, or no room: the spot is in the way.
      return Result.REFUSED
    }
    val item = ItemStack(Material.matchMaterial(entity.id) ?: Material.ITEM_FRAME)
    val event = if (spawned is Hanging) {
      HangingPlaceEvent(spawned, player, block.getRelative(spawned.attachedFace), spawned.facing, EquipmentSlot.HAND, item)
    } else {
      EntityPlaceEvent(spawned, player, block.getRelative(BlockFace.DOWN), BlockFace.UP, EquipmentSlot.HAND)
    }
    Bukkit.getPluginManager().callEvent(event)
    if ((event as org.bukkit.event.Cancellable).isCancelled) {
      spawned.remove()
      return Result.REFUSED
    }
    return Result.PLACED
  }

  /** A player head at [block] with the skin at [url], a link to the game's skin server. */
  fun skin(block: Block, url: String) {
    val skull = block.state as? Skull ?: return
    runCatching {
      val profile = Bukkit.createPlayerProfile(UUID.nameUUIDFromBytes(url.toByteArray()), "Allay")
      val textures = profile.textures
      textures.skin = URI(url).toURL()
      profile.setTextures(textures)
      skull.ownerProfile = profile
      skull.update(false, false)
    }
  }

  private fun face(facing: Int, blueprint: Blueprint, placement: Placement): BlockFace? {
    when (facing) {
      0 -> return BlockFace.DOWN
      1 -> return BlockFace.UP
    }
    val (dx, dz) = Construction.step(facing) ?: return null
    val (wx, wz) = placement.direction(blueprint, dx, dz)
    return BlockFace.entries.firstOrNull { it.modX == wx && it.modZ == wz && it.modY == 0 }
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
