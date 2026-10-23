package chat.allay.modcore

import net.minecraft.core.Holder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.world.phys.Vec3

/** Sounds only one player hears, where they stand or from a spot, as Bukkit's `Player.playSound`. */
object Sounds {
  fun play(player: ServerPlayer, sound: SoundEvent, volume: Float, pitch: Float) = play(player, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), volume, pitch)

  fun play(player: ServerPlayer, sound: Holder<SoundEvent>, volume: Float, pitch: Float) = play(player, player.position(), sound, volume, pitch)

  /** A sound only [player] hears, coming from [at], as Bukkit's `Player.playSound(Location, …)`. */
  fun play(player: ServerPlayer, at: Vec3, sound: SoundEvent, volume: Float, pitch: Float) = play(player, at, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), volume, pitch)

  fun play(player: ServerPlayer, at: Vec3, sound: Holder<SoundEvent>, volume: Float, pitch: Float) =
    player.connection.send(ClientboundSoundPacket(sound, SoundSource.MASTER, at.x, at.y, at.z, volume, pitch, player.random.nextLong()))
}
