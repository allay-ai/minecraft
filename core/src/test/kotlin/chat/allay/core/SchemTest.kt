package chat.allay.core

import chat.allay.core.building.Blueprint
import chat.allay.core.building.Blueprints
import chat.allay.core.building.Schem
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Schematics from Allay's catalog, as the catalog's own tools wrote them, read back block for block. */
class SchemTest {
  private fun file(name: String) = SchemTest::class.java.getResourceAsStream("/schematics/$name")!!.readBytes()

  @Test fun `the starter hut reads back as the catalog built it`() {
    val hut = Schem.read(file("starter-hut.schem"))
    assertEquals(Triple(5, 5, 5), Triple(hut.width, hut.height, hut.length))
    assertEquals(96, hut.blocks)
    assertEquals("minecraft:air", hut.palette[0])
    assertEquals("minecraft:cobblestone", hut.palette[hut.at(0, 0, 0)])
    assertEquals(Blueprint.AIR, hut.at(2, 1, 0), "the doorway")
    assertEquals("minecraft:oak_slab[type=bottom]", hut.palette[hut.at(2, 4, 2)])
    assertEquals("Allay", hut.author)
    val planks = hut.counts()[hut.palette.indexOf("minecraft:oak_planks")]
    assertEquals(45, planks)
  }

  @Test fun `a real build reads whole, every block in its place`() {
    val villa = Schem.read(file("modern-villa.schem"))
    assertEquals(Triple(37, 20, 33), Triple(villa.width, villa.height, villa.length))
    assertEquals(10373, villa.blocks)
    assertTrue(villa.palette.any { it.startsWith("minecraft:sandstone") })
    var seen = 0
    villa.forEach { _, _, _, _ -> seen++ }
    assertEquals(villa.blocks, seen)
  }

  @Test fun `a file that isn't a schematic, or breaks off, is refused, never a crash`() {
    assertFailsWith<Schem.Malformed> { Schem.read(ByteArray(40)) }
    val villa = file("modern-villa.schem")
    assertFailsWith<Schem.Malformed> { Schem.read(villa.copyOf(villa.size / 2)) }
  }

  @Test fun `a build comes only from the catalog, exactly the file it names, and once`() {
    val villa = file("modern-villa.schem")
    val sha = MessageDigest.getInstance("SHA-256").digest(villa).joinToString("") { "%02x".format(it) }
    val url = "https://raw.githubusercontent.com/allay-ai/schematics/${"c".repeat(40)}/catalog/planetminecraft/modern-villa-1201476/build.schem"
    var downloads = 0
    val blueprints = Blueprints(download = { downloads++; villa })
    assertEquals(10373, blueprints.load(Blueprints.Source("planetminecraft/modern-villa-1201476", url, sha, villa.size)).blocks)
    blueprints.load(Blueprints.Source("planetminecraft/modern-villa-1201476", url, sha, villa.size))
    assertEquals(1, downloads, "kept in memory while it's in use")
    assertFailsWith<Blueprints.Refused> { blueprints.load(Blueprints.Source("x/y", "https://example.com/catalog/x/y/build.schem", sha, villa.size)) }
    assertFailsWith<Blueprints.Refused> { blueprints.load(Blueprints.Source("x/y", url.replace("allay-ai", "someone"), sha, villa.size)) }
    assertFailsWith<Blueprints.Refused> { Blueprints(download = { villa }).load(Blueprints.Source("x/y", url, "0".repeat(64), villa.size)) }
    assertFailsWith<Blueprints.Refused> { Blueprints(download = { villa }).load(Blueprints.Source("x/y", url, sha, villa.size + 1)) }
    // Kept while used, gone after.
    blueprints.forget(System.currentTimeMillis() + 31 * 60_000L)
    blueprints.load(Blueprints.Source("planetminecraft/modern-villa-1201476", url, sha, villa.size))
    assertEquals(2, downloads)
  }

  @Test fun `an author's tools go as air, command blocks, barriers, and light`() {
    val file = schem(3, 1, 1, listOf("minecraft:stone", "minecraft:command_block[conditional=false,facing=up]", "minecraft:barrier"), intArrayOf(0, 1, 2), 3953)
    val read = Schem.read(file)
    assertEquals(1, read.blocks)
    assertEquals(Blueprint.AIR, read.at(1, 0, 0))
    assertEquals(Blueprint.AIR, read.at(2, 0, 0))
  }

