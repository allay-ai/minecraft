package chat.allay.spigot.automation

import chat.allay.spigot.of
import chat.allay.spigot.code
import chat.allay.spigot.audience

import chat.allay.core.tasks.Task
import chat.allay.core.tools.Button
import chat.allay.core.tools.ToolError
import chat.allay.core.Locales
import chat.allay.spigot.send
import chat.allay.spigot.tools.Marks

import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDamageEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.plugin.Plugin
import java.util.UUID

/**
 * Picking blocks for an automation agent: while it's on, a left click on a chest (or a furnace, or a brewing stand)
 * picks it instead of hitting it, and it glows; a second click unpicks it. A double chest is picked as one, both
 * halves glowing. A block that can't take part flashes red and the player hears why. Server thread only.
 */
class Picking(private val plugin: Plugin, private val locales: Locales, private val automation: Automation) : Listener {
  enum class Kind(val wire: String, val accepts: (Material) -> Boolean) {
    CHESTS("chests", Blocks::storage),
    FURNACES("furnaces", Blocks::furnace),
    BREWING_STANDS("brewing_stands", { it == Material.BREWING_STAND });

    companion object {
      fun parse(name: String?) = entries.firstOrNull { it.wire.equals(name?.trim(), ignoreCase = true) }
        ?: throw ToolError("kind must be chests, furnaces, or brewing_stands")
    }
  }

  private class Session(val task: Task, val kind: Kind, val startedAt: Long) {
    val picked = LinkedHashMap<Block, List<Entity>>()
    var open = true
  }

  // A player picks for one agent at a time; what they picked stays lit until the agent is done with it.
  private val sessions = HashMap<UUID, Session>()

