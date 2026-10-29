package chat.allay.spigot.tools

import chat.allay.core.tools.ToolError
import chat.allay.core.tools.TrailBar

import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.UUID

/**
 * Particle trails toward a spot or another player that only the player sees. One per player: a new trail replaces the
 * old one, and a trail ends once the player arrives, so a ten-minute trail doesn't hang over them after they're there.
 */
object Trails {
  /** The looks the assistant picks from; the names are what `point_to` takes as `style`. */
  enum class Style(val particle: Particle) {
    SPARKLE(Particle.END_ROD), FLAME(Particle.FLAME), SOUL(Particle.SOUL_FIRE_FLAME), GLOW(Particle.GLOW), MAGIC(Particle.WITCH),
    SPARK(Particle.ELECTRIC_SPARK), CHERRY(Particle.CHERRY_LEAVES), NOTES(Particle.NOTE), HEARTS(Particle.HEART),
    SNOW(Particle.SNOWFLAKE), TOTEM(Particle.TOTEM_OF_UNDYING), HAPPY(Particle.HAPPY_VILLAGER);

    companion object {
      /** The style the model named; none means the classic sparkle. */
      fun parse(name: String?): Style = name?.trim()?.ifEmpty { null }?.let { wanted ->
        entries.firstOrNull { it.name.equals(wanted, ignoreCase = true) }
          ?: throw ToolError("style must be one of ${entries.joinToString { it.name.lowercase() }}")
      } ?: SPARKLE
    }
  }

  /** Closer than this, horizontally, the player is there. */
  private const val ARRIVED = 3.0
  /** Closer than this, a column of particles marks the spot itself. */
  private const val MARKER = 48.0
  /** How long a followed player may stay off the locator bar before their trail ends. */
  const val HIDDEN_SECONDS = 15

  /** A trail on its way, with the action bar line it keeps up to date and what to put back when it ends. */
  private class Running(val task: BukkitTask, val bar: TrailBar?, val cleanup: () -> Unit)

  // Touched on the server thread only.
  private val running = HashMap<UUID, Running>()

  /**
   * Shows the way to a spot for [seconds]. [arrived] runs once if the player gets there before it ends, with how many
   * blocks the spot lies above them (below when negative; 0 when its height is unknown), and [ended] once it runs out,
   * with the blocks left, or null when the player left its dimension, so neither ending goes unsaid. With [bar], the
   * action bar points the way too, under [label] when the model named the spot. With [surface], the height is unknown
   * and the end column stands on the ground there once the spot is near enough for its chunk to be loaded.
   */
  fun show(
    plugin: Plugin, player: Player, target: Location, seconds: Int, style: Style = Style.SPARKLE, bar: TrailBar? = null, label: String? = null,
    surface: Boolean = false, ended: (Long?) -> Unit = {}, arrived: (Int) -> Unit = {},
  ) {
    val pulses = seconds * 2
    val spot = target.clone()
    var grounded = !surface
    trail(plugin, player, style, bar) { pulse, particle ->
      if (player.world != spot.world) return@trail Pulse.END.also { ended(null) }
      val distance = Math.hypot(spot.x - player.location.x, spot.z - player.location.z)
      if (pulse >= pulses) return@trail Pulse.END.also { ended(Math.round(distance)) }
      if (!grounded && distance <= MARKER) {
        spot.y = spot.world!!.getHighestBlockYAt(spot.blockX, spot.blockZ) + 1.0
        grounded = true
      }
      if (distance <= ARRIVED) {
        player.spawnParticle(particle, spot.clone().add(0.0, 1.0, 0.0), 30, 0.6, 0.8, 0.6, 0.02)
        arrived(if (surface) 0 else Math.round(spot.y - player.location.y).toInt())
        return@trail Pulse.END
      }
      line(player, spot, particle)
      if (distance <= MARKER) column(player, spot, particle)
      bar?.show(player.location.yaw, spot.x - player.location.x, spot.z - player.location.z, Math.round(distance), label)
      Pulse.GO
    }
  }

