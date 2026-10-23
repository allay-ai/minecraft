package chat.allay.fabric.tools

import chat.allay.core.Scheduler
import chat.allay.core.tools.ToolError

import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.LevelData
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * Particle trails toward a spot or another player that only the player sees. One per player: a new trail replaces the
 * old one, and a trail ends once the player arrives, so a ten-minute trail doesn't hang over them after they're there.
 */
object Trails {
  /** The looks the assistant picks from; the names are what `point_to` takes as `style`. */
  enum class Style(val particle: ParticleOptions) {
    SPARKLE(ParticleTypes.END_ROD), FLAME(ParticleTypes.FLAME), SOUL(ParticleTypes.SOUL_FIRE_FLAME), GLOW(ParticleTypes.GLOW), MAGIC(ParticleTypes.WITCH),
    SPARK(ParticleTypes.ELECTRIC_SPARK), CHERRY(ParticleTypes.CHERRY_LEAVES), NOTES(ParticleTypes.NOTE), HEARTS(ParticleTypes.HEART),
    SNOW(ParticleTypes.SNOWFLAKE), TOTEM(ParticleTypes.TOTEM_OF_UNDYING), HAPPY(ParticleTypes.HAPPY_VILLAGER);

    companion object {
      /** The style the model named; none means the classic sparkle. */
      fun parse(name: String?): Style = name?.trim()?.ifEmpty { null }?.let { wanted ->
        entries.firstOrNull { it.name.equals(wanted, ignoreCase = true) }
          ?: throw ToolError("style must be one of ${entries.joinToString { it.name.lowercase() }}")
      } ?: SPARKLE
    }
  }

  /** A spot in a world to show the way to. */
  data class Target(val dimension: ResourceKey<Level>, val point: Vec3)

  /** Closer than this, horizontally, the player is there. */
  private const val ARRIVED = 3.0
  /** Closer than this, a column of particles marks the spot itself. */
  private const val MARKER = 48.0
  /** How long a followed player may stay off the locator bar before their trail ends. */
  const val HIDDEN_SECONDS = 15

  // Touched on the server thread only.
  private val running = HashMap<UUID, AutoCloseable>()

  /** Shows the way to a spot for [seconds]; [arrived] runs once if the player gets there before it ends. */
  fun show(scheduler: Scheduler, player: ServerPlayer, target: Target, seconds: Int, style: Style = Style.SPARKLE, arrived: () -> Unit = {}) {
    val pulses = seconds * 2
    val id = player.uuid
    trail(scheduler, player, style) { pulse, particle ->
      val now = now(player, id) ?: return@trail Pulse.END
      if (now.level().dimension() != target.dimension || pulse >= pulses) return@trail Pulse.END
      val distance = Math.hypot(target.point.x - now.x, target.point.z - now.z)
      if (distance <= ARRIVED) {
        particles(now, particle, target.point.add(0.0, 1.0, 0.0), 30, 0.6, 0.8, 0.6, 0.02)
        arrived()
        return@trail Pulse.END
      }
      line(now, target.point, particle)
      if (distance <= MARKER) column(now, target.point, particle)
      Pulse.GO
    }
  }

