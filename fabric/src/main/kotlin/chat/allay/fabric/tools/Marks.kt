package chat.allay.fabric.tools

import chat.allay.core.*
import chat.allay.core.tools.*
import chat.allay.fabric.Claims
import chat.allay.fabric.Hooks
import chat.allay.fabric.Texts
import chat.allay.fabric.automation.Blocks

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.BlockTags
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks as GameBlocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import org.joml.Vector3f
import java.util.UUID

/**
 * Marks over blocks that only the asker sees: a glowing box around a block that is there (a chest, a furnace), a
 * glowing ghost of a block to place (a torch on a dark spot), and a label over either. They're display entities only
 * the asker's client knows ([Ghosts]), never saved with the world, and gone after their time, on `/allay cancel`, or
 * when the player leaves. Glow shows through walls, so what may glow is narrow: never ores, spawners, or vaults, and
 * only containers the player could open ([Claims]). One set per player: new marks replace old.
 */
object Marks {
  /** The colors the assistant picks from: the glow, the box's glass, and the label's text. */
  enum class Hue(val glow: Int, val glass: Block, val text: Char) {
    YELLOW(0xFFD83D, GameBlocks.STAINED_GLASS.yellow(), 'e'),
    GREEN(0x5BE35B, GameBlocks.STAINED_GLASS.lime(), 'a'),
    RED(0xFF5555, GameBlocks.STAINED_GLASS.red(), 'c'),
    BLUE(0x55AAFF, GameBlocks.STAINED_GLASS.lightBlue(), '9'),
    AQUA(0x55FFFF, GameBlocks.STAINED_GLASS.cyan(), 'b'),
    PURPLE(0xC77DFF, GameBlocks.STAINED_GLASS.purple(), 'd'),
    ORANGE(0xFFA040, GameBlocks.STAINED_GLASS.orange(), '6'),
    WHITE(0xFFFFFF, GameBlocks.STAINED_GLASS.white(), 'f');

    companion object {
      fun parse(name: String?): Hue? = name?.trim()?.ifEmpty { null }?.let { wanted ->
        entries.firstOrNull { it.name.equals(wanted, ignoreCase = true) } ?: throw ToolError("color must be one of ${entries.joinToString { it.name.lowercase() }}")
      }
    }
  }

  /** One mark: a box around [block], or with [ghost], that block drawn where it should go. */
  class Mark(val block: BlockAt, val ghost: BlockState?, val label: String?, val hue: Hue)

  private class Shown(val player: ServerPlayer, val ghosts: MutableMap<BlockAt, MutableList<Ghosts.Ghost>>, val expiry: AutoCloseable)

  /** A hair bigger than the block, so the box doesn't flicker against its faces. */
  private const val EDGE = 0.01f

  // Touched on the server thread only.
  private val shown = HashMap<UUID, Shown>()

  fun start() {
    Hooks.left += { clear(it) }
    Hooks.arrived += { player -> shown[player.uuid]?.let { Ghosts.resend(player, it.ghosts.values.flatten()) } }
    // A ghost is done once the player puts a block there: the mark over that spot goes out.
    Hooks.placedBlock += { player, level, pos ->
      shown[player.uuid]?.let { marks -> marks.ghosts.remove(level.at(pos))?.let { Ghosts.hide(player, it) } }
    }
  }

  /** Shows [marks] to [player] alone for [seconds], replacing what they had. */
  fun show(scheduler: Scheduler, player: ServerPlayer, marks: List<Mark>, seconds: Int) {
    clear(player)
    val ghosts = LinkedHashMap<BlockAt, MutableList<Ghosts.Ghost>>()
    marks.forEach { mark -> ghosts.getOrPut(mark.block) { mutableListOf() } += draw(player, mark) }
    val expiry = scheduler.later(seconds * 20L) { if (shown[player.uuid]?.ghosts === ghosts) clear(player) }
    shown[player.uuid] = Shown(player, ghosts, expiry)
  }

  /** Removes the player's marks; false when they had none. */
  fun clear(player: ServerPlayer): Boolean {
    val gone = shown.remove(player.uuid) ?: return false
    gone.expiry.close()
    Ghosts.hide(player.level().server.playerList.getPlayer(player.uuid), gone.ghosts.values.flatten())
    return true
  }

  fun clearAll() {
    shown.values.forEach { gone ->
      gone.expiry.close()
      Ghosts.hide(gone.player.level().server.playerList.getPlayer(gone.player.uuid), gone.ghosts.values.flatten())
    }
    shown.clear()
  }

