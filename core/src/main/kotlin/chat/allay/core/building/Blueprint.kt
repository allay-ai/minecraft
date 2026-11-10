package chat.allay.core.building

/**
 * A build as a schematic holds it: a box of [width] (x) × [height] (y) × [length] (z) cells, each an index into
 * [palette], block states as the game writes them (`minecraft:oak_stairs[facing=north,half=bottom]`). Cells live in
 * sections of 16³, and a section with nothing in it holds nothing, so a tall, hollow build costs only its blocks.
 * Read-only once built; any thread may read it.
 */
class Blueprint(
  val width: Int,
  val height: Int,
  val length: Int,
  val palette: List<String>,
  private val sections: Array<ShortArray?>,
  /** The game's DataVersion it was saved in: an older one's block names may need the game's own fixes. */
  val dataVersion: Int,
  val blockEntities: List<BlockEntity> = emptyList(),
  /** Item frames, glow item frames, paintings, and armor stands: built empty, as items without what they hold. */
  val entities: List<Entity> = emptyList(),
  /** Its title and author, as the schematic names them. */
  val name: String = "",
  val author: String = "",
) {
  /**
   * A block entity's place in the box and its id; what it holds stays behind (a chest comes empty). [text] is a sign's
   * front, [texture] a player head's skin as the game's `textures` property (base64 of a textures.minecraft.net link).
   */
  data class BlockEntity(val x: Int, val y: Int, val z: Int, val id: String, val text: List<String> = emptyList(), val texture: String? = null)

  /**
   * An entity of the build: [id] without `minecraft:`, its place in the box ([x], [y], [z], the block it hangs in is
   * their floor), [facing] as the game numbers directions (2 north, 3 south, 4 west, 5 east, 0 down, 1 up) for a frame
   * or painting, [yaw] for an armor stand, [variant] a painting's (`kebab`).
   */
  data class Entity(val x: Double, val y: Double, val z: Double, val id: String, val facing: Int = 2, val yaw: Float = 0f, val variant: String? = null)

  /** Which palette entries are nothing to place: air of any kind, and the blank a structure block leaves alone. */
  val empty: BooleanArray = BooleanArray(palette.size) { block(palette[it]) in EMPTY }

  private val columns = (width + 15) shr 4
  private val rows = (length + 15) shr 4

  /** How many cells hold a block. */
  val blocks: Int = run {
    var count = 0
    for (section in sections) if (section != null) for (cell in section) if (!empty[cell.toInt()]) count++
    count
  }

  /** The palette index at a cell; 0 outside the box, which is air in a schematic Allay's catalog wrote. */
  fun at(x: Int, y: Int, z: Int): Int {
    if (x !in 0 until width || y !in 0 until height || z !in 0 until length) return AIR
    val section = sections[section(x shr 4, y shr 4, z shr 4)] ?: return AIR
    return section[((y and 15) shl 8) or ((z and 15) shl 4) or (x and 15)].toInt()
  }

  fun filled(x: Int, y: Int, z: Int) = !empty[at(x, y, z)]

  /** Every block, layer by layer from the bottom, rows from z 0, cells from x 0; air left out. */
  inline fun forEach(action: (x: Int, y: Int, z: Int, index: Int) -> Unit) {
    for (y in 0 until height) for (z in 0 until length) for (x in 0 until width) {
      val index = at(x, y, z)
      if (!empty[index]) action(x, y, z, index)
    }
  }

  /** How many of each palette entry the build holds. */
  fun counts(): IntArray {
    val counts = IntArray(palette.size)
    for (section in sections) if (section != null) for (cell in section) counts[cell.toInt()]++
    return counts
  }

  private fun section(sx: Int, sy: Int, sz: Int) = (sy * rows + sz) * columns + sx

  /** Lays cells out in sections as they're read. */
  class Builder(val width: Int, val height: Int, val length: Int) {
    private val columns = (width + 15) shr 4
    private val rows = (length + 15) shr 4
    private val sections = arrayOfNulls<ShortArray>(columns * rows * ((height + 15) shr 4))

    fun set(x: Int, y: Int, z: Int, index: Int) {
      if (index == AIR) return
      val at = ((y shr 4) * rows + (z shr 4)) * columns + (x shr 4)
      val section = sections[at] ?: ShortArray(4096).also { sections[at] = it }
      section[((y and 15) shl 8) or ((z and 15) shl 4) or (x and 15)] = index.toShort()
    }

    fun build(palette: List<String>, dataVersion: Int, blockEntities: List<BlockEntity>, name: String, author: String, entities: List<Entity> = emptyList()) =
      Blueprint(width, height, length, palette, sections, dataVersion, blockEntities, entities, name, author)
  }

  companion object {
    const val AIR = 0
    val EMPTY = setOf("minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:structure_void")

    /** The block without its states: `minecraft:oak_stairs`. */
    fun block(state: String) = state.substringBefore('[')

    /** A state's properties: `facing=north` and so on. */
    fun states(state: String): Map<String, String> =
      state.substringAfter('[', "").substringBefore(']').split(',').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
  }
}
