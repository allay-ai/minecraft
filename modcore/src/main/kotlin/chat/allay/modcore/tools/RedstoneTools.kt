package chat.allay.modcore.tools

import chat.allay.core.*
import chat.allay.core.tools.*
import chat.allay.modcore.Claims

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.core.Direction
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.ComparatorBlock
import net.minecraft.world.level.block.DaylightDetectorBlock
import net.minecraft.world.level.block.DiodeBlock
import net.minecraft.world.level.block.DispenserBlock
import net.minecraft.world.level.block.HopperBlock
import net.minecraft.world.level.block.ObserverBlock
import net.minecraft.world.level.block.RedstoneWallTorchBlock
import net.minecraft.world.level.block.RedstoneWireBlock
import net.minecraft.world.level.block.RepeaterBlock
import net.minecraft.world.level.block.SculkSensorBlock
import net.minecraft.world.level.block.piston.PistonBaseBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.level.block.state.properties.RedstoneSide
import net.minecraft.world.level.material.PushReaction
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import kotlin.math.max

/**
 * `inspect_redstone`: a machine as the game sees it, so the model can say why it doesn't work. The mod reads every
 * component's state and finds the faults the game's rules make certain (a dot of dust, a burned-out torch, a jammed
 * piston, dust that runs past a lamp); the model explains, and `show_marks` can point at them. Only where the player
 * may build ([Claims.mayBuild]): nobody takes apart a neighbor's machine.
 */
object RedstoneTools {
  private const val MAX_RADIUS = 10
  private const val MAX_COMPONENTS = 80
  /** The game's push limit. */
  private const val PUSH_LIMIT = 12
  /** How far the player's crosshair reaches for the machine, as `look_at`. */
  private const val BLOCK_RANGE = 6.0

  private val SIDES = listOf(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)

  private fun id(state: BlockState) = Worlds.key(BuiltInRegistries.BLOCK.getKey(state.block))

  /** Things a signal turns on: a powered neighbor should change them. */
  private fun mechanism(state: BlockState): Boolean {
    val block = state.block
    return block == Blocks.REDSTONE_LAMP || id(state).endsWith("copper_bulb") || block is PistonBaseBlock ||
      block == Blocks.DISPENSER || block == Blocks.DROPPER || block == Blocks.CRAFTER || block == Blocks.HOPPER || block == Blocks.NOTE_BLOCK ||
      block == Blocks.TNT || block == Blocks.BELL || state.`is`(BlockTags.DOORS) || state.`is`(BlockTags.TRAPDOORS) || state.`is`(BlockTags.FENCE_GATES) ||
      block == Blocks.POWERED_RAIL || block == Blocks.ACTIVATOR_RAIL
  }

  /** Whether a block takes part in redstone at all. */
  private fun component(state: BlockState): Boolean {
    val block = state.block
    return block is RedstoneWireBlock || block is RepeaterBlock || block is ComparatorBlock || block is ObserverBlock || block is PistonBaseBlock ||
      block == Blocks.REDSTONE_TORCH || block == Blocks.REDSTONE_WALL_TORCH || block == Blocks.REDSTONE_BLOCK ||
      block == Blocks.LEVER || state.`is`(BlockTags.BUTTONS) || state.`is`(BlockTags.PRESSURE_PLATES) || block == Blocks.TRIPWIRE_HOOK ||
      block == Blocks.TARGET || block is DaylightDetectorBlock || block is SculkSensorBlock || id(state) == "lightning_rod" || mechanism(state)
  }

  private val inspect = object : Tool {
    override val name = "inspect_redstone"

    override fun run(context: ToolContext, args: JsonObject): ToolOutput {
      val player = context.requirePlayer()
      val radius = (args.int("radius") ?: 6).coerceIn(1, MAX_RADIUS)
      // The machine under the crosshair, or the player's own spot when they look at nothing near.
      val center = (if (args.string("around") == "player") null else target(player)) ?: player.level().at(player.blockPosition())
      if (!Claims.mayBuild(player, center.level, center.pos)) {
        throw ToolError("this is someone else's claim where the player may not build: Allay reads redstone only where the player could build it")
      }
      val level = center.level
      val found = mutableListOf<BlockAt>()
      for (dx in -radius..radius) for (dy in -radius..radius) for (dz in -radius..radius) {
        val y = center.y + dy
        if (y < level.minY || y > level.maxY) continue
        val block = level.at(center.x + dx, y, center.z + dz)
        if (component(block.state) && Claims.mayBuild(player, level, block.pos)) found += block
      }
      found.sortBy { it.pos.distSqr(center.pos) }
      val shown = found.take(MAX_COMPONENTS)
      val issues = buildJsonArray { shown.forEach { block -> issue(block)?.let { add(it) } } }
      val result = buildJsonObject {
        put("center", buildJsonObject { put("x", center.x); put("y", center.y); put("z", center.z); put("id", center.key) })
        put("radius", radius)
        put("components", buildJsonArray { shown.forEach { add(buildJsonObject { describe(it) }) } })
        if (found.size > shown.size) put("more_components", found.size - shown.size)
        put("issues", issues)
        put("notes", "repeaters and comparators send their signal toward outputs and take it from input_from; observers watch one side and pulse out the back; received is the strongest signal reaching a block")
      }
      return ToolOutput(result, context.lang.text("summary.redstone", "count" to shown.size, "issues" to issues.size))
    }
  }

