package chat.allay.spigot.tools

import chat.allay.core.*
import chat.allay.core.tools.*
import chat.allay.spigot.Claims

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bukkit.ChatColor
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.block.Block
import org.bukkit.block.Container
import org.bukkit.block.data.BlockData
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import java.util.UUID

/**
 * Marks over blocks that only the asker sees: a glowing box around a block that is there (a chest, a furnace), a
 * glowing ghost of a block to place (a torch on a dark spot), and a label over either. They're display entities,
 * hidden from everyone else from the moment they spawn, never saved with the world, and gone after their time,
 * on `/allay cancel`, or when the player leaves. Glow shows through walls, so what may glow is narrow: never ores,
 * spawners, or vaults, and only containers the player could open ([Claims]). One set per player: new marks replace old.
 */
object Marks : Listener {
  /** The colors the assistant picks from: the glow, the box's glass, and the label's text. */
  enum class Hue(val glow: Color, val glass: Material, val text: ChatColor) {
    YELLOW(Color.fromRGB(0xFFD83D), Material.YELLOW_STAINED_GLASS, ChatColor.YELLOW),
    GREEN(Color.fromRGB(0x5BE35B), Material.LIME_STAINED_GLASS, ChatColor.GREEN),
    RED(Color.fromRGB(0xFF5555), Material.RED_STAINED_GLASS, ChatColor.RED),
    BLUE(Color.fromRGB(0x55AAFF), Material.LIGHT_BLUE_STAINED_GLASS, ChatColor.BLUE),
    AQUA(Color.fromRGB(0x55FFFF), Material.CYAN_STAINED_GLASS, ChatColor.AQUA),
    PURPLE(Color.fromRGB(0xC77DFF), Material.PURPLE_STAINED_GLASS, ChatColor.LIGHT_PURPLE),
    ORANGE(Color.fromRGB(0xFFA040), Material.ORANGE_STAINED_GLASS, ChatColor.GOLD),
    WHITE(Color.fromRGB(0xFFFFFF), Material.WHITE_STAINED_GLASS, ChatColor.WHITE);

    companion object {
      fun parse(name: String?): Hue? = name?.trim()?.ifEmpty { null }?.let { wanted ->
        entries.firstOrNull { it.name.equals(wanted, ignoreCase = true) } ?: throw ToolError("color must be one of ${entries.joinToString { it.name.lowercase() }}")
      }
    }
  }

  /** One mark: a box around [block], or with [ghost], that block drawn where it should go. */
  class Mark(val block: Block, val ghost: BlockData?, val label: String?, val hue: Hue)

  private class Shown(val entities: MutableMap<Block, MutableList<Entity>>, val expiry: BukkitTask)

  /** A hair bigger than the block, so the box doesn't flicker against its faces. */
  private const val EDGE = 0.01f

  // Touched on the server thread only.
  private val shown = HashMap<UUID, Shown>()

  /** Shows [marks] to [player] alone for [seconds], replacing what they had. */
  fun show(plugin: Plugin, player: Player, marks: List<Mark>, seconds: Int) {
    clear(player)
    val entities = LinkedHashMap<Block, MutableList<Entity>>()
    marks.forEach { mark -> entities.getOrPut(mark.block) { mutableListOf() } += draw(plugin, player, mark) }
    val expiry = plugin.server.scheduler.runTaskLater(plugin, Runnable { if (shown[player.uniqueId]?.entities === entities) clear(player) }, seconds * 20L)
    shown[player.uniqueId] = Shown(entities, expiry)
  }

  /** Removes the player's marks; false when they had none. */
  fun clear(player: Player): Boolean {
    val gone = shown.remove(player.uniqueId) ?: return false
    gone.expiry.cancel()
    gone.entities.values.flatten().forEach(Entity::remove)
    return true
  }

  fun clearAll() {
    shown.values.forEach { it.expiry.cancel(); it.entities.values.flatten().forEach(Entity::remove) }
    shown.clear()
  }

  @EventHandler fun onQuit(event: PlayerQuitEvent) {
    clear(event.player)
  }

