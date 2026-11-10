package chat.allay.core

import chat.allay.core.building.Blueprint
import chat.allay.core.building.Blueprints
import chat.allay.core.building.Schem
import java.security.MessageDigest
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
    val blueprints = Blueprints { downloads++; villa }
    assertEquals(10373, blueprints.load(Blueprints.Source("planetminecraft/modern-villa-1201476", url, sha, villa.size)).blocks)
    blueprints.load(Blueprints.Source("planetminecraft/modern-villa-1201476", url, sha, villa.size))
    assertEquals(1, downloads, "kept in memory while it's in use")
    assertFailsWith<Blueprints.Refused> { blueprints.load(Blueprints.Source("x/y", "https://example.com/catalog/x/y/build.schem", sha, villa.size)) }
    assertFailsWith<Blueprints.Refused> { blueprints.load(Blueprints.Source("x/y", url.replace("allay-ai", "someone"), sha, villa.size)) }
    assertFailsWith<Blueprints.Refused> { Blueprints { villa }.load(Blueprints.Source("x/y", url, "0".repeat(64), villa.size)) }
    assertFailsWith<Blueprints.Refused> { Blueprints { villa }.load(Blueprints.Source("x/y", url, sha, villa.size + 1)) }
    // Kept while used, gone after.
    blueprints.forget(System.currentTimeMillis() + 31 * 60_000L)
    blueprints.load(Blueprints.Source("planetminecraft/modern-villa-1201476", url, sha, villa.size))
    assertEquals(2, downloads)
  }
}
