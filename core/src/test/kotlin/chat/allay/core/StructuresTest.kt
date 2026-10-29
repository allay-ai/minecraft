package chat.allay.core

import chat.allay.core.tools.Structures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StructuresTest {
  private val keys = listOf("village_plains", "village_desert", "mansion", "monument", "fortress", "swamp_hut", "desert_pyramid", "ancient_city", "trial_chambers") +
    (1..7).map { "ruined_portal_$it" }

  @Test fun `common names find the game's ids`() {
    assertEquals(listOf("mansion"), Structures.match("woodland mansion", keys))
    assertEquals(listOf("monument"), Structures.match("Ocean Monument", keys))
    assertEquals(listOf("fortress"), Structures.match("nether_fortress", keys))
    assertEquals(listOf("swamp_hut"), Structures.match("witch hut", keys))
    assertEquals(listOf("trial_chambers"), Structures.match("trial chamber", keys))
  }

  @Test fun `a name with variants finds them, up to the cap`() {
    assertEquals(listOf("village_plains", "village_desert"), Structures.match("village", keys))
    assertEquals(Structures.MAX.coerceAtMost(7), Structures.match("ruined_portal", keys).size)
  }

  @Test fun `desert temple and words in an id find it`() {
    assertEquals(listOf("desert_pyramid"), Structures.match("desert temple", keys))
    assertTrue(Structures.match("castle", keys).isEmpty())
    assertTrue("close ones" in Structures.missing("mansions", keys) || "include" in Structures.missing("mansions", keys))
  }
}
