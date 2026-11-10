package chat.allay.core.building

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

/**
 * Reads a Sponge `.schem` (v3, as Allay's catalog writes them; v2 too) into a [Blueprint] in one pass: the file inflates
 * straight into one direct buffer of the size its gzip trailer names, and NBT is walked in place, without a tree of
 * objects. Only what a build needs is kept: the size, the palette, the cells, and the block entities' places and ids
 * (a sign's text too). Anything odd is a [Malformed], never a crash.
 */
object Schem {
  class Malformed(message: String) : Exception(message)

  /** The most a file may inflate to: a catalog build of 2 million blocks is far below it. */
  private const val MAX_INFLATED = 256 * 1024 * 1024

  /** The biggest box read: a side past this is no build anyone places. */
  private const val MAX_SIDE = 4096

  /** The entities a build is built with, empty; mobs, minecarts, and the rest stay out. */
  private val KEPT = setOf("item_frame", "glow_item_frame", "painting", "armor_stand")
  private const val MAX_ENTITIES = 4096

  fun read(file: ByteArray): Blueprint {
    val data = inflate(file)
    val reader = Reader(data)
    if (reader.byte() != COMPOUND) throw Malformed("the file isn't NBT")
    reader.string()
    var root = Fields()
    reader.compound { name, kind -> root.take(reader, name, kind, depth = 0) }
    // v3 wraps everything in a Schematic compound; v2 keeps it at the root.
    root.nested["Schematic"]?.let { root = it }
    return build(root, reader)
  }

