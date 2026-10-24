package chat.allay.core

import chat.allay.core.tools.AnvilTargets
import chat.allay.core.tools.AnvilTargets.Option
import chat.allay.core.tools.AnvilTargets.Pick
import kotlin.test.Test
import kotlin.test.assertEquals

class AnvilTargetsTest {
  @Test fun `the item in hand wins when the books improve it`() {
    assertEquals(Pick.One(2), AnvilTargets.pick(listOf(Option(2, "diamond_sword", 5), Option(14, "bow", 9)), hand = 2))
  }

  @Test fun `an empty hand leaves the only item the books improve`() {
    assertEquals(Pick.One(14), AnvilTargets.pick(listOf(Option(3, "stick", 0), Option(14, "diamond_sword", 5)), hand = 0))
  }

  @Test fun `several items ask the player, best first`() {
    val pick = AnvilTargets.pick(listOf(Option(9, "bow", 2), Option(14, "diamond_sword", 5), Option(38, "diamond_chestplate", 5)), hand = 0)
    assertEquals(Pick.Choose(listOf(Option(14, "diamond_sword", 5), Option(38, "diamond_chestplate", 5), Option(9, "bow", 2))), pick)
  }

  @Test fun `nothing to improve says so`() {
    assertEquals(Pick.None, AnvilTargets.pick(listOf(Option(1, "stick", 0)), hand = 0))
    assertEquals(Pick.None, AnvilTargets.named(emptyList(), hand = 0))
  }

  @Test fun `a named item takes the copy that gains most`() {
    assertEquals(Pick.One(20), AnvilTargets.named(listOf(Option(10, "diamond_sword", 1), Option(20, "diamond_sword", 4)), hand = 0))
    assertEquals(Pick.Choose(listOf(Option(10, "bow", 3), Option(20, "bow", 3))), AnvilTargets.named(listOf(Option(10, "bow", 3), Option(20, "bow", 3)), hand = 0))
  }

  @Test fun `slots read as a player looks for them`() {
    assertEquals("hand", AnvilTargets.label(4, hand = 4))
    assertEquals("hotbar 3", AnvilTargets.label(2, hand = 4))
    assertEquals("inventory row 1 column 6", AnvilTargets.label(14, hand = 4))
    assertEquals("helmet", AnvilTargets.label(39, hand = 4))
    assertEquals("offhand", AnvilTargets.label(40, hand = 4))
  }

  @Test fun `gain counts only new levels`() {
    assertEquals(4, AnvilTargets.gain(mapOf("sharpness" to 3, "unbreaking" to 3), mapOf("sharpness" to 5, "unbreaking" to 3, "mending" to 1, "looting" to 1)))
  }
}

class AnvilMatchingTest {
  private val have = setOf("diamond_sword", "bow", "iron_pickaxe")

  @Test fun `an exact id, a part of one, or a name in any language finds the player's item`() {
    assertEquals(setOf("diamond_sword"), AnvilTargets.matching("diamond_sword", have, "diamond_sword") { emptySet() })
    assertEquals(setOf("diamond_sword"), AnvilTargets.matching("swords", have, null) { emptySet() })
    assertEquals(setOf("diamond_sword"), AnvilTargets.matching("меч", have, null) { setOf("diamond_sword", "iron_sword") })
    assertEquals(emptySet(), AnvilTargets.matching("netherite_axe", have, "netherite_axe") { emptySet() })
  }
}
