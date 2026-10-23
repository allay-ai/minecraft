package chat.allay.modcore.tools

import chat.allay.core.Lang
import chat.allay.core.Legacy
import chat.allay.core.Locales
import chat.allay.core.Scheduler
import chat.allay.core.tools.DraftBlock
import chat.allay.core.tools.DraftCell
import chat.allay.core.tools.DraftSide
import chat.allay.core.tools.Drafting
import chat.allay.core.tools.ToolError
import chat.allay.modcore.Hooks
import chat.allay.modcore.Sounds
import chat.allay.modcore.Texts
import chat.allay.modcore.of

import com.mojang.brigadier.exceptions.CommandSyntaxException
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.tags.BlockTags
import net.minecraft.world.BossEvent
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.state.BlockState
import java.util.UUID

/**
 * Drafts: a build of up to 20×15×20 blocks ([Drafting]) drawn as glowing ghosts that only its player sees ([Ghosts]),
 * one layer at a time from the bottom. Every half second the mod compares the layer with the world: a block in place
 * goes out, a wrong one gets a red box saying what belongs there, and once the layer is done the next one lights up.
 * A boss bar counts. The draft lives in memory: it goes on `/allay cancel`, when the player leaves, or on a restart.
 *
 * Cells are in the draft's own frame ([DraftCell]); block states are written as if the player faced north (north is
 * forward, east is right) and turn with the draft.
 */
object Drafts {
  class Draft(val owner: UUID, val title: String, val level: ServerLevel, var corner: Triple<Int, Int, Int>, var facing: DraftSide, val cells: MutableMap<DraftCell, DraftBlock<BlockState>>) {
    /** The layer shown, or -1 for all of them at once. */
    var layer = 0
    val layers get() = (cells.keys.maxOfOrNull { it.up } ?: 0) + 1
    val width get() = (cells.keys.maxOfOrNull { it.right } ?: 0) + 1
    val depth get() = (cells.keys.maxOfOrNull { it.forward } ?: 0) + 1
    internal val drawn = HashMap<DraftCell, Pair<State, List<Ghosts.Ghost>>>()
    internal var bar: ServerBossEvent? = null
  }

  enum class State { DONE, GHOST, WRONG }

  // Touched on the server thread only.
  private val drafts = HashMap<UUID, Draft>()
  private var ticker: AutoCloseable? = null
  private lateinit var locales: Locales

  fun start(scheduler: Scheduler, locales: Locales) {
    this.locales = locales
    ticker = scheduler.every(10L, 10L) { drafts.values.toList().forEach(::tick) }
    Hooks.left += { clear(it.uuid) }
    Hooks.arrived += { player -> drafts[player.uuid]?.let { draft -> Ghosts.resend(player, draft.drawn.values.flatMap { it.second }) } }
  }

  fun stop() {
    ticker?.close()
    drafts.values.toList().forEach { clear(it.owner) }
  }

  fun of(player: ServerPlayer): Draft? = drafts[player.uuid]

  fun begin(player: ServerPlayer, draft: Draft) {
    clear(player.uuid)
    drafts[player.uuid] = draft
    draft.layer = firstOpen(draft, 0)
    tick(draft)
  }

  /** Removes the player's draft; false when they had none. */
  fun clear(owner: UUID): Boolean {
    val draft = drafts.remove(owner) ?: return false
    undraw(draft)
    draft.bar?.removeAllPlayers()
    return true
  }

  fun clear(player: ServerPlayer) = clear(player.uuid)

  /** After the draft moved, turned, or changed layer: everything is drawn again. */
  fun redraw(draft: Draft) {
    undraw(draft)
    tick(draft)
  }

  /** The world block of a cell. */
  fun block(draft: Draft, cell: DraftCell): BlockAt {
    val (x, y, z) = Drafting.place(draft.corner, draft.facing, cell)
    return draft.level.at(x, y, z)
  }

  /** The block a cell asks for, turned the way the draft faces. */
  fun turned(draft: Draft, planned: DraftBlock<BlockState>): BlockState? = planned.data?.rotate(rotation(draft.facing))

  /** Whether the world already holds what the cell asks for: the same block, with every state the draft spelled out. */
  fun state(draft: Draft, cell: DraftCell, planned: DraftBlock<BlockState>): State {
    val block = block(draft, cell)
    val expected = turned(draft, planned)
    if (expected == null) return if (open(block)) State.DONE else State.WRONG
    if (block.block != expected.block) return if (open(block)) State.GHOST else State.WRONG
    if (planned.keys.isEmpty()) return State.DONE
    val actual = states(block.state)
    val wanted = states(expected)
    return if (planned.keys.all { actual[it] == wanted[it] }) State.DONE else State.WRONG
  }

  /** What's wrong in a cell, for the label over its red box and for `check`. */
  fun wanted(draft: Draft, planned: DraftBlock<BlockState>): String {
    val expected = turned(draft, planned) ?: return "air"
    val shown = states(expected).filterKeys { it in planned.keys }
    return Worlds.key(BuiltInRegistries.BLOCK.getKey(expected.block)) + if (shown.isEmpty()) "" else shown.entries.joinToString(",", "[", "]") { "${it.key}=${it.value}" }
  }

  /** Cells of a layer, or of all of them for -1. */
  fun cellsOf(draft: Draft, layer: Int) = draft.cells.filterKeys { layer < 0 || it.up == layer }

  /** The first layer from [from] up that still has work, or the last one. */
  fun firstOpen(draft: Draft, from: Int): Int =
    (from until draft.layers).firstOrNull { layer -> cellsOf(draft, layer).any { (cell, planned) -> state(draft, cell, planned) != State.DONE } } ?: (draft.layers - 1)