  private fun draw(player: ServerPlayer, mark: Mark): List<Ghosts.Ghost> {
    val block = mark.block
    // The box hugs what's really there (a chest is lower than a block); air and ghosts get a whole block.
    val bounds = block.bounds.takeIf { mark.ghost == null }
    val made = mutableListOf<Ghosts.Ghost>()
    made += if (mark.ghost != null) ghost(block, mark.ghost, mark.hue) else box(block, mark.hue)
    mark.label?.let { made += label(block, it, mark.hue, (bounds?.maxY ?: (block.y + 1.0)) + 0.35) }
    Ghosts.show(player, made)
    return made
  }

  /** A glowing glass box around what stands at [block], hugging its shape. */
  fun box(block: BlockAt, hue: Hue): Ghosts.Ghost {
    val bounds = block.bounds
    val min = Vector3f((bounds?.minX?.minus(block.x) ?: 0.0).toFloat(), (bounds?.minY?.minus(block.y) ?: 0.0).toFloat(), (bounds?.minZ?.minus(block.z) ?: 0.0).toFloat())
    val size = Vector3f((bounds?.xsize ?: 1.0).toFloat(), (bounds?.ysize ?: 1.0).toFloat(), (bounds?.zsize ?: 1.0).toFloat())
    return Ghosts.block(block.level, block.corner, hue.glass.defaultBlockState(), Vector3f(min.x - EDGE, min.y - EDGE, min.z - EDGE), Vector3f(size.x + 2 * EDGE, size.y + 2 * EDGE, size.z + 2 * EDGE), hue.glow)
  }

  /**
   * A glowing ghost of [state] at [block], a little smaller than the real thing so it reads as “put it here”.
   * Chests, beds, signs, heads, and the like are drawn by the client's block entity renderer, which a block
   * display doesn't run, so they'd be invisible: those come as their item instead.
   */
  fun ghost(block: BlockAt, state: BlockState, hue: Hue): Ghosts.Ghost {
    if (rendersAsEntity(state)) {
      val item = state.block.asItem().takeIf { it != net.minecraft.world.item.Items.AIR } ?: return Ghosts.block(block.level, block.corner, state, Vector3f(0.1f, 0.1f, 0.1f), Vector3f(0.8f, 0.8f, 0.8f), hue.glow)
      return Ghosts.item(block.level, block.corner.add(0.5, 0.45, 0.5), ItemStack(item), Vector3f(0.8f, 0.8f, 0.8f), hue.glow)
    }
    return Ghosts.block(block.level, block.corner, state, Vector3f(0.1f, 0.1f, 0.1f), Vector3f(0.8f, 0.8f, 0.8f), hue.glow)
  }

  /** A line of text over a block at height [y], turned toward whoever looks. */
  fun label(block: BlockAt, text: String, hue: Hue, y: Double): Ghosts.Ghost =
    Ghosts.text(block.level, Vec3(block.x + 0.5, y, block.z + 0.5), Texts.legacy("§${hue.text}$text"), Vector3f(0.7f, 0.7f, 0.7f))

  /** Blocks the client draws with a block entity renderer: a block display shows nothing for them. */
  private fun rendersAsEntity(state: BlockState): Boolean {
    val path = BuiltInRegistries.BLOCK.getKey(state.block).path
    return state.`is`(GameBlocks.CHEST) || state.`is`(GameBlocks.TRAPPED_CHEST) || state.`is`(GameBlocks.ENDER_CHEST) || state.`is`(GameBlocks.DECORATED_POT) ||
      state.`is`(GameBlocks.CONDUIT) || state.`is`(GameBlocks.BELL) || state.`is`(BlockTags.SHULKER_BOXES) || state.`is`(BlockTags.COPPER_CHESTS) || state.`is`(BlockTags.BEDS) ||
      state.`is`(BlockTags.BANNERS) || state.`is`(BlockTags.ALL_SIGNS) || path.endsWith("_head") || path.endsWith("_skull")
  }

  /** Blocks that never glow for anyone: what Paper's anti-xray hides, and what players hunt underground. */
  fun secret(state: BlockState): Boolean {
    val path = BuiltInRegistries.BLOCK.getKey(state.block).path
    return state.`is`(BlockTags.ORES) || path.endsWith("_ore") || state.`is`(GameBlocks.ANCIENT_DEBRIS) ||
      state.`is`(GameBlocks.SPAWNER) || state.`is`(GameBlocks.TRIAL_SPAWNER) || state.`is`(GameBlocks.VAULT)
  }