  /** A ghost is done once the player puts a block there: the mark over that spot goes out. */
  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  fun onPlace(event: BlockPlaceEvent) {
    val marks = shown[event.player.uniqueId] ?: return
    marks.entities.remove(event.block)?.forEach(Entity::remove)
  }

  private fun draw(plugin: Plugin, player: Player, mark: Mark): List<Entity> {
    val block = mark.block
    // The box hugs what's really there (a chest is lower than a block); air and ghosts get a whole block.
    val bounds = block.boundingBox.takeIf { mark.ghost == null && it.volume > 0 }
    val made = mutableListOf<Entity>()
    made += if (mark.ghost != null) ghost(block, mark.ghost, mark.hue) else box(block, mark.hue)
    mark.label?.let { made += label(block, it, mark.hue, (bounds?.maxY ?: (block.y + 1.0)) + 0.35) }
    reveal(plugin, player, made)
    return made
  }

  /** A glowing glass box around what stands at [block], hugging its shape. */
  fun box(block: Block, hue: Hue): Entity {
    val bounds = block.boundingBox.takeIf { it.volume > 0 }
    val min = Vector3f((bounds?.minX?.minus(block.x) ?: 0.0).toFloat(), (bounds?.minY?.minus(block.y) ?: 0.0).toFloat(), (bounds?.minZ?.minus(block.z) ?: 0.0).toFloat())
    val size = Vector3f((bounds?.widthX ?: 1.0).toFloat(), (bounds?.height ?: 1.0).toFloat(), (bounds?.widthZ ?: 1.0).toFloat())
    return block.world.spawn(corner(block), BlockDisplay::class.java) { display ->
      hidden(display, hue)
      display.block = hue.glass.createBlockData()
      display.transformation = Transformation(Vector3f(min.x - EDGE, min.y - EDGE, min.z - EDGE), AxisAngle4f(), Vector3f(size.x + 2 * EDGE, size.y + 2 * EDGE, size.z + 2 * EDGE), AxisAngle4f())
    }
  }

  /**
   * A glowing ghost of [data] at [block], a little smaller than the real thing so it reads as “put it here”.
   * Chests, beds, signs, heads, and the like are drawn by the client's block entity renderer, which a block
   * display doesn't run, so they'd be invisible: those come as their item instead.
   */
  fun ghost(block: Block, data: BlockData, hue: Hue): Entity {
    val world = block.world
    if (rendersAsEntity(data.material)) {
      val item = data.placementMaterial.takeIf { it.isItem } ?: data.material
      return world.spawn(corner(block).add(0.5, 0.45, 0.5), ItemDisplay::class.java) { display ->
        hidden(display, hue)
        display.setItemStack(ItemStack(item))
        display.transformation = Transformation(Vector3f(), AxisAngle4f(), Vector3f(0.8f, 0.8f, 0.8f), AxisAngle4f())
      }
    }
    return world.spawn(corner(block), BlockDisplay::class.java) { display ->
      hidden(display, hue)
      display.block = data
      display.transformation = Transformation(Vector3f(0.1f, 0.1f, 0.1f), AxisAngle4f(), Vector3f(0.8f, 0.8f, 0.8f), AxisAngle4f())
    }
  }

  /** A line of text over a block at height [y], turned toward whoever looks. */
  fun label(block: Block, text: String, hue: Hue, y: Double): Entity =
    block.world.spawn(Location(block.world, block.x + 0.5, y, block.z + 0.5), TextDisplay::class.java) { display ->
      hidden(display, null)
      display.text = hue.text.toString() + text
      display.billboard = Display.Billboard.CENTER
      display.isSeeThrough = true
      display.isShadowed = true
      display.transformation = Transformation(Vector3f(), AxisAngle4f(), Vector3f(0.7f, 0.7f, 0.7f), AxisAngle4f())
    }

  /** Shows entities made here to [player] once they're in the world, so the server's tracker sends them to this player alone. */
  fun reveal(plugin: Plugin, player: Player, entities: Iterable<Entity>) = entities.forEach { player.showEntity(plugin, it) }

  private fun corner(block: Block) = Location(block.world, block.x.toDouble(), block.y.toDouble(), block.z.toDouble())