  private fun tick(draft: Draft) {
    val player = draft.level.server.playerList.getPlayer(draft.owner)
    if (player == null || player.hasDisconnected()) return run { clear(draft.owner) }
    val lang = locales.of(player)
    val cells = cellsOf(draft, draft.layer)
    var done = 0
    cells.forEach { (cell, planned) ->
      val now = state(draft, cell, planned)
      if (now == State.DONE) done++
      val before = draft.drawn[cell]
      if (before?.first == now) return@forEach
      before?.second?.let { Ghosts.hide(player, it) }
      draft.drawn.remove(cell)
      val made = draw(draft, cell, planned, now, lang)
      if (made.isNotEmpty()) Ghosts.show(player, made)
      draft.drawn[cell] = now to made
    }
    val bar = draft.bar ?: ServerBossEvent(UUID.randomUUID(), Component.empty(), BossEvent.BossBarColor.BLUE, BossEvent.BossBarOverlay.NOTCHED_10).also { draft.bar = it }
    // A respawn makes a new ServerPlayer: the bar follows the one that plays now.
    bar.players.filter { it !== player }.forEach(bar::removePlayer)
    if (player !in bar.players) bar.addPlayer(player)
    val total = cells.size.coerceAtLeast(1)
    bar.progress = (done.toDouble() / total).coerceIn(0.0, 1.0).toFloat()
    bar.name = Texts.legacy(Legacy.color(lang.text(if (draft.layer < 0) "drafts.bar_all" else "drafts.bar", "title" to draft.title, "layer" to draft.layer + 1, "layers" to draft.layers, "done" to done, "total" to cells.size)))
    if (done < cells.size || draft.layer < 0) return
    // The layer is built: on to the next one with work left, or the whole draft is done.
    val next = if (draft.layer + 1 < draft.layers) firstOpen(draft, draft.layer + 1) else draft.layers
    if (next >= draft.layers || cellsOf(draft, next).all { (cell, planned) -> state(draft, cell, planned) == State.DONE }) {
      send(player, lang, "drafts.done", "title" to draft.title)
      Sounds.play(player, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.2f)
      clear(draft.owner)
      return
    }
    send(player, lang, "drafts.layer_done", "layer" to draft.layer + 1, "next" to next + 1)
    Sounds.play(player, SoundEvents.EXPERIENCE_ORB_PICKUP, 0.6f, 1.4f)
    draft.layer = next
    undraw(draft)
  }

  private fun draw(draft: Draft, cell: DraftCell, planned: DraftBlock<BlockState>, state: State, lang: Lang): List<Ghosts.Ghost> {
    val block = block(draft, cell)
    return when (state) {
      State.DONE -> emptyList()
      State.GHOST -> {
        val data = turned(draft, planned) ?: return emptyList()
        listOfNotNull(Marks.ghost(block, data, Marks.Hue.AQUA), planned.label?.let { Marks.label(block, it, Marks.Hue.AQUA, block.y + 1.2) })
      }
      State.WRONG -> listOf(
        Marks.box(block, Marks.Hue.RED),
        Marks.label(block, if (planned.data == null) lang.text("drafts.clear") else lang.text("drafts.needs", "block" to wanted(draft, planned)), Marks.Hue.RED, block.y + 1.3),
      )
    }
  }

  private fun undraw(draft: Draft) {
    val player = draft.level.server.playerList.getPlayer(draft.owner)
    Ghosts.hide(player, draft.drawn.values.flatMap { it.second })
    draft.drawn.clear()
  }

  private fun send(player: ServerPlayer, lang: Lang, key: String, vararg values: Pair<String, Any?>) {
    val text = lang.text(key, *values)
    if (text.isNotEmpty()) player.sendSystemMessage(Texts.legacy(Legacy.color(text)))
  }

  /** A spot a block can go without breaking anything first: air, water, tall grass, snow layers, and the like. */
  private fun open(block: BlockAt) = block.state.isAir || block.liquid || block.state.`is`(BlockTags.REPLACEABLE)

  /** Block states as `key=value`, from the game's own text for the block. */
  fun states(state: BlockState): Map<String, String> = Drafting.states(BlockStateParser.serialize(state))

  /** How a draft written facing north turns to face [facing]. */
  fun rotation(facing: DraftSide) = when (facing) {
    DraftSide.EAST -> Rotation.CLOCKWISE_90
    DraftSide.SOUTH -> Rotation.CLOCKWISE_180
    DraftSide.WEST -> Rotation.COUNTERCLOCKWISE_90
    DraftSide.NORTH -> Rotation.NONE
  }

  /** The way the player looks, to the nearest side of the world. */
  fun cardinal(player: ServerPlayer): DraftSide = DraftSide.of(player.yRot)

  /** Reads one palette entry: a block id with states, like `repeater[facing=north,delay=2]`, or `air` for a cell that must stay empty. */
  fun parse(text: String, label: String?): DraftBlock<BlockState> {
    val clean = Drafting.clean(text)
    if (clean == "air") return DraftBlock(null, label, emptySet())
    val data = try {
      BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, "minecraft:$clean", false).blockState()
    } catch (_: CommandSyntaxException) {
      throw ToolError("$text is not a block the game knows; write ids with states like repeater[facing=north,delay=2]")
    }
    if (data.isAir) return DraftBlock(null, label, emptySet())
    // Dust's connections and power follow what's around it; only the states the draft spells out are checked.
    return DraftBlock(data, label, Drafting.spelled(clean))
  }
}
