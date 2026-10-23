package chat.allay.core

import chat.allay.core.tools.SpawnProtection
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [SpawnProtection.covers] against the game's rule in 26.3 (`DedicatedServer.isUnderSpawnProtection`). */
class SpawnProtectionTest {
  @Test fun `a square around the spawn, its edge included`() {
    assertTrue(SpawnProtection.covers(operatorsExist = true, operator = false, radius = 16, dx = 16, dz = -16))
    assertFalse(SpawnProtection.covers(operatorsExist = true, operator = false, radius = 16, dx = 17, dz = 0))
    assertFalse(SpawnProtection.covers(operatorsExist = true, operator = false, radius = 16, dx = 0, dz = -17))
  }

  @Test fun `operators build anywhere`() {
    assertFalse(SpawnProtection.covers(operatorsExist = true, operator = true, radius = 16, dx = 0, dz = 0))
  }

  @Test fun `no operators or no radius, no protection`() {
    assertFalse(SpawnProtection.covers(operatorsExist = false, operator = false, radius = 16, dx = 0, dz = 0))
    assertFalse(SpawnProtection.covers(operatorsExist = true, operator = false, radius = 0, dx = 0, dz = 0))
  }
}
