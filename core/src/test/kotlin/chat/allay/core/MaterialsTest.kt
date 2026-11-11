package chat.allay.core

import chat.allay.core.building.Families
import chat.allay.core.building.Materials
import chat.allay.core.building.Renames
import chat.allay.core.building.Schem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What blocks cost in survival, and swapping one material for another across a build. */
class MaterialsTest {
  private fun items(state: String) = Materials.of(state).items

  @Test fun `a block costs the item a player places it with`() {
    assertEquals(mapOf("oak_planks" to 1), items("minecraft:oak_planks"))
    assertEquals(mapOf("torch" to 1), items("minecraft:wall_torch[facing=north]"))
    assertEquals(mapOf("spruce_sign" to 1), items("minecraft:spruce_wall_sign[facing=east,waterlogged=false]"))
    assertEquals(mapOf("white_banner" to 1), items("minecraft:white_wall_banner[facing=south]"))
    assertEquals(mapOf("redstone" to 1), items("minecraft:redstone_wire[east=side,north=none,power=0,south=none,west=side]"))
    assertEquals(mapOf("wheat_seeds" to 1), items("minecraft:wheat[age=7]"))
    assertEquals(mapOf("glow_berries" to 1), items("minecraft:cave_vines_plant[berries=true]"))
  }

  @Test fun `what stacks in one block counts each, and a pot pays for its plant`() {
    assertEquals(mapOf("stone_slab" to 2), items("minecraft:stone_slab[type=double,waterlogged=false]"))
    assertEquals(mapOf("red_candle" to 3), items("minecraft:red_candle[candles=3,lit=false,waterlogged=false]"))
    assertEquals(mapOf("sea_pickle" to 4), items("minecraft:sea_pickle[pickles=4,waterlogged=true]"))
    assertEquals(mapOf("snow" to 5), items("minecraft:snow[layers=5]"))
    assertEquals(mapOf("vine" to 2), items("minecraft:vine[east=true,north=true,south=false,up=false,west=false]"))
    assertEquals(mapOf("flower_pot" to 1, "azalea" to 1), items("minecraft:potted_azalea_bush"))
    assertEquals(mapOf("flower_pot" to 1, "poppy" to 1), items("minecraft:potted_poppy"))
    assertEquals(mapOf("cake" to 1, "blue_candle" to 1), items("minecraft:blue_candle_cake[lit=false]"))
  }

  @Test fun `the bottom half pays for a door, a bed, and a tall plant`() {
    assertEquals(mapOf("oak_door" to 1), items("minecraft:oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]"))
    assertEquals(emptyMap(), items("minecraft:oak_door[facing=north,half=upper,hinge=left,open=false,powered=false]"))
    assertEquals(emptyMap(), items("minecraft:red_bed[facing=east,occupied=false,part=head]"))
    assertEquals(emptyMap(), items("minecraft:sunflower[half=upper]"))
  }

  @Test fun `water, lava, tools, and what no player can get`() {
    assertTrue(Materials.of("minecraft:water[level=0]").water)
    assertEquals(emptyMap(), items("minecraft:water[level=3]"))
    assertTrue(Materials.of("minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=true]").water)
    assertEquals(mapOf("lava_bucket" to 1), items("minecraft:lava[level=0]"))
    assertEquals("hoe", Materials.of("minecraft:farmland[moisture=7]").tool)
    assertEquals("shovel", Materials.of("minecraft:dirt_path").tool)
    assertTrue(Materials.of("minecraft:bedrock").unobtainable)
    assertTrue(Materials.of("minecraft:infested_stone_bricks").unobtainable)
    assertTrue(Materials.of("minecraft:player_head[rotation=4]").free)
    assertEquals(emptyMap(), items("minecraft:piston_head[facing=up,short=false,type=normal]"))
  }

  @Test fun `a real build's estimate adds up to its blocks`() {
    val villa = Schem.read(MaterialsTest::class.java.getResourceAsStream("/schematics/modern-villa.schem")!!.readBytes())
    val estimate = Materials.estimate(villa)
    assertTrue(estimate.items.getValue("sandstone") > 0)
    assertTrue(estimate.items.values.sum() + estimate.unobtainable.values.sum() + estimate.free.values.sum() <= villa.blocks * 2)
  }

  @Test fun `a swap takes the whole family along, states kept, and names a shape that doesn't exist`() {
    val known = setOf("stone_brick_stairs", "stone_brick_slab", "stone_brick_wall", "dark_oak_stairs", "dark_oak_slab")::contains
    val stairs = Families.swap("minecraft:cobblestone_stairs[facing=east,half=top,shape=straight,waterlogged=false]", "cobblestone", "stone_bricks", known)
    assertEquals(Families.Swap.To("minecraft:stone_brick_stairs[facing=east,half=top,shape=straight,waterlogged=false]"), stairs)
    assertEquals(Families.Swap.To("minecraft:stone_bricks"), Families.swap("minecraft:cobblestone", "cobblestone", "stone_bricks", known))
    assertEquals(Families.Swap.To("minecraft:dark_oak_stairs[facing=north]"), Families.swap("minecraft:oak_stairs[facing=north]", "oak_planks", "dark_oak_planks", known))
    assertNull(Families.swap("minecraft:oak_log[axis=y]", "cobblestone", "stone_bricks", known))
    val (palette, missing) = Families.apply(listOf("minecraft:air", "minecraft:oak_fence", "minecraft:oak_planks"), "oak_planks", "dark_oak_planks", known)
    assertEquals(listOf("minecraft:air", "minecraft:oak_fence", "minecraft:dark_oak_planks"), palette)
    assertEquals(setOf("dark_oak_fence"), missing)
  }

  @Test fun `a build is made of other blocks a player pays for, never water, lava, fire, or a free head`() {
    for (block in listOf("lava", "water", "fire", "player_head", "air", "bubble_column")) assertTrue(Materials.refusal(block) != null, block)
    assertTrue(Materials.refusal("bedrock")!!.startsWith("no survival player"))
    assertNull(Materials.refusal("stone_bricks"))
    assertNull(Materials.refusal("dark_oak_planks"))
    val palette = listOf("minecraft:air", "minecraft:cobblestone_stairs[facing=east]", "minecraft:oak_planks")
    assertTrue(Families.has(palette, "cobblestone"))
    assertTrue(Families.has(palette, "oak_planks"))
    assertFalse(Families.has(palette, "stone"), "stone isn't stonecutter's or stone bricks' family")
  }

  @Test fun `an old build's stone slab is today's smooth stone slab`() {
    val known: (String) -> Boolean = { true }
    val slab = "minecraft:stone_slab[type=double,waterlogged=false]"
    assertEquals("minecraft:smooth_stone_slab[type=double,waterlogged=false]", Renames.fix(slab, known, 1631))
    assertEquals(slab, Renames.fix(slab, known, 3953))
    assertEquals(slab, Renames.fix(slab, known))
    assertEquals(listOf("minecraft:smooth_stone_slab[type=bottom]"), Materials.placed(listOf("minecraft:stone_slab[type=bottom]"), 1519, known))
  }
}
