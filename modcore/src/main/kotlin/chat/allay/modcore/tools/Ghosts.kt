package chat.allay.modcore.tools

import chat.allay.modcore.mixin.BlockDisplayAccess
import chat.allay.modcore.mixin.DisplayAccess
import chat.allay.modcore.mixin.ItemDisplayAccess
import chat.allay.modcore.mixin.TextDisplayAccess

import com.mojang.math.Transformation
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.Brightness
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector3f

/**
 * Display entities that exist for one player only. The game has no per-player visibility, so these never enter the
 * world: the server tells the player's client about them and later takes them away. Nobody else sees them, nothing
 * saves them, and a restart forgets them. Marks and drafts are drawn with these, as Bukkit's hidden displays.
 */
object Ghosts {
  /** A display ready to send; [id] takes it away again. */
  class Ghost(val entity: Display) {
    val id get() = entity.id
  }

  fun block(level: ServerLevel, at: Vec3, state: BlockState, translation: Vector3f, scale: Vector3f, glow: Int?): Ghost {
    val display = Display.BlockDisplay(EntityTypes.BLOCK_DISPLAY, level)
    (display as BlockDisplayAccess).`allay$block`(state)
    return ghost(display, at, translation, scale, glow)
  }

  fun item(level: ServerLevel, at: Vec3, stack: ItemStack, scale: Vector3f, glow: Int?): Ghost {
    val display = Display.ItemDisplay(EntityTypes.ITEM_DISPLAY, level)
    (display as ItemDisplayAccess).`allay$item`(stack)
    return ghost(display, at, Vector3f(), scale, glow)
  }

  /** A line of text that turns toward whoever looks and shows through walls. */
  fun text(level: ServerLevel, at: Vec3, text: Component, scale: Vector3f): Ghost {
    val display = Display.TextDisplay(EntityTypes.TEXT_DISPLAY, level)
    (display as TextDisplayAccess).`allay$text`(text)
    (display as TextDisplayAccess).`allay$flags`((Display.TextDisplay.FLAG_SEE_THROUGH.toInt() or Display.TextDisplay.FLAG_SHADOW.toInt()).toByte())
    (display as DisplayAccess).`allay$billboard`(Display.BillboardConstraints.CENTER)
    return ghost(display, at, Vector3f(), scale, null)
  }

  private fun ghost(display: Display, at: Vec3, translation: Vector3f, scale: Vector3f, glow: Int?): Ghost {
    display.setPos(at.x, at.y, at.z)
    val access = display as DisplayAccess
    access.`allay$transformation`(Transformation(translation, Quaternionf(), scale, Quaternionf()))
    access.`allay$brightness`(Brightness(15, 15))
    access.`allay$viewRange`(2f)
    if (glow != null) {
      display.setGlowingTag(true)
      access.`allay$glowColor`(glow)
    }
    return Ghost(display)
  }

  /** Sends [ghosts] to [player]'s client. */
  fun show(player: ServerPlayer, ghosts: Iterable<Ghost>) = ghosts.forEach { ghost ->
    val entity = ghost.entity
    player.connection.send(ClientboundAddEntityPacket(entity.id, entity.uuid, entity.x, entity.y, entity.z, 0f, 0f, entity.type, 0, Vec3.ZERO, 0.0))
    entity.entityData.nonDefaultValues?.let { player.connection.send(ClientboundSetEntityDataPacket(entity.id, it)) }
  }

  /**
   * Sends [ghosts] again once the player's client started a new world and forgot them ([chat.allay.modcore.Hooks.arrived]):
   * those in the level the player is in now. The others wait until the player comes back to theirs, as a hidden entity
   * on Bukkit shows again only in its own world.
   */
  fun resend(player: ServerPlayer, ghosts: Iterable<Ghost>) = show(player, ghosts.filter { it.entity.level() === player.level() })

  /** Takes [ghosts] away from [player]'s client, if they're still there to hear it. */
  fun hide(player: ServerPlayer?, ghosts: Iterable<Ghost>) {
    val ids = ghosts.map { it.id }.toIntArray()
    if (player == null || player.hasDisconnected() || ids.isEmpty()) return
    player.connection.send(ClientboundRemoveEntitiesPacket(*ids))
  }
}
