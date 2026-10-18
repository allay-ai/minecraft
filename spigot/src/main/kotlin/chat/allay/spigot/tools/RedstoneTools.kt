package chat.allay.spigot.tools

import chat.allay.core.*
import chat.allay.core.tools.*
import chat.allay.spigot.Claims

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.PistonMoveReaction
import org.bukkit.block.data.AnaloguePowerable
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.Directional
import org.bukkit.block.data.Lightable
import org.bukkit.block.data.Openable
import org.bukkit.block.data.Powerable
import org.bukkit.block.data.type.Comparator
import org.bukkit.block.data.type.DaylightDetector
import org.bukkit.block.data.type.Dispenser
import org.bukkit.block.data.type.Hopper
import org.bukkit.block.data.type.Observer
import org.bukkit.block.data.type.Piston
import org.bukkit.block.data.type.RedstoneWallTorch
import org.bukkit.block.data.type.RedstoneWire
import org.bukkit.block.data.type.Repeater
import org.bukkit.block.data.type.SculkSensor
import org.bukkit.entity.Player

/**
 * `inspect_redstone`: a machine as the game sees it, so the model can say why it doesn't work. The plugin reads every
 * component's state and finds the faults the game's rules make certain (a dot of dust, a burned-out torch, a jammed
 * piston, dust that runs past a lamp); the model explains, and `show_marks` can point at them. Only where the player
 * may build ([Claims.mayBuild]): nobody takes apart a neighbor's machine.
 */
object RedstoneTools {
  private const val MAX_RADIUS = 10
  private const val MAX_COMPONENTS = 80
  /** The game's push limit. */
  private const val PUSH_LIMIT = 12

