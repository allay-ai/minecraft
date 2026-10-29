package chat.allay.core

import chat.allay.core.tools.ToolLabels
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * The live display, modeled on Claude Code: a spinner with a changing verb, elapsed time, and tokens in the action bar,
 * and one chat line per tool the assistant uses. The platform plays the sounds and draws the particles ([Effects]).
 */
class Animator(private val platform: Platform, config: Config, private val effects: Effects) {
  private val active = ConcurrentHashMap<UUID, AutoCloseable>()
  private val ui = config.ui
  private val spinner = Spinner(ui.spinnerFrames)

  /** Plays [cue] for this player alone, its pitch moved a little at random. */
  private fun play(player: PlayerAudience, cue: Cue, sound: Config.Sound, volume: Float = sound.volume) {
    val shift = if (ui.pitchVariation > 0) Random.nextFloat() * 2 * ui.pitchVariation - ui.pitchVariation else 0f
    effects.play(player, cue, volume, (sound.pitch + shift).coerceIn(0.5f, 2f))
  }

  /** The current action bar text for a run. */
  fun statusLine(run: Run): String {
    val elapsed = run.elapsedMillis
    return StatusFormat(run.lang).line(
      spinner = spinner.frame(elapsed / ui.frameMillis),
      verb = run.verbs.at(elapsed),
      phase = run.phase,
      elapsedSeconds = elapsed / 1000,
      input = run.input.get(),
      output = run.output,
    )
  }

  fun start(run: Run) {
    val player = run.asker.uuid?.let(platform::player) ?: return
    stop(player)

    if (ui.soundsEnabled) play(player, Cue.THINKING, ui.thinkingSound)

    var lastParticles = 0L
    var task: AutoCloseable? = null
    if (ui.actionBarEnabled) status.add(player.uuid)
    task = platform.scheduler.every(0L, 2L) {
      if (!player.online || run.cancelled) {
        task?.close()
        status.remove(player.uuid)
        return@every
      }
      if (ui.actionBarEnabled) player.actionBar(Legacy.color(statusLine(run)))
      val now = System.currentTimeMillis()
      if (ui.particlesEnabled && now - lastParticles >= ui.particleAnimationInterval) {
        lastParticles = now
        effects.particles(player)
      }
    }
    active[player.uuid] = task
  }

  fun toolStarted(run: Run, to: Audience, name: String, args: JsonObject?) {
    if (!ui.toolLines) return
    // Another plugin's tool says whose it is; its own words are for the model.
    val label = run.expansionNames[name]?.let { run.lang.text("tool_lines.expansion", "name" to it) } ?: ToolLabels.label(run.lang, name, args)
    run.lang.send(to, "tool_lines.started", "label" to label)
  }

  /**
   * A failed tool shows its reason only where the language's `tool_lines.failed` asks for `{reason}`: the reasons are
   * written for the model. A refused one (nothing crafted, nothing found) is no success either, and its summary is
   * already in the player's language.
   */
  fun toolFinished(run: Run, to: Audience, summary: String?, failed: Boolean, refused: Boolean = false) {
    if (!ui.toolLines || summary.isNullOrBlank()) return
    when {
      failed -> run.lang.send(to, "tool_lines.failed", "reason" to summary)
      refused -> run.lang.send(to, "tool_lines.refused", "summary" to summary)
      else -> run.lang.send(to, "tool_lines.finished", "summary" to summary)
    }
  }

  fun stop(player: PlayerAudience) {
    active.remove(player.uuid)?.let { animation ->
      animation.close()
      status.remove(player.uuid)
      if (ui.actionBarEnabled) player.actionBar("")
    }
  }

  fun stopAll() {
    active.values.forEach(AutoCloseable::close)
    active.keys.forEach(status::remove)
    active.clear()
  }

  companion object {
    // Shared by every Allay in the game: a singleplayer game starts one per world, and a trail outlives none of them.
    private val status = ConcurrentHashMap.newKeySet<UUID>()

    /** Whether a running answer's status holds [player]'s action bar; anything else there waits for it. */
    fun showing(player: UUID): Boolean = player in status
  }

  fun success(player: PlayerAudience) {
    if (ui.soundsEnabled) play(player, Cue.ANSWER, ui.successSound)
  }

  /** The quiet success sound for a player who reads someone else's public answer. */
  fun publicAnswer(player: PlayerAudience) {
    if (ui.soundsEnabled && ui.publicAnswerSound) play(player, Cue.ANSWER, ui.successSound, ui.successSound.volume * 0.5f)
  }

  fun error(player: PlayerAudience) {
    if (ui.soundsEnabled) play(player, Cue.ERROR, ui.errorSound)
  }
}
