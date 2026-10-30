package chat.allay.modcore.automation

import chat.allay.core.Locales
import chat.allay.core.Scheduler
import chat.allay.core.send
import chat.allay.core.tasks.Task
import chat.allay.core.tools.Button
import chat.allay.core.tools.ToolError
import chat.allay.modcore.Hooks
import chat.allay.modcore.audience
import chat.allay.modcore.of
import chat.allay.modcore.tools.BlockAt
import chat.allay.modcore.tools.Ghosts
import chat.allay.modcore.tools.Marks
import chat.allay.modcore.tools.at

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.Blocks as GameBlocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import java.util.UUID

/**
 * Picking blocks for an automation agent: while it's on, a left click on a chest (or a furnace, or a brewing stand)
 * picks it instead of hitting it, and it glows; a second click unpicks it. A double chest is picked as one, both
 * halves glowing. A block that can't take part flashes red and the player hears why. Server thread only.
 */
class Picking(private val scheduler: Scheduler, private val locales: Locales, private val automation: Automation) {
  enum class Kind(val wire: String, val accepts: (BlockState) -> Boolean) {
    CHESTS("chests", Blocks::storage),
    FURNACES("furnaces", Blocks::furnace),
    BREWING_STANDS("brewing_stands", { it.`is`(GameBlocks.BREWING_STAND) });

    companion object {
      fun parse(name: String?) = entries.firstOrNull { it.wire.equals(name?.trim(), ignoreCase = true) }
        ?: throw ToolError("kind must be chests, furnaces, or brewing_stands")
    }
  }

  private class Session(val player: ServerPlayer, val task: Task, val kind: Kind, val startedAt: Long) {
    val picked = LinkedHashMap<BlockAt, List<Ghosts.Ghost>>()
    var open = true
  }

  // A player picks for one agent at a time; what they picked stays lit until the agent is done with it.
  private val sessions = HashMap<UUID, Session>()

  /** A left click picks instead of hitting, so nothing breaks while picking, not even in creative. */
  fun start() {
    Hooks.hitBlock += { player, level, pos ->
      val session = sessions[player.uuid]?.takeIf { it.open }
      val block = level.at(pos)
      if (session == null || !session.kind.accepts(block.state)) false
      else {
        toggle(player, session, block)
        true
      }
    }
    Hooks.arrived += { player -> sessions[player.uuid]?.let { Ghosts.resend(player, it.picked.values.flatten()) } }
  }

  /** Starts picking for [task]; with [looking], the block under the crosshair is picked right away. */
  fun start(player: ServerPlayer, task: Task, kind: Kind, looking: Boolean): Int {
    // Another agent's open picking ends here; it hears so instead of waiting for nothing.
    sessions[player.uuid]?.takeIf { it.open && it.task.id != task.id }?.let { automation.post(it.task, "[job] picking: another automation took over picking; pick again later") }
    stop(player, null)
    val session = Session(player, task, kind, System.currentTimeMillis())
    sessions[player.uuid] = session
    if (looking) target(player)?.let { toggle(player, session, it, quiet = true) }
    val lang = locales.of(player)
    automation.say(
      player, task, lang.text("automation.pick.${kind.wire}"),
      listOf(Button.Pick(lang.text("automation.pick.done"), "done")) +
        (if (kind == Kind.CHESTS) listOf(Button.Pick(lang.text("automation.pick.all"), "all")) else emptyList()) +
        listOf(Button.Pick(lang.text("automation.pick.cancel"), "cancel")),
    )
    return session.picked.size
  }

  /** The blocks picked for [task], in the order the player picked them; chests as their roots now. */
  fun picked(player: ServerPlayer, task: Task): List<BlockAt> {
    val session = sessions[player.uuid]?.takeIf { it.task.id == task.id } ?: return emptyList()
    reroot(player, session)
    return session.picked.keys.toList()
  }

  /**
   * Picks chests by their roots as the world has them now: a chest placed beside a picked one since may have made it
   * the right half of a double chest, or two picked chests one. Each double chest stays one pick, both halves lit.
   */
  private fun reroot(player: ServerPlayer, session: Session) {
    if (session.kind != Kind.CHESTS) return
    val rooted = LinkedHashMap<BlockAt, List<Ghosts.Ghost>>()
    session.picked.forEach { (block, lit) ->
      val root = Blocks.root(block)
      when {
        // Two picks joined into one double chest: one pick stays.
        root in rooted -> Ghosts.hide(player, lit)
        root == block && Blocks.halves(root).size == lit.size -> rooted[root] = lit
        else -> {
          Ghosts.hide(player, lit)
          val relit = Blocks.halves(root).map { Marks.box(it, Marks.Hue.GREEN) }
          Ghosts.show(player, relit)
          rooted[root] = relit
        }
      }
    }
    session.picked.clear()
    session.picked.putAll(rooted)
  }