  private val SIDES = listOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)

  /** Things a signal turns on: a powered neighbor should change them. */
  private fun mechanism(type: Material): Boolean =
    type == Material.REDSTONE_LAMP || type.name.endsWith("COPPER_BULB") || type == Material.PISTON || type == Material.STICKY_PISTON ||
      type == Material.DISPENSER || type == Material.DROPPER || type == Material.CRAFTER || type == Material.HOPPER || type == Material.NOTE_BLOCK ||
      type == Material.TNT || type == Material.BELL || Tag.DOORS.isTagged(type) || Tag.TRAPDOORS.isTagged(type) || Tag.FENCE_GATES.isTagged(type) ||
      type == Material.POWERED_RAIL || type == Material.ACTIVATOR_RAIL

  /** Whether a block takes part in redstone at all. */
  private fun component(block: Block): Boolean {
    val type = block.type
    val data = block.blockData
    return data is RedstoneWire || data is Repeater || data is Comparator || data is Observer || data is Piston ||
      type == Material.REDSTONE_TORCH || type == Material.REDSTONE_WALL_TORCH || type == Material.REDSTONE_BLOCK ||
      type == Material.LEVER || Tag.BUTTONS.isTagged(type) || Tag.PRESSURE_PLATES.isTagged(type) || type == Material.TRIPWIRE_HOOK ||
      type == Material.TARGET || data is DaylightDetector || data is SculkSensor || type == Material.LIGHTNING_ROD || mechanism(type)
  }

  private val inspect = object : Tool {
    override val name = "inspect_redstone"

    override fun run(context: ToolContext, args: JsonObject): ToolOutput {
      val player = context.requirePlayer()
      val radius = (args.int("radius") ?: 6).coerceIn(1, MAX_RADIUS)
      // The machine under the crosshair, or the player's own spot when they look at nothing near.
      val center = (if (args.string("around") == "player") null else player.getTargetBlockExact(LookTools.BLOCK_RANGE)) ?: player.location.block
      if (!Claims.mayBuild(player, center.location)) {
        throw ToolError("this is someone else's claim where the player may not build: Allay reads redstone only where the player could build it")
      }
      val world = center.world
      val found = mutableListOf<Block>()
      for (dx in -radius..radius) for (dy in -radius..radius) for (dz in -radius..radius) {
        val y = center.y + dy
        if (y < world.minHeight || y >= world.maxHeight) continue
        val block = world.getBlockAt(center.x + dx, y, center.z + dz)
        if (component(block) && Claims.mayBuild(player, block.location)) found += block
      }
      found.sortBy { it.location.distanceSquared(center.location) }
      val shown = found.take(MAX_COMPONENTS)
      val issues = buildJsonArray { shown.forEach { block -> issue(block)?.let { add(it) } } }
      val result = buildJsonObject {
        put("center", buildJsonObject { put("x", center.x); put("y", center.y); put("z", center.z); put("id", center.type.key.key) })
        put("radius", radius)
        put("components", buildJsonArray { shown.forEach { add(buildJsonObject { describe(it) }) } })
        if (found.size > shown.size) put("more_components", found.size - shown.size)
        put("issues", issues)
        put("notes", "repeaters and comparators send their signal toward outputs and take it from input_from; observers watch one side and pulse out the back; received is the strongest signal reaching a block")
      }
      return ToolOutput(result, context.lang.text("summary.redstone", "count" to shown.size, "issues" to issues.size))
    }
  }

  private fun face(face: BlockFace) = face.name.lowercase()

  private fun JsonObjectBuilder.describe(block: Block) {
    val data = block.blockData
    put("id", block.type.key.key)
    put("x", block.x); put("y", block.y); put("z", block.z)
    when (data) {
      is RedstoneWire -> {
        put("power", data.power)
        put("connects", buildJsonArray {
          SIDES.forEach { side ->
            when (data.getFace(side)) {
              RedstoneWire.Connection.SIDE -> add(face(side))
              RedstoneWire.Connection.UP -> add(face(side) + "_up")
              else -> {}
            }
          }
        })
      }
      is Repeater -> {
        // The game's facing points at the input; the signal leaves the other way.
        put("input_from", face(data.facing)); put("outputs", face(data.facing.oppositeFace))
        put("delay_ticks", data.delay); put("powered", data.isPowered)
        if (data.isLocked) put("locked", true)
      }
      is Comparator -> {
        put("input_from", face(data.facing)); put("outputs", face(data.facing.oppositeFace))
        put("mode", data.mode.name.lowercase()); put("powered", data.isPowered)
      }
      is Observer -> {
        put("watches", face(data.facing)); put("outputs", face(data.facing.oppositeFace)); put("powered", data.isPowered)
      }
      is Piston -> {
        put("pushes", face(data.facing)); put("extended", data.isExtended); put("sticky", block.type == Material.STICKY_PISTON)
      }
      is DaylightDetector -> { put("power", data.power); put("inverted", data.isInverted) }
      is SculkSensor -> { put("phase", data.phase.name.lowercase()); put("power", data.power) }
      is Hopper -> { put("facing", face(data.facing)); put("enabled", data.isEnabled) }
      is Dispenser -> { put("facing", face(data.facing)); put("triggered", data.isTriggered) }
      else -> {
        if (data is Lightable) put("lit", data.isLit)
        if (data is Openable) put("open", data.isOpen)
        if (data is AnaloguePowerable) put("power", data.power)
        if (data is Powerable) put("powered", data.isPowered)
        if (data is Directional && data !is Openable) put("facing", face(data.facing))
      }
    }
    if (mechanism(block.type)) put("received", block.blockPower)
  }

  /** The one fault the game's rules make certain for this block, if any. */
  private fun issue(block: Block): JsonObject? {
    val data = block.blockData
    fun fault(kind: String, detail: String) = buildJsonObject {
      put("id", block.type.key.key); put("x", block.x); put("y", block.y); put("z", block.z); put("issue", kind); put("detail", detail)
    }
    when {
      data is RedstoneWire && SIDES.all { data.getFace(it) == RedstoneWire.Connection.NONE } ->
        return fault("dust_dot", "a dot of dust powers only the block under it; right-click it to make a cross, or lead it into what it should power")
      (block.type == Material.REDSTONE_TORCH || block.type == Material.REDSTONE_WALL_TORCH) && data is Lightable && !data.isLit -> {
        val attached = if (data is RedstoneWallTorch) block.getRelative(data.facing.oppositeFace) else block.getRelative(BlockFace.DOWN)
        if (!attached.isBlockIndirectlyPowered) {
          return fault("torch_burned_out", "off although its block isn't powered: it toggled more than 8 times in 60 game ticks and burned out; it relights on the next block update after a moment")
        }
      }
      data is Piston && !data.isExtended && block.isBlockIndirectlyPowered -> jam(block, data.facing)?.let { return fault("piston_jammed", it) }
      data is Hopper && !data.isEnabled -> return fault("hopper_locked", "a signal locks it: it neither pulls nor passes items while powered")
      data is Repeater && data.isLocked -> return fault("repeater_locked", "a powered repeater or comparator at its side locks it in its state")
    }
    if (mechanism(block.type) && block.blockPower == 0) {
      // Dust beside a lamp or a door that runs past it instead of into it: the commonest mistake there is.
      SIDES.forEach { side ->
        val next = block.getRelative(side)
        val wire = next.blockData as? RedstoneWire ?: return@forEach
        if (wire.power > 0 && wire.getFace(side.oppositeFace) == RedstoneWire.Connection.NONE) {
          return fault("dust_runs_past", "powered dust at ${next.x} ${next.y} ${next.z} runs past without pointing into it; bend the line into it or put a block under the dust's end")
        }
      }
    }
    return null
  }

  /** Why a powered piston stays in, by the game's rules for what it can push. */
  private fun jam(piston: Block, facing: BlockFace): String? {
    var block = piston.getRelative(facing)
    var count = 0
    while (true) {
      when (block.pistonMoveReaction) {
        PistonMoveReaction.BLOCK -> return "${block.type.key.key} at ${block.x} ${block.y} ${block.z} can't be pushed"
        PistonMoveReaction.BREAK, PistonMoveReaction.IGNORE -> return null
        else -> if (block.isEmpty || block.isLiquid) return null
      }
      if (++count > PUSH_LIMIT) return "it would push more than $PUSH_LIMIT blocks"
      block = block.getRelative(facing)
    }
  }

  val all = listOf(inspect)
}