  /**
   * Shows the way to another player for [seconds], following them as they run. The trail goes dark while they're
   * off the asker's locator bar, as their dot does, and [lost] ends it once they stay off for [HIDDEN_SECONDS] or
   * leave the game. [found] runs once the asker reaches them.
   */
  fun follow(plugin: Plugin, player: Player, target: Player, seconds: Int, style: Style = Style.SPARKLE, bar: TrailBar? = null, found: () -> Unit = {}, lost: () -> Unit = {}) {
    val pulses = seconds * 2
    var hidden = 0
    // The compass goes back to the world spawn after, so it never keeps pointing where they last were.
    val reset = { if (player.isOnline) player.compassTarget = player.world.spawnLocation }
    trail(plugin, player, style, bar, reset) { pulse, particle ->
      if (pulse >= pulses) return@trail Pulse.END
      if (!target.isOnline) {
        lost()
        return@trail Pulse.END
      }
      if (!Locator.shows(player, target)) {
        if (++hidden >= HIDDEN_SECONDS * 2) {
          lost()
          return@trail Pulse.END
        }
        bar?.hidden(target.name)
        return@trail Pulse.GO
      }
      hidden = 0
      val spot = target.location
      // Compasses point only in the overworld; elsewhere the trail alone shows the way.
      player.compassTarget = spot
      if (spot.distance(player.location) <= ARRIVED + 1) {
        player.spawnParticle(particle, spot.clone().add(0.0, 2.2, 0.0), 30, 0.4, 0.4, 0.4, 0.02)
        found()
        return@trail Pulse.END
      }
      line(player, spot.clone().add(0.0, 1.0, 0.0), particle)
      // Over their head, so they stand out in a crowd once they're near.
      if (spot.distance(player.location) <= MARKER) column(player, spot.clone().add(0.0, 2.2, 0.0), particle)
      bar?.show(player.location.yaw, spot.x - player.location.x, spot.z - player.location.z, Math.round(spot.distance(player.location)), target.name)
      Pulse.GO
    }
  }

  private enum class Pulse { GO, END }

  /** Runs one trail: two pulses a second, so the line follows the player as they turn and walk. */
  private fun trail(plugin: Plugin, player: Player, style: Style, bar: TrailBar?, cleanup: () -> Unit = {}, pulse: (Int, Particle) -> Pulse) {
    stop(player)
    // A particle that needs data (a color, a block) would throw; the styles above need none, but a game update may change that.
    val particle = style.particle.takeIf { it.dataType == Void::class.java } ?: Particle.END_ROD
    var count = 0
    lateinit var task: BukkitTask
    task = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
      if (!player.isOnline || pulse(count++, particle) == Pulse.END) end(player, task)
    }, 0L, 10L)
    running[player.uniqueId] = Running(task, bar, cleanup)
  }

  /** Ends the player's trail; false when none was running. */
  fun stop(player: Player): Boolean = running.remove(player.uniqueId)?.also(::close) != null

  private fun end(player: Player, task: BukkitTask) {
    val trail = running[player.uniqueId]?.takeIf { it.task === task }
    if (trail != null) running.remove(player.uniqueId)
    trail?.let(::close) ?: task.cancel()
  }

  private fun close(trail: Running) {
    trail.task.cancel()
    trail.bar?.clear()
    trail.cleanup()
  }

  /** A short line from the player's chest toward the target. */
  private fun line(player: Player, target: Location, particle: Particle) {
    val from = player.eyeLocation.subtract(0.0, 0.4, 0.0)
    val way = target.toVector().subtract(from.toVector())
    if (way.lengthSquared() < 1) return
    // Never past the target: a spot five blocks off gets a five-block line.
    val steps = minOf(24, (way.length() / 0.5).toInt())
    val step = way.normalize().multiply(0.5)
    val point = from.clone()
    repeat(steps) {
      point.add(step)
      player.spawnParticle(particle, point, 1, 0.0, 0.0, 0.0, 0.0)
    }
  }

  /** A pillar over the spot, so the player sees where exactly it is once it's near. */
  private fun column(player: Player, target: Location, particle: Particle) {
    val point = target.clone()
    repeat(12) {
      player.spawnParticle(particle, point, 1, 0.05, 0.0, 0.05, 0.0)
      point.add(0.0, 0.5, 0.0)
    }
  }
}