  /** The block under the crosshair within reach, fluids looked through, as Bukkit's `getTargetBlockExact`. */
  private fun target(player: ServerPlayer): BlockAt? {
    val hit = player.pick(BLOCK_RANGE, 1f, false)
    if (hit.type != HitResult.Type.BLOCK) return null
    return player.level().at((hit as BlockHitResult).blockPos)
  }

  private fun face(face: Direction) = face.name.lowercase()

  /** The `facing` of a block that has one, as Bukkit's `Directional` reads it. */
  @Suppress("UNCHECKED_CAST")
  private fun facing(state: BlockState): Direction? =
    (state.properties.firstOrNull { it.name == "facing" && it.valueClass == Direction::class.java } as? EnumProperty<Direction>)?.let(state::getValue)

  private fun JsonObjectBuilder.describe(at: BlockAt) {
    val state = at.state
    put("id", at.key)
    put("x", at.x); put("y", at.y); put("z", at.z)
    when (state.block) {
      is RedstoneWireBlock -> {
        put("power", state.getValue(RedstoneWireBlock.POWER))
        put("connects", buildJsonArray {
          SIDES.forEach { side ->
            when (state.getValue(RedstoneWireBlock.PROPERTY_BY_DIRECTION.getValue(side))) {
              RedstoneSide.SIDE -> add(face(side))
              RedstoneSide.UP -> add(face(side) + "_up")
              else -> {}
            }
          }
        })
      }
      is RepeaterBlock -> {
        // The game's facing points at the input; the signal leaves the other way.
        val facing = state.getValue(DiodeBlock.FACING)
        put("input_from", face(facing)); put("outputs", face(facing.opposite))
        put("delay_ticks", state.getValue(RepeaterBlock.DELAY)); put("powered", state.getValue(DiodeBlock.POWERED))
        if (state.getValue(RepeaterBlock.LOCKED)) put("locked", true)
      }
      is ComparatorBlock -> {
        val facing = state.getValue(DiodeBlock.FACING)
        put("input_from", face(facing)); put("outputs", face(facing.opposite))
        put("mode", state.getValue(ComparatorBlock.MODE).name.lowercase()); put("powered", state.getValue(DiodeBlock.POWERED))
      }
      is ObserverBlock -> {
        val facing = state.getValue(ObserverBlock.FACING)
        put("watches", face(facing)); put("outputs", face(facing.opposite)); put("powered", state.getValue(ObserverBlock.POWERED))
      }
      is PistonBaseBlock -> {
        put("pushes", face(state.getValue(PistonBaseBlock.FACING))); put("extended", state.getValue(PistonBaseBlock.EXTENDED)); put("sticky", state.block == Blocks.STICKY_PISTON)
      }
      is DaylightDetectorBlock -> { put("power", state.getValue(DaylightDetectorBlock.POWER)); put("inverted", state.getValue(DaylightDetectorBlock.INVERTED)) }
      is SculkSensorBlock -> { put("phase", state.getValue(SculkSensorBlock.PHASE).name.lowercase()); put("power", state.getValue(SculkSensorBlock.POWER)) }
      is HopperBlock -> { put("facing", face(state.getValue(HopperBlock.FACING))); put("enabled", state.getValue(HopperBlock.ENABLED)) }
      is DispenserBlock -> { put("facing", face(state.getValue(DispenserBlock.FACING))); put("triggered", state.getValue(DispenserBlock.TRIGGERED)) }
      else -> {
        // Bukkit's Lightable, Openable, AnaloguePowerable, Powerable, and Directional are these states.
        val openable = state.hasProperty(BlockStateProperties.OPEN)
        if (state.hasProperty(BlockStateProperties.LIT)) put("lit", state.getValue(BlockStateProperties.LIT))
        if (openable) put("open", state.getValue(BlockStateProperties.OPEN))
        if (state.hasProperty(BlockStateProperties.POWER)) put("power", state.getValue(BlockStateProperties.POWER))
        if (state.hasProperty(BlockStateProperties.POWERED)) put("powered", state.getValue(BlockStateProperties.POWERED))
        if (!openable) facing(state)?.let { put("facing", face(it)) }
      }
    }
    if (mechanism(state)) put("received", power(at))
  }

