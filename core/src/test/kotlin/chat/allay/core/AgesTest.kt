package chat.allay.core

import java.nio.file.Files
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgesTest {
  private val player = UUID.fromString("00000000-0000-0000-0000-000000000001")

  @Test fun `the age of consent follows the country, and an unknown one is the strictest`() {
    assertEquals(13, AgeGate.threshold("US"))
    assertEquals(14, AgeGate.threshold("es"))
    assertEquals(15, AgeGate.threshold("FR"))
    assertEquals(16, AgeGate.threshold("DE"))
    assertEquals(13, AgeGate.threshold("SE"))
    assertEquals(13, AgeGate.threshold("RU"))
    assertEquals(16, AgeGate.threshold(null))
  }

  @Test fun `a band counts only when its youngest age is old enough`() {
    assertTrue(AgeGate.allows(AgeGate.Band.AGE_13, "US"))
    assertFalse(AgeGate.allows(AgeGate.Band.UNDER_13, "US"))
    assertFalse(AgeGate.allows(AgeGate.Band.AGE_13, "ES"))
    assertTrue(AgeGate.allows(AgeGate.Band.AGE_14_15, "ES"))
    // 14–15 straddles France's 15, so it doesn't let anyone through.
    assertFalse(AgeGate.allows(AgeGate.Band.AGE_14_15, "FR"))
    assertFalse(AgeGate.allows(AgeGate.Band.AGE_14_15, null))
    assertTrue(AgeGate.allows(AgeGate.Band.AGE_16_17, null))
    assertTrue(AgeGate.allows(AgeGate.Band.ADULT, "DE"))
    assertEquals(AgeGate.Band.AGE_14_15, AgeGate.Band.parse("14-15"))
  }

  @Test fun `too young is kept sealed for a year, then asked again`() {
    val log = Logger.getAnonymousLogger()
    val file = Files.createTempDirectory("allay-ages").resolve(Ages.FILE).toFile()
    var now = 1_000L
    fun ages() = Ages(SealedFile(file, { "lic.secret-value" }, "allay ages v1", log), log) { now }
    assertFalse(ages().blocked(player))
    ages().block(player)
    assertFalse(file.readText(Charsets.ISO_8859_1).contains(player.toString()))
    assertTrue(ages().blocked(player))
    now += Ages.YEAR_MS
    assertFalse(ages().blocked(player))
    assertFalse(file.exists())
  }
}