  /** Blocks the client draws with a block entity renderer: a block display shows nothing for them. */
  private fun rendersAsEntity(type: Material): Boolean =
    type == Material.CHEST || type == Material.TRAPPED_CHEST || type == Material.ENDER_CHEST || type == Material.DECORATED_POT ||
      type == Material.CONDUIT || type == Material.BELL || Tag.SHULKER_BOXES.isTagged(type) || Tag.COPPER_CHESTS.isTagged(type) || Tag.BEDS.isTagged(type) ||
      Tag.BANNERS.isTagged(type) || Tag.ALL_SIGNS.isTagged(type) || type.name.endsWith("_HEAD") || type.name.endsWith("_SKULL")

  /** Before the entity enters the world: nobody sees it by default, it isn't saved with the chunk, and it glows in [hue]. */
  private fun hidden(entity: Display, hue: Hue?) {
    entity.isVisibleByDefault = false
    entity.isPersistent = false
    entity.viewRange = 2f
    entity.brightness = Display.Brightness(15, 15)
    if (hue != null) {
      entity.isGlowing = true
      entity.glowColorOverride = hue.glow
    }
  }

  /** Blocks that never glow for anyone: what Paper's anti-xray hides, and what players hunt underground. */
  fun secret(type: Material): Boolean =
    Tag.ORES.isTagged(type) || type.name.endsWith("_ORE") || type == Material.ANCIENT_DEBRIS ||
      type == Material.SPAWNER || type == Material.TRIAL_SPAWNER || type == Material.VAULT
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
          val x = args.int("x"); val y = args.int("y"); val z = args.int("z")
          if (x == null || y == null || z == null) {
            add(buildJsonObject { put("reason", "each mark needs x, y, and z") })
            return@forEach
          }
          when (val mark = mark(player, args, x, y, z, settings.marksRadius)) {
            is Result.Made -> made += mark.mark
            is Result.Refused -> add(buildJsonObject { put("x", x); put("y", y); put("z", z); put("reason", mark.reason) })
          }
        }
      }
      if (made.isEmpty()) throw ToolError("no mark could be shown: ${refused.joinToString { (it as JsonObject).string("reason") ?: "" }}")
      Marks.show(context.plugin, player, made, seconds)
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

  private fun mark(player: Player, args: JsonObject, x: Int, y: Int, z: Int, radius: Int): Result {
    val world = player.world
    if (y < world.minHeight || y >= world.maxHeight) return Result.Refused("outside the world's height")
    val spot = Location(world, x + 0.5, y + 0.5, z + 0.5)
    if (spot.distance(player.location) > radius) return Result.Refused("farther than $radius blocks from the player")
    val block = world.getBlockAt(x, y, z)
    val hue = try {
      Marks.Hue.parse(args.string("color"))
    } catch (error: ToolError) {
      return Result.Refused(error.message ?: "bad color")
    }
    // Labels can come from container names, which players write: plain text only.
    val label = args.string("label")?.let(ChatColor::stripColor)?.replace("§", "")?.trim()?.take(48)?.ifEmpty { null }
    val ghost = args.string("block")?.trim()?.ifEmpty { null }
    if (ghost != null) {
      val material = Material.matchMaterial(ghost.lowercase().removePrefix("minecraft:").replace(' ', '_'))?.takeIf { it.isBlock && !it.isAir }
        ?: return Result.Refused("no block called $ghost")
      if (!block.isPassable && !block.isLiquid) return Result.Refused("${block.type.key.key} already stands there")
      return Result.Made(Marks.Mark(block, material.createBlockData(), label, hue ?: Marks.Hue.AQUA))
    }
    if (Marks.secret(block.type)) return Result.Refused("${block.type.key.key} is never highlighted: no glowing ores, spawners, or vaults")
    (block.state as? Container)?.let { if (!Claims.mayOpen(player, it)) return Result.Refused("a container the player can't open (someone's claim, or an unopened structure chest)") }
    return Result.Made(Marks.Mark(block, null, label, hue ?: Marks.Hue.YELLOW))
  }

  val all = listOf(marks)
}