  /** The one fault the game's rules make certain for this block, if any. */
  private fun issue(at: BlockAt): JsonObject? {
    val state = at.state
    val block = state.block
    fun fault(kind: String, detail: String) = buildJsonObject {
      put("id", at.key); put("x", at.x); put("y", at.y); put("z", at.z); put("issue", kind); put("detail", detail)
    }
    when {
      block is RedstoneWireBlock && SIDES.all { state.getValue(RedstoneWireBlock.PROPERTY_BY_DIRECTION.getValue(it)) == RedstoneSide.NONE } ->
        return fault("dust_dot", "a dot of dust powers only the block under it; right-click it to make a cross, or lead it into what it should power")
      (block == Blocks.REDSTONE_TORCH || block == Blocks.REDSTONE_WALL_TORCH) && !state.getValue(BlockStateProperties.LIT) -> {
        val attached = if (block is RedstoneWallTorchBlock) at.relative(state.getValue(RedstoneWallTorchBlock.FACING).opposite) else at.relative(Direction.DOWN)
        if (!powered(attached)) {
          return fault("torch_burned_out", "off although its block isn't powered: it toggled more than 8 times in 60 game ticks and burned out; it relights on the next block update after a moment")
        }
      }
      block is PistonBaseBlock && !state.getValue(PistonBaseBlock.EXTENDED) && powered(at) -> jam(at, state.getValue(PistonBaseBlock.FACING))?.let { return fault("piston_jammed", it) }
      block is HopperBlock && !state.getValue(HopperBlock.ENABLED) -> return fault("hopper_locked", "a signal locks it: it neither pulls nor passes items while powered")
      block is RepeaterBlock && state.getValue(RepeaterBlock.LOCKED) -> return fault("repeater_locked", "a powered repeater or comparator at its side locks it in its state")
    }
    if (mechanism(state) && power(at) == 0) {
      // Dust beside a lamp or a door that runs past it instead of into it: the commonest mistake there is.
      SIDES.forEach { side ->
        val next = at.relative(side)
        val wire = next.state.takeIf { it.block is RedstoneWireBlock } ?: return@forEach
        if (wire.getValue(RedstoneWireBlock.POWER) > 0 && wire.getValue(RedstoneWireBlock.PROPERTY_BY_DIRECTION.getValue(side.opposite)) == RedstoneSide.NONE) {
          return fault("dust_runs_past", "powered dust at ${next.x} ${next.y} ${next.z} runs past without pointing into it; bend the line into it or put a block under the dust's end")
        }
      }
    }
    return null
  }

  /** Whether a signal reaches the block from any side, as Bukkit's `isBlockIndirectlyPowered`. */
  private fun powered(at: BlockAt) = at.level.hasNeighborSignal(at.pos)

  /**
   * The strongest signal reaching a block, as Bukkit's `getBlockPower` counts it: the power of dust beside it that
   * gives a signal, else 15 for any other signal, else 0.
   */
  private fun power(at: BlockAt): Int {
    var power = 0
    for (direction in Direction.entries) {
      val pos = at.pos.relative(direction)
      if (!at.level.hasSignal(pos, direction)) continue
      val state = at.level.getBlockState(pos)
      if (state.hasProperty(RedstoneWireBlock.POWER)) power = max(power, state.getValue(RedstoneWireBlock.POWER))
      if (power == 15) return power
    }
    return if (power > 0) power else if (powered(at)) 15 else 0
  }

  /** Why a powered piston stays in, by the game's rules for what it can push. */
  private fun jam(piston: BlockAt, facing: Direction): String? {
    var block = piston.relative(facing)
    var count = 0
    while (true) {
      when (block.state.pistonPushReaction) {
        PushReaction.IMMOVEABLE -> return "${block.key} at ${block.x} ${block.y} ${block.z} can't be pushed"
        PushReaction.POPPED, PushReaction.IGNORE_ENTITY -> return null
        else -> if (block.state.isAir || block.liquid) return null
      }
      if (++count > PUSH_LIMIT) return "it would push more than $PUSH_LIMIT blocks"
      block = block.relative(facing)
    }
  }

  val all = listOf(inspect)
}