  /**
   * Shows the way to another player for [seconds], following them as they run. The trail goes dark while they're
   * off the asker's locator bar, as their dot does, and [lost] ends it once they stay off for [HIDDEN_SECONDS] or
   * leave the game. [found] runs once the asker reaches them.
   */
  fun follow(scheduler: Scheduler, player: ServerPlayer, target: ServerPlayer, seconds: Int, style: Style = Style.SPARKLE, found: () -> Unit = {}, lost: () -> Unit = {}) {
    val pulses = seconds * 2
    var hidden = 0
    val id = player.uuid
    val followed = target.uuid
    trail(scheduler, player, style) { pulse, particle ->
      val now = now(player, id) ?: return@trail Pulse.END
      if (pulse >= pulses) return@trail Pulse.END
      val them = now.level().server.playerList.getPlayer(followed)
      if (them == null || them.hasDisconnected()) {
        lost()
        return@trail Pulse.END
      }
      if (!Locator.shows(now, them)) {
        if (++hidden >= HIDDEN_SECONDS * 2) {
          lost()
          return@trail Pulse.END
        }
        return@trail Pulse.GO
      }
      hidden = 0
      val spot = them.position()
      // Compasses point only in the overworld; elsewhere the trail alone shows the way.
      compass(now, them.level().dimension(), them.blockPosition())
      if (spot.distanceTo(now.position()) <= ARRIVED + 1) {
        particles(now, particle, spot.add(0.0, 2.2, 0.0), 30, 0.4, 0.4, 0.4, 0.02)
        found()
        return@trail Pulse.END
      }
      line(now, spot.add(0.0, 1.0, 0.0), particle)
      // Over their head, so they stand out in a crowd once they're near.
      if (spot.distanceTo(now.position()) <= MARKER) column(now, spot.add(0.0, 2.2, 0.0), particle)
      Pulse.GO
    }
  }

  /** Points the player's compass at [pos], as Bukkit's `setCompassTarget`: only they see it move. */
  fun compass(player: ServerPlayer, dimension: ResourceKey<Level>, pos: BlockPos) =
    player.connection.send(ClientboundSetDefaultSpawnPositionPacket(LevelData.RespawnData.of(dimension, pos, 0f, 0f)))

  private enum class Pulse { GO, END }

  /** The player as the game has them now (a respawn makes a new one); null once they left. */
  private fun now(player: ServerPlayer, id: UUID): ServerPlayer? = player.level().server.playerList.getPlayer(id)?.takeUnless { it.hasDisconnected() }

  /** Runs one trail: two pulses a second, so the line follows the player as they turn and walk. */
  private fun trail(scheduler: Scheduler, player: ServerPlayer, style: Style, pulse: (Int, ParticleOptions) -> Pulse) {
    stop(player)
    val id = player.uuid
    var count = 0
    lateinit var task: AutoCloseable
    task = scheduler.every(1, 10) {
      if (pulse(count++, style.particle) == Pulse.END) end(id, task)
    }
    running[id] = task
  }

  /** Ends the player's trail; false when none was running. */
  fun stop(player: ServerPlayer): Boolean = running.remove(player.uuid)?.also(AutoCloseable::close) != null

  /** Ends every trail when Allay stops: a singleplayer game keeps this object for the next world. */
  fun clearAll() {
    running.values.toList().forEach(AutoCloseable::close)
    running.clear()
  }

  private fun end(id: UUID, task: AutoCloseable) {
    task.close()
    running.remove(id, task)
  }

  /** Particles only [player] sees, as Bukkit's `Player.spawnParticle`. */
  fun particles(player: ServerPlayer, particle: ParticleOptions, at: Vec3, count: Int, dx: Double, dy: Double, dz: Double, speed: Double) =
    player.connection.send(ClientboundLevelParticlesPacket(particle, false, false, at.x, at.y, at.z, dx.toFloat(), dy.toFloat(), dz.toFloat(), speed.toFloat(), count))

  /** A short line from the player's chest toward the target. */
  private fun line(player: ServerPlayer, target: Vec3, particle: ParticleOptions) {
    val from = player.eyePosition.subtract(0.0, 0.4, 0.0)
    val way = target.subtract(from)
    if (way.lengthSqr() < 1) return
    val step = way.normalize().scale(0.5)
    var point = from
    repeat(24) {
      point = point.add(step)
      particles(player, particle, point, 1, 0.0, 0.0, 0.0, 0.0)
    }
  }

  /** A pillar over the spot, so the player sees where exactly it is once it's near. */
  private fun column(player: ServerPlayer, target: Vec3, particle: ParticleOptions) {
    var point = target
    repeat(12) {
      particles(player, particle, point, 1, 0.05, 0.0, 0.05, 0.0)
      point = point.add(0.0, 0.5, 0.0)
    }
  }
}