  /** Starts picking for [task]; with [looking], the block under the crosshair is picked right away. */
  fun start(player: Player, task: Task, kind: Kind, looking: Boolean): Int {
    // Another agent's open picking ends here; it hears so instead of waiting for nothing.
    sessions[player.uniqueId]?.takeIf { it.open && it.task.id != task.id }?.let { automation.post(it.task, "[job] picking: another automation took over picking; pick again later") }
    stop(player, null)
    val session = Session(task, kind, System.currentTimeMillis())
    sessions[player.uniqueId] = session
    if (looking) player.getTargetBlockExact(6)?.let { toggle(player, session, it, quiet = true) }
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
  fun picked(player: Player, task: Task): List<Block> {
    val session = sessions[player.uniqueId]?.takeIf { it.task.id == task.id } ?: return emptyList()
    reroot(player, session)
    return session.picked.keys.toList()
  }

  /**
   * Picks chests by their roots as the world has them now: a chest placed beside a picked one since may have made it
   * the right half of a double chest, or two picked chests one. Each double chest stays one pick, both halves lit.
   */
  private fun reroot(player: Player, session: Session) {
    if (session.kind != Kind.CHESTS) return
    val rooted = LinkedHashMap<Block, List<Entity>>()
    session.picked.forEach { (block, lit) ->
      val root = Blocks.root(block)
      when {
        // Two picks joined into one double chest: one pick stays.
        root in rooted -> lit.forEach(Entity::remove)
        root == block && Blocks.halves(root).size == lit.size -> rooted[root] = lit
        else -> {
          lit.forEach(Entity::remove)
          val relit = Blocks.halves(root).map { Marks.box(it, Marks.Hue.GREEN) }
          Marks.reveal(plugin, player, relit)
          rooted[root] = relit
        }
      }
    }
    session.picked.clear()
    session.picked.putAll(rooted)
  }

  /** Unlights and forgets the picks of [task], or of anyone when null. */
  fun stop(player: Player, task: Task?) {
    val session = sessions[player.uniqueId] ?: return
    if (task != null && session.task.id != task.id) return
    sessions.remove(player.uniqueId)
    session.picked.values.flatten().forEach(Entity::remove)
  }

  /** The [Done], [All], and [Cancel] buttons. */
  fun button(player: Player, action: String) {
    val session = sessions[player.uniqueId]?.takeIf { it.open } ?: return locales.of(player).send(player, "automation.gone")
    when (action) {
      "cancel" -> {
        stop(player, session.task)
        automation.post(session.task, "[job] picking: the player cancelled picking")
      }
      "all" -> {
        val added = all(player, session)
        if (session.picked.isEmpty()) return locales.of(player).send(player, "automation.pick.none")
        session.open = false
        automation.post(session.task, "[job] picking: the player pressed All: ${session.picked.size} chests (${added} joined the picked ones by being near): ${list(session)}")
      }
      else -> {
        if (session.picked.isEmpty()) return locales.of(player).send(player, "automation.pick.none")
        session.open = false
        automation.post(session.task, "[job] picking: done: ${session.picked.size} ${session.kind.wire} picked: ${list(session)}")
      }
    }
  }

  private fun list(session: Session) = session.picked.keys.joinToString("; ") { "${it.x} ${it.y} ${it.z}" }

  /** Adds every suitable chest reachable from the picked ones across gaps of up to `automation.chests.gap`, or from the one under the crosshair. */
  private fun all(player: Player, session: Session): Int {
    val settings = automation.settings()
    reroot(player, session)
    val queue = ArrayDeque(session.picked.keys.ifEmpty { listOfNotNull(player.getTargetBlockExact(6)?.takeIf { Blocks.storage(it.type) }?.let(Blocks::root)) })
    val seen = queue.toMutableSet()
    var added = 0
    while (queue.isNotEmpty() && session.picked.size < settings.maxChests) {
      val block = queue.removeFirst()
      if (block !in session.picked) {
        if (Blocks.unsuitable(player, block) != null) continue
        toggle(player, session, block, quiet = true)
        added++
      }
      if (block.location.distance(player.location) > settings.pauseDistance) continue
      Blocks.halves(block).forEach { half ->
        Blocks.near(half.location, settings.chestGap + 1) { Blocks.storage(it.type) }.map(Blocks::root).forEach { next -> if (seen.add(next)) queue += next }
      }
    }
    return added
  }

  private fun toggle(player: Player, session: Session, clicked: Block, quiet: Boolean = false) {
    val lang = locales.of(player)
    if (!session.kind.accepts(clicked.type)) return
    val block = if (session.kind == Kind.CHESTS) Blocks.root(clicked) else clicked
    reroot(player, session)
    session.picked.remove(block)?.let { lit ->
      lit.forEach(Entity::remove)
      if (!quiet) lang.send(player, "automation.pick.removed", "count" to session.picked.size)
      return
    }
    val settings = automation.settings()
    val unfit = if (session.kind == Kind.CHESTS) Blocks.unsuitable(player, block, adding = true) else Blocks.unusable(player, block, settings.radius, settings.lineOfSight)
    if (unfit != null) {
      val flash = Blocks.halves(block).map { Marks.box(it, Marks.Hue.RED) }
      Marks.reveal(plugin, player, flash)
      plugin.server.scheduler.runTaskLater(plugin, Runnable { flash.forEach(Entity::remove) }, 60L)
      if (!quiet) lang.send(player, "automation.pick.unfit", "reason" to lang.text(unfit.key))
      return
    }
    if (session.kind == Kind.CHESTS && session.picked.size >= settings.maxChests) return lang.send(player, "automation.pick.full", "count" to settings.maxChests)
    val lit = Blocks.halves(block).map { Marks.box(it, Marks.Hue.GREEN) }
    Marks.reveal(plugin, player, lit)
    session.picked[block] = lit
    if (!quiet) lang.send(player, "automation.pick.added", "count" to session.picked.size)
  }

  fun tick(now: Long) {
    sessions.entries.toList().forEach { (id, session) ->
      if (session.open && now - session.startedAt > TIMEOUT_MILLIS) {
        session.open = false
        plugin.server.getPlayer(id)?.let { locales.of(it).send(it, "automation.pick.timeout") }
        automation.post(session.task, "[job] picking: time ran out with ${session.picked.size} picked: ${list(session)}")
      }
    }
  }

  /** A left click picks instead of hitting, so nothing breaks while picking. */
  @EventHandler(priority = EventPriority.LOWEST)
  fun onClick(event: PlayerInteractEvent) {
    if (event.action != Action.LEFT_CLICK_BLOCK) return
    val session = sessions[event.player.uniqueId]?.takeIf { it.open } ?: return
    val block = event.clickedBlock ?: return
    if (!session.kind.accepts(block.type)) return
    event.isCancelled = true
    toggle(event.player, session, block)
  }

  @EventHandler(priority = EventPriority.LOWEST)
  fun onDamage(event: BlockDamageEvent) {
    val session = sessions[event.player.uniqueId]?.takeIf { it.open } ?: return
    if (session.kind.accepts(event.block.type)) event.isCancelled = true
  }

  /** Creative players break blocks on the first click; while picking they don't. */
  @EventHandler(priority = EventPriority.LOWEST)
  fun onBreak(event: BlockBreakEvent) {
    val session = sessions[event.player.uniqueId]?.takeIf { it.open } ?: return
    if (session.kind.accepts(event.block.type)) event.isCancelled = true
  }

  private companion object {
    const val TIMEOUT_MILLIS = 3 * 60_000L
  }
}