  @Test fun `a sign reads as its player reads it, from any game that saved it`() {
    // 1.13: four lines apart, each JSON.
    val old = schem(1, 1, 1, listOf("minecraft:oak_sign[rotation=0]"), intArrayOf(0), 1631) { out ->
      out.string("Id", "minecraft:sign")
      out.ints("Pos", 0, 0, 0)
      out.string("Text1", """{"extra":[{"text":"Inn "},{"text":"& Tavern","bold":true}],"text":""}""")
      out.string("Text2", "\"\"")
      out.string("Text3", """{"text":"open"}""")
      out.string("Text4", """{"text":""}""")
    }
    assertEquals(listOf("Inn & Tavern", "", "open", ""), Schem.read(old).blockEntities.single().text)
    // 1.20 to 1.21.4: front_text, still JSON.
    val json = schem(1, 1, 1, listOf("minecraft:oak_sign[rotation=0]"), intArrayOf(0), 3953) { out ->
      out.string("Id", "minecraft:oak_sign")
      out.ints("Pos", 0, 0, 0)
      out.compound("Data") { out.compound("front_text") { out.strings("messages", "\"Inn\"", "\"\"", """{"text":"open"}""", "\"\"") } }
    }
    assertEquals(listOf("Inn", "", "open", ""), Schem.read(json).blockEntities.single().text)
    // Since 1.21.5: text components, a string as it is.
    val plain = schem(1, 1, 1, listOf("minecraft:oak_sign[rotation=0]"), intArrayOf(0), 4440) { out ->
      out.string("Id", "minecraft:oak_sign")
      out.ints("Pos", 0, 0, 0)
      out.compound("Data") { out.compound("front_text") { out.strings("messages", "\"quoted\"", "", "", "") } }
    }
    assertEquals(listOf("\"quoted\"", "", "", ""), Schem.read(plain).blockEntities.single().text)
    // A blank sign holds no text at all.
    val blank = schem(1, 1, 1, listOf("minecraft:oak_sign[rotation=0]"), intArrayOf(0), 4440) { out ->
      out.string("Id", "minecraft:oak_sign")
      out.ints("Pos", 0, 0, 0)
      out.compound("Data") { out.compound("front_text") { out.strings("messages", "", "", "", "") } }
    }
    assertEquals(emptyList(), Schem.read(blank).blockEntities.single().text)
  }

  @Test fun `a box past what a cell holds, or a palette index far past it, is refused`() {
    assertFailsWith<Schem.Malformed> { Schem.read(schem(1, 1024, 1, listOf("minecraft:stone"), IntArray(1024), 3953)) }
    assertFailsWith<Schem.Malformed> { Schem.read(schem(2048, 1, 1, listOf("minecraft:stone"), IntArray(2048), 3953)) }
    assertFailsWith<Schem.Malformed> { Schem.read(schem(1, 1, 1, listOf("minecraft:stone"), intArrayOf(0), 3953, indexOf = { 1_000_000_000 })) }
  }

  /**
   * A Sponge v3 schematic written by hand: [palette] at its indices (or [indexOf]'s), [cells] x fastest, then z, then y,
   * and one block entity whose fields [entity] writes.
   */
  private fun schem(
    width: Int, height: Int, length: Int, palette: List<String>, cells: IntArray, dataVersion: Int,
    indexOf: (Int) -> Int = { it }, entity: ((DataOutputStream) -> Unit)? = null,
  ): ByteArray {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(GZIPOutputStream(bytes)).use { out ->
      out.writeByte(10); out.writeUTF("")
      out.compound("Schematic") {
        out.int("Version", 3)
        out.int("DataVersion", dataVersion)
        out.short("Width", width); out.short("Height", height); out.short("Length", length)
        out.compound("Blocks") {
          out.compound("Palette") { palette.forEachIndexed { i, state -> out.int(state, indexOf(i)) } }
          val data = ByteArrayOutputStream()
          for (cell in cells) {
            var v = cell
            while (v and 0x7F.inv() != 0) { data.write((v and 0x7F) or 0x80); v = v ushr 7 }
            data.write(v)
          }
          out.writeByte(7); out.writeUTF("Data"); out.writeInt(data.size()); out.write(data.toByteArray())
          out.writeByte(9); out.writeUTF("BlockEntities")
          if (entity == null) { out.writeByte(10); out.writeInt(0) } else { out.writeByte(10); out.writeInt(1); entity(out); out.writeByte(0) }
        }
      }
      out.writeByte(0)
    }
    return bytes.toByteArray()
  }

  private fun DataOutputStream.compound(name: String, body: () -> Unit) { writeByte(10); writeUTF(name); body(); writeByte(0) }
  private fun DataOutputStream.int(name: String, value: Int) { writeByte(3); writeUTF(name); writeInt(value) }
  private fun DataOutputStream.short(name: String, value: Int) { writeByte(2); writeUTF(name); writeShort(value) }
  private fun DataOutputStream.string(name: String, value: String) { writeByte(8); writeUTF(name); writeUTF(value) }
  private fun DataOutputStream.ints(name: String, vararg values: Int) { writeByte(11); writeUTF(name); writeInt(values.size); values.forEach(::writeInt) }
  private fun DataOutputStream.strings(name: String, vararg values: String) { writeByte(9); writeUTF(name); writeByte(8); writeInt(values.size); values.forEach(::writeUTF) }
}