  /** Unlights and forgets the picks of [task], or of anyone when null. */
  fun stop(player: ServerPlayer, task: Task?) {
    val session = sessions[player.uuid] ?: return
    if (task != null && session.task.id != task.id) return
    sessions.remove(player.uuid)
    Ghosts.hide(now(player), session.picked.values.flatten())
  }

  /** The [Done], [All], and [Cancel] buttons. */
  fun button(player: ServerPlayer, action: String) {
    val session = sessions[player.uuid]?.takeIf { it.open } ?: return locales.of(player).send(player.audience, "automation.gone")
    when (action) {
      "cancel" -> {
        stop(player, session.task)
        automation.post(session.task, "[job] picking: the player cancelled picking")
      }
      "all" -> {
        val added = all(player, session)
        if (session.picked.isEmpty()) return locales.of(player).send(player.audience, "automation.pick.none")
        session.open = false
        automation.post(session.task, "[job] picking: the player pressed All: ${session.picked.size} chests (${added} joined the picked ones by being near): ${list(session)}")
      }
      else -> {
        if (session.picked.isEmpty()) return locales.of(player).send(player.audience, "automation.pick.none")
        session.open = false
        automation.post(session.task, "[job] picking: done: ${session.picked.size} ${session.kind.wire} picked: ${list(session)}")
      }
    }
  }

  private fun list(session: Session) = session.picked.keys.joinToString("; ") { "${it.x} ${it.y} ${it.z}" }

  /** Adds every suitable chest reachable from the picked ones across gaps of up to `automation.chests.gap`, or from the one under the crosshair. */
  private fun all(player: ServerPlayer, session: Session): Int {
    val settings = automation.settings()
    reroot(player, session)
    val queue = ArrayDeque(session.picked.keys.ifEmpty { listOfNotNull(target(player)?.takeIf { Blocks.storage(it.state) }?.let(Blocks::root)) })
    val seen = queue.toMutableSet()
    var added = 0
    while (queue.isNotEmpty() && session.picked.size < settings.maxChests) {
      val block = queue.removeFirst()
      if (block !in session.picked) {
        if (Blocks.unsuitable(player, block) != null) continue
        toggle(player, session, block, quiet = true)
        added++
      }
      if (block.corner.distanceTo(player.position()) > settings.pauseDistance) continue
      Blocks.halves(block).forEach { half ->
        Blocks.near(half.level, half.corner, settings.chestGap + 1) { Blocks.storage(it.state) }.map(Blocks::root).forEach { next -> if (seen.add(next)) queue += next }
      }
    }
    return added
  }

  private fun toggle(player: ServerPlayer, session: Session, clicked: BlockAt, quiet: Boolean = false) {
    val lang = locales.of(player)
    if (!session.kind.accepts(clicked.state)) return
    val block = if (session.kind == Kind.CHESTS) Blocks.root(clicked) else clicked
    reroot(player, session)
    session.picked.remove(block)?.let { lit ->
      Ghosts.hide(player, lit)
      if (!quiet) lang.send(player.audience, "automation.pick.removed", "count" to session.picked.size)
      return
    }
    val settings = automation.settings()
    val unfit = if (session.kind == Kind.CHESTS) Blocks.unsuitable(player, block, adding = true) else Blocks.unusable(player, block, settings.radius, settings.lineOfSight)
    if (unfit != null) {
      val flash = Blocks.halves(block).map { Marks.box(it, Marks.Hue.RED) }
      Ghosts.show(player, flash)
      scheduler.later(60L) { Ghosts.hide(now(player), flash) }
      if (!quiet) lang.send(player.audience, "automation.pick.unfit", "reason" to lang.text(unfit.key))
      return
    }
    if (session.kind == Kind.CHESTS && session.picked.size >= settings.maxChests) return lang.send(player.audience, "automation.pick.full", "count" to settings.maxChests)
    val lit = Blocks.halves(block).map { Marks.box(it, Marks.Hue.GREEN) }
    Ghosts.show(player, lit)
    session.picked[block] = lit
    if (!quiet) lang.send(player.audience, "automation.pick.added", "count" to session.picked.size)
  }

  fun tick(now: Long) {
    sessions.values.toList().forEach { session ->
      if (session.open && now - session.startedAt > TIMEOUT_MILLIS) {
        session.open = false
        now(session.player)?.let { locales.of(it).send(it.audience, "automation.pick.timeout") }
        automation.post(session.task, "[job] picking: time ran out with ${session.picked.size} picked: ${list(session)}")
      }
    }
  }

  /** The player as the game has them now: a respawn makes a new one. */
  private fun now(player: ServerPlayer): ServerPlayer? = player.level().server.playerList.getPlayer(player.uuid)

  companion object {
    private const val TIMEOUT_MILLIS = 3 * 60_000L

    /** The block under the crosshair within 6 blocks, as Bukkit's `getTargetBlockExact(6)`: outlines count, fluids don't. */
    fun target(player: ServerPlayer): BlockAt? {
      val hit = player.pick(6.0, 1f, false)
      if (hit.type != HitResult.Type.BLOCK) return null
      return player.level().at((hit as BlockHitResult).blockPos)
    }
  }
}
