package chat.allay.fabric

import net.minecraft.core.Holder
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource

/** Sounds only one player hears, where they stand, as Bukkit's `Player.playSound`. */
object Sounds {
  fun play(player: ServerPlayer, sound: SoundEvent, volume: Float, pitch: Float) = play(player, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound), volume, pitch)

  fun play(player: ServerPlayer, sound: Holder<SoundEvent>, volume: Float, pitch: Float) =
    player.connection.send(ClientboundSoundPacket(sound, SoundSource.MASTER, player.x, player.y, player.z, volume, pitch, player.random.nextLong()))
}