  /** A block by the name the model wrote, like `torch` or `minecraft:oak_planks`; null when there's none. */
  fun named(name: String): Block? {
    val id = Identifier.tryParse(name.lowercase().trim().replace(' ', '_')) ?: return null
    return BuiltInRegistries.BLOCK.getOptional(id).orElse(null)?.takeUnless { it.defaultBlockState().isAir }
  }
}

/** `show_marks`: what the assistant draws over blocks for the asker. */
object MarkTools {
  private val marks = object : Tool {
    override val name = "show_marks"

    override fun run(context: ToolContext, args: JsonObject): ToolOutput {
      val player = context.requirePlayer()
      val lang = context.lang
      val settings = context.features.config().tools
      if (args.bool("clear") == true) {
        val had = Marks.clear(player)
        return ToolOutput(buildJsonObject { put("cleared", had) }, lang.text("summary.marks_cleared"))
      }
      val list = (args["marks"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: throw ToolError("marks is required: an array of {x, y, z}, or clear: true")
      if (list.isEmpty()) throw ToolError("marks is empty")
      if (list.size > settings.maxMarks) throw ToolError("at most ${settings.maxMarks} marks at once")
      val seconds = args.int("seconds")?.also { if (it !in 1..settings.marksMaxSeconds) throw ToolError("seconds must be 1–${settings.marksMaxSeconds}") }
        ?: settings.marksSeconds
      val made = mutableListOf<Marks.Mark>()
      val refused = buildJsonArray {
        list.forEach { args ->
          val x = args.int("x")
          val y = args.int("y")
          val z = args.int("z")
          if (x == null || y == null || z == null) {
            add(buildJsonObject { put("reason", "each mark needs x, y, and z") })
            return@forEach
          }
          when (val mark = mark(player, args, x, y, z, settings.marksRadius)) {
            is Result.Made -> made += mark.mark
            is Result.Refused -> add(buildJsonObject {
              put("x", x)
              put("y", y)
              put("z", z)
              put("reason", mark.reason)
            })
          }
        }
      }
      if (made.isEmpty()) throw ToolError("no mark could be shown: ${refused.joinToString { (it as JsonObject).string("reason") ?: "" }}")
      Marks.show(context.scheduler, player, made, seconds)
      val result = buildJsonObject {
        put("shown", made.size)
        put("seconds", seconds)
        if (refused.isNotEmpty()) put("refused", refused)
      }
      return ToolOutput(result, lang.text("summary.marks", "count" to made.size))
    }
  }

  private sealed interface Result {
    class Made(val mark: Marks.Mark) : Result
    class Refused(val reason: String) : Result
  }

  private fun mark(player: ServerPlayer, args: JsonObject, x: Int, y: Int, z: Int, radius: Int): Result {
    val level = player.level()
    if (y < level.minY || y > level.maxY) return Result.Refused("outside the world's height")
    if (Vec3(x + 0.5, y + 0.5, z + 0.5).distanceTo(player.position()) > radius) return Result.Refused("farther than $radius blocks from the player")
    val block = level.at(x, y, z)
    val hue = try {
      Marks.Hue.parse(args.string("color"))
    } catch (error: ToolError) {
      return Result.Refused(error.message ?: "bad color")
    }
    // Labels can come from container names, which players write: plain text only.
    val label = args.string("label")?.let(Legacy::strip)?.replace("§", "")?.trim()?.take(48)?.ifEmpty { null }
    val ghost = args.string("block")?.trim()?.ifEmpty { null }
    if (ghost != null) {
      val type = Marks.named(ghost) ?: return Result.Refused("no block called $ghost")
      if (!block.passable && !block.liquid) return Result.Refused("${block.key} already stands there")
      return Result.Made(Marks.Mark(block, type.defaultBlockState(), label, hue ?: Marks.Hue.AQUA))
    }
    if (Marks.secret(block.state)) return Result.Refused("${block.key} is never highlighted: no glowing ores, spawners, or vaults")
    Blocks.container(block)?.let { if (!Claims.mayOpen(player, level, block.pos)) return Result.Refused("a container the player can't open (someone's claim, or an unopened structure chest)") }
    return Result.Made(Marks.Mark(block, null, label, hue ?: Marks.Hue.YELLOW))
  }

  val all = listOf(marks)
}