  /** The gzip'd file in one direct buffer, sized by the trailer and never past [MAX_INFLATED]. */
  private fun inflate(file: ByteArray): ByteBuffer {
    if (file.size < 18 || file[0] != 0x1f.toByte() || file[1] != 0x8b.toByte()) throw Malformed("the file isn't gzip'd")
    val flags = file[3].toInt()
    var at = 10
    if (flags and 4 != 0) at += 2 + ((file[at].toInt() and 0xFF) or ((file[at + 1].toInt() and 0xFF) shl 8))
    if (flags and 8 != 0) while (at < file.size && file[at++] != 0.toByte()) Unit
    if (flags and 16 != 0) while (at < file.size && file[at++] != 0.toByte()) Unit
    if (flags and 2 != 0) at += 2
    val size = ByteBuffer.wrap(file, file.size - 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
    if (size < 0 || size > MAX_INFLATED) throw Malformed("the file inflates past ${MAX_INFLATED / 1024 / 1024} MB")
    val out = ByteBuffer.allocateDirect(size)
    val inflater = Inflater(true)
    try {
      inflater.setInput(file, at, file.size - at - 8)
      while (out.hasRemaining() && !inflater.finished()) {
        if (inflater.inflate(out) == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
      }
      if (out.hasRemaining() || !inflater.finished()) throw Malformed("the file ends before its blocks")
    } finally {
      inflater.end()
    }
    return out.flip()
  }

  private const val END = 0
  private const val BYTE = 1
  private const val SHORT = 2
  private const val INT = 3
  private const val LONG = 4
  private const val FLOAT = 5
  private const val DOUBLE = 6
  private const val BYTE_ARRAY = 7
  private const val STRING = 8
  private const val LIST = 9
  private const val COMPOUND = 10
  private const val INT_ARRAY = 11
  private const val LONG_ARRAY = 12

  /** NBT read in place from [buffer]; big-endian, as NBT is. */
  private class Reader(val buffer: ByteBuffer) {
    fun byte(): Int = need(1).get().toInt()
    fun short(): Int = need(2).short.toInt()
    fun int(): Int = need(4).int
    fun float(): Float = need(4).float
    fun double(): Double = need(8).double

    fun string(): String {
      val size = need(2).short.toInt() and 0xFFFF
      val bytes = ByteArray(size)
      need(size).get(bytes)
      return String(bytes, Charsets.UTF_8)
    }

    fun need(n: Int): ByteBuffer {
      if (n < 0 || buffer.remaining() < n) throw Malformed("the NBT ends midway")
      return buffer
    }

    /** Each named tag of the compound the reader stands in, up to its end. */
    inline fun compound(each: (name: String, kind: Int) -> Unit) {
      while (true) {
        val kind = byte()
        if (kind == END) return
        each(string(), kind)
      }
    }

    /** Skips a tag's payload without reading it into anything. */
    fun skip(kind: Int, depth: Int) {
      if (depth > 64) throw Malformed("the NBT nests too deep")
      when (kind) {
        BYTE -> need(1).position(buffer.position() + 1)
        SHORT -> need(2).position(buffer.position() + 2)
        INT, FLOAT -> need(4).position(buffer.position() + 4)
        LONG, DOUBLE -> need(8).position(buffer.position() + 8)
        BYTE_ARRAY -> int().let { need(it).position(buffer.position() + it) }
        STRING -> (short() and 0xFFFF).let { need(it).position(buffer.position() + it) }
        LIST -> {
          val inner = byte()
          repeat(int()) { skip(inner, depth + 1) }
        }
        COMPOUND -> compound { _, inner -> skip(inner, depth + 1) }
        INT_ARRAY -> int().let { need(it * 4).position(buffer.position() + it * 4) }
        LONG_ARRAY -> int().let { need(it * 8).position(buffer.position() + it * 8) }
        else -> throw Malformed("an unknown NBT tag $kind")
      }
    }
  }

  /** The fields of a schematic compound that a build needs; the rest is skipped where it lies. */
  private class Fields {
    var version = 0
    var dataVersion = 0
    var width = 0
    var height = 0
    var length = 0
    var palette: Map<String, Int>? = null
    /** Where the block data starts in the buffer, and how long it is: read once the size is known. */
    var dataAt = -1
    var dataSize = 0
    val blockEntities = mutableListOf<Blueprint.BlockEntity>()
    val entities = mutableListOf<Blueprint.Entity>()
    var name = ""
    var author = ""
    val nested = mutableMapOf<String, Fields>()

    fun take(reader: Reader, name: String, kind: Int, depth: Int) {
      when {
        name == "Schematic" && kind == COMPOUND -> nested[name] = Fields().also { inner -> reader.compound { n, k -> inner.take(reader, n, k, depth + 1) } }
        name == "Blocks" && kind == COMPOUND -> reader.compound { n, k -> take(reader, n, k, depth + 1) }
        name == "Metadata" && kind == COMPOUND -> reader.compound { n, k ->
          when {
            n == "Name" && k == STRING -> this.name = reader.string().take(100)
            n == "Author" && k == STRING -> author = reader.string().take(100)
            else -> reader.skip(k, depth + 2)
          }
        }
        name == "Version" && kind == INT -> version = reader.int()
        name == "DataVersion" && kind == INT -> dataVersion = reader.int()
        name == "Width" && kind == SHORT -> width = reader.short() and 0xFFFF
        name == "Height" && kind == SHORT -> height = reader.short() and 0xFFFF
        name == "Length" && kind == SHORT -> length = reader.short() and 0xFFFF
        name == "Palette" && kind == COMPOUND -> palette = buildMap { reader.compound { n, k -> if (k == INT) put(n, reader.int()) else reader.skip(k, depth + 1) } }
        (name == "Data" || name == "BlockData") && kind == BYTE_ARRAY -> {
          dataSize = reader.int()
          dataAt = reader.need(dataSize).position()
          reader.buffer.position(dataAt + dataSize)
        }
        name == "BlockEntities" && kind == LIST -> blockEntities(reader, depth)
        name == "Entities" && kind == LIST -> entities(reader, depth)
        else -> reader.skip(kind, depth + 1)
      }
    }

    private fun blockEntities(reader: Reader, depth: Int) {
      val inner = reader.byte()
      val count = reader.int()
      if (inner != COMPOUND) return repeat(count) { reader.skip(inner, depth + 1) }
      repeat(count) {
        var pos: IntArray? = null
        var id = ""
        val text = mutableListOf<String>()
        val texture = arrayOfNulls<String>(1)
        reader.compound { n, k ->
          when {
            n == "Pos" && k == INT_ARRAY && reader.int() == 3 -> pos = intArrayOf(reader.int(), reader.int(), reader.int())
            n == "Pos" && k == INT_ARRAY -> throw Malformed("a block entity's Pos isn't three numbers")
            n == "Id" && k == STRING -> id = reader.string()
            // v3 keeps the entity's own fields under Data, v2 beside Pos and Id: a sign's lines and a head's skin are all that's read.
            n == "Data" && k == COMPOUND -> reader.compound { dn, dk -> field(reader, dn, dk, text, texture, depth + 3) }
            else -> field(reader, n, k, text, texture, depth + 2)
          }
        }
        pos?.let { blockEntities += Blueprint.BlockEntity(it[0], it[1], it[2], id, text, texture[0]) }
      }
    }

    private fun field(reader: Reader, name: String, kind: Int, text: MutableList<String>, texture: Array<String?>, depth: Int) {
      when {
        // A head's owner: `profile` since 1.20.5, `SkullOwner` before; its skin is a `textures` property's value.
        (name == "profile" || name == "SkullOwner") && kind == COMPOUND -> texture[0] = skin(reader, COMPOUND, depth)
        else -> signText(reader, name, kind, text, depth)
      }
    }

    /** The first `value` or `Value` string under a head's profile: its skin, at most 8000 characters. */
    private fun skin(reader: Reader, kind: Int, depth: Int): String? {
      if (depth > 64) throw Malformed("the NBT nests too deep")
      var found: String? = null
      when (kind) {
        COMPOUND -> reader.compound { n, k ->
          if ((n == "value" || n == "Value") && k == STRING) reader.string().takeIf { found == null && it.length <= 8000 }?.let { found = it }
          else skin(reader, k, depth + 1)?.let { if (found == null) found = it }
        }
        LIST -> {
          val inner = reader.byte()
          repeat(reader.int()) { skin(reader, inner, depth + 1)?.let { if (found == null) found = it } }
        }
        else -> reader.skip(kind, depth)
      }
      return found
    }

    /** The entities a build keeps: frames, paintings, and armor stands, at most [MAX_ENTITIES]; the rest is skipped. */
    private fun entities(reader: Reader, depth: Int) {
      val inner = reader.byte()
      val count = reader.int()
      if (inner != COMPOUND) return repeat(count) { reader.skip(inner, depth + 1) }
      repeat(count) {
        var pos: DoubleArray? = null
        var id = ""
        var facing = 2
        var yaw = 0f
        var variant: String? = null
        fun own(n: String, k: Int, d: Int) {
          when {
            (n == "Facing" || n == "facing") && k == BYTE -> facing = reader.byte()
            n == "Rotation" && k == LIST -> {
              val kind = reader.byte()
              val size = reader.int()
              repeat(size) { i -> if (kind == FLOAT) reader.float().let { if (i == 0) yaw = it } else reader.skip(kind, d + 1) }
            }
            (n == "variant" || n == "Motive") && k == STRING -> variant = reader.string().removePrefix("minecraft:").take(64)
            else -> reader.skip(k, d)
          }
        }
        reader.compound { n, k ->
          when {
            n == "Pos" && k == LIST -> {
              val kind = reader.byte()
              val size = reader.int()
              if (kind != DOUBLE || size != 3) throw Malformed("an entity's Pos isn't three numbers")
              pos = doubleArrayOf(reader.double(), reader.double(), reader.double())
            }
            n == "Id" && k == STRING -> id = reader.string().removePrefix("minecraft:")
            n == "Data" && k == COMPOUND -> reader.compound { dn, dk -> own(dn, dk, depth + 3) }
            else -> own(n, k, depth + 2)
          }
        }
        val at = pos
        if (at != null && id in KEPT && this.entities.size < MAX_ENTITIES) this.entities += Blueprint.Entity(at[0], at[1], at[2], id, facing, yaw, variant)
      }
    }

    /** A sign's front lines, as the game keeps them since 1.20; anything else is skipped. */
    private fun signText(reader: Reader, name: String, kind: Int, text: MutableList<String>, depth: Int) {
      if (name != "front_text" || kind != COMPOUND) return reader.skip(kind, depth)
      reader.compound { n, k ->
        if (n == "messages" && k == LIST) {
          val inner = reader.byte()
          repeat(reader.int()) { if (inner == STRING) text += reader.string().take(200) else reader.skip(inner, depth + 1) }
        } else {
          reader.skip(k, depth + 1)
        }
      }
    }
  }

  private fun build(fields: Fields, reader: Reader): Blueprint {
    val (width, height, length) = Triple(fields.width, fields.height, fields.length)
    if (width <= 0 || height <= 0 || length <= 0) throw Malformed("the schematic has no size")
    if (maxOf(width, height, length) > MAX_SIDE) throw Malformed("a side past $MAX_SIDE blocks")
    // Each cell takes a byte at least, so the inflated size bounds the box: no count past an Int.
    if (width.toLong() * height * length > fields.dataSize) throw Malformed("the block data ends before the box")
    if (fields.version !in 2..3) throw Malformed("Sponge schematic version ${fields.version} isn't read")
    val raw = fields.palette ?: throw Malformed("the schematic has no palette")
    if (fields.dataAt < 0) throw Malformed("the schematic has no blocks")
    // Air goes first, so a section nobody wrote reads as air; every other index moves up one if it wasn't.
    val byIndex = arrayOfNulls<String>((raw.values.maxOrNull() ?: 0) + 1)
    raw.forEach { (state, index) -> if (index in byIndex.indices) byIndex[index] = state }
    val palette = mutableListOf("minecraft:air")
    val remap = IntArray(byIndex.size)
    byIndex.forEachIndexed { index, state ->
      remap[index] = when {
        state == null || Blueprint.block(state) in Blueprint.EMPTY -> 0
        else -> palette.size.also { palette += state }
      }
    }
    if (palette.size > Short.MAX_VALUE) throw Malformed("more than ${Short.MAX_VALUE} block states")
    val builder = Blueprint.Builder(width, height, length)
    val data = reader.buffer
    val end = fields.dataAt + fields.dataSize
    var at = fields.dataAt
    val total = width.toLong() * height * length
    var cell = 0L
    val layer = width * length
    while (cell < total) {
      // Varints, x fastest, then z, then y.
      var value = 0
      var shift = 0
      while (true) {
        if (at >= end) throw Malformed("the block data ends before the box")
        val byte = data.get(at++).toInt()
        value = value or ((byte and 0x7F) shl shift)
        if (byte and 0x80 == 0) break
        shift += 7
        if (shift > 28) throw Malformed("a block index past 32 bits")
      }
      if (value !in remap.indices) throw Malformed("a block index past the palette")
      val index = remap[value]
      if (index != 0) {
        val c = cell.toInt()
        val y = c / layer
        val rest = c - y * layer
        builder.set(rest % width, y, rest / width, index)
      }
      cell++
    }
    val blockEntities = fields.blockEntities.filter { it.x in 0 until width && it.y in 0 until height && it.z in 0 until length }
    val entities = fields.entities.filter { it.x >= 0 && it.y >= 0 && it.z >= 0 && it.x < width && it.y < height && it.z < length }
    return builder.build(palette, fields.dataVersion, blockEntities, fields.name, fields.author, entities)
  }
}
