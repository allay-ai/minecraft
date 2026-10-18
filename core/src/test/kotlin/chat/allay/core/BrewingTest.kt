package chat.allay.core

import chat.allay.core.tools.Brewing
import chat.allay.core.tools.Brewing.Bottle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BrewingTest {
  /** A recipe file as 26.3 ships it. */
  private fun recipe(from: String, potion: String, reagent: String, to: String, made: String) = """
    {"type": "minecraft:brewing",
     "input": {"item": "minecraft:$from", "potion_contents": {"potions": "minecraft:$potion"}},
     "output": {"components": {"minecraft:potion_contents": {"potion": "minecraft:$made"}}, "id": "minecraft:$to"},
     "reagent": {"item": "minecraft:$reagent"}}
  """

  private val brews = listOf(
    recipe("potion", "water", "nether_wart", "potion", "awkward"),
    recipe("potion", "water", "redstone", "potion", "mundane"),
    recipe("potion", "awkward", "golden_carrot", "potion", "night_vision"),
    recipe("potion", "night_vision", "redstone", "potion", "long_night_vision"),
    recipe("potion", "night_vision", "fermented_spider_eye", "potion", "invisibility"),
    recipe("potion", "night_vision", "gunpowder", "splash_potion", "night_vision"),
    recipe("splash_potion", "night_vision", "dragon_breath", "lingering_potion", "night_vision"),
  ).flatMap(Brewing::parse)

  @Test fun `a recipe file becomes one brew`() {
    assertEquals(
      listOf(Brewing.Brew(Bottle("potion", "awkward"), "golden_carrot", Bottle("potion", "night_vision"))),
      Brewing.parse(recipe("potion", "awkward", "golden_carrot", "potion", "night_vision")),
    )
  }

  @Test fun `other recipes and broken files give nothing`() {
    assertEquals(emptyList(), Brewing.parse("""{"type": "minecraft:crafting_shaped"}"""))
    assertEquals(emptyList(), Brewing.parse("not json"))
  }

  @Test fun `the chain starts from a water bottle`() {
    val chain = Brewing.chain(brews, Bottle("potion", "long_night_vision"))!!
    assertEquals(listOf("nether_wart", "golden_carrot", "redstone"), chain.map { it.reagent })
  }

  @Test fun `forms come from gunpowder and dragon's breath`() {
    val chain = Brewing.chain(brews, Bottle("lingering_potion", "night_vision"))!!
    assertEquals(listOf("nether_wart", "golden_carrot", "gunpowder", "dragon_breath"), chain.map { it.reagent })
  }

  @Test fun `the bottle turns last, even when another way is as short`() {
    val wateryFirst = brews + listOf(
      recipe("potion", "water", "gunpowder", "splash_potion", "water"),
      recipe("splash_potion", "water", "nether_wart", "splash_potion", "awkward"),
      recipe("splash_potion", "awkward", "golden_carrot", "splash_potion", "night_vision"),
    ).flatMap(Brewing::parse)
    val chain = Brewing.chain(wateryFirst.reversed(), Bottle("splash_potion", "night_vision"))!!
    assertEquals(listOf("nether_wart", "golden_carrot", "gunpowder"), chain.map { it.reagent })
  }

  @Test fun `a potion nobody brews has no chain`() {
    assertNull(Brewing.chain(brews, Bottle("potion", "luck")))
  }

  @Test fun `names match ids first, then the shortest id that holds them`() {
    assertEquals("night_vision", Brewing.find(brews, "Night Vision"))
    assertEquals("long_night_vision", Brewing.find(brews, "minecraft:long_night_vision"))
    assertEquals("invisibility", Brewing.find(brews, "invis"))
    assertNull(Brewing.find(brews, "luck"))
  }

  @Test fun `stands fill three to one, own bottles first`() {
    assertEquals(listOf(Brewing.Fill(0, 3)), Brewing.fill(3, 4, emptyMap()))
    assertEquals(listOf(Brewing.Fill(2, 3), Brewing.Fill(2, 1)), Brewing.fill(4, 4, mapOf(2 to 4)))
    assertEquals(listOf(Brewing.Fill(2, 2), Brewing.Fill(1, 3), Brewing.Fill(0, 2)), Brewing.fill(7, 4, mapOf(1 to 3, 2 to 2)))
    assertEquals(listOf(Brewing.Fill(0, 3), Brewing.Fill(0, 3)), Brewing.fill(9, 2, emptyMap()))
  }

  @Test fun `more loads than stands brew in rounds, evenly`() {
    assertEquals(listOf(listOf(1, 3, 5), listOf(2, 4)), Brewing.rounds(listOf(1, 2, 3, 4, 5), 2))
    assertEquals(listOf(listOf(1), listOf(2)), Brewing.rounds(listOf(1, 2), 4))
    assertEquals(emptyList(), Brewing.rounds(listOf(1), 0))
  }
}
