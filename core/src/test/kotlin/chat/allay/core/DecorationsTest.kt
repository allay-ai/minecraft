package chat.allay.core

import chat.allay.core.building.Blueprint
import chat.allay.core.building.Construction
import chat.allay.core.building.Materials
import chat.allay.core.building.Placement
import chat.allay.core.building.Schem
import chat.allay.core.tools.DraftSide
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Heads, banners, and the entities a build keeps: frames, paintings, armor stands. */
class DecorationsTest {
  private val corner = Schem.read(DecorationsTest::class.java.getResourceAsStream("/schematics/decorated-corner.schem")!!.readBytes())

  @Test fun `a build keeps its frames, paintings, and armor stands, and a player head its skin`() {
    assertEquals(listOf("item_frame", "painting", "armor_stand"), corner.entities.map { it.id })
    val painting = corner.entities[1]
    assertEquals(3, painting.facing)
    assertEquals("kebab", painting.variant)
    assertEquals(180f, corner.entities[2].yaw)
    val head = corner.blockEntities.first { it.x == 0 && it.y == 1 && it.z == 2 }
    assertTrue(head.texture!!.isNotBlank())
    assertNull(corner.blockEntities.first { it.x == 3 }.texture)
  }

  @Test fun `a banner goes up plain white, a mob head may be Steve's, a player's head is free`() {
    val placed = Materials.placed(corner.palette)
    assertTrue("minecraft:white_banner[rotation=0]" in placed)
    assertEquals(listOf("minecraft:white_wall_banner[facing=east]"), Materials.placed(listOf("minecraft:light_gray_wall_banner[facing=east]")))
    assertEquals("minecraft:player_head[rotation=4]", Materials.steve("minecraft:zombie_head[rotation=4]"))
    assertEquals("minecraft:player_wall_head[facing=north]", Materials.steve("minecraft:creeper_wall_head[facing=north]"))
    assertNull(Materials.steve("minecraft:player_head[rotation=4]"))
    // A build an older game saved names grass and wall signs as it did: the estimate asks for what this game has.
    val old = Materials.placed(listOf("minecraft:grass", "minecraft:wall_sign[facing=north]")) { it in setOf("short_grass", "oak_wall_sign") }
    assertEquals(listOf("minecraft:short_grass", "minecraft:oak_wall_sign[facing=north]"), old)
    val estimate = Materials.estimate(corner, placed)
    assertEquals(1, estimate.items["white_banner"])
    assertEquals(1, estimate.items["zombie_head"])
    assertEquals(1, estimate.items["item_frame"])
    assertEquals(1, estimate.items["painting"])
    assertEquals(1, estimate.items["armor_stand"])
    assertEquals(1, estimate.free["player_head"])
  }

  @Test fun `a head's skin comes only from the game's skin server`() {
    val head = corner.blockEntities.first { it.texture != null }
    assertEquals("https://textures.minecraft.net/texture/a116e69a845e227f7ca1fdde8c357c8c821ebd4ba619382ea4a1f87d4ae94", Construction.skin(head.texture!!))
    val elsewhere = Base64.getEncoder().encodeToString("{\"textures\":{\"SKIN\":{\"url\":\"http://evil.example/texture/abcdef0123456789\"}}}".toByteArray())
    assertNull(Construction.skin(elsewhere))
  }

  @Test fun `a direction turns with the build`() {
    val north = Placement.before(corner, Triple(0, 64, 0), DraftSide.NORTH)
    val east = Placement.before(corner, Triple(0, 64, 0), DraftSide.EAST)
    // South in the schematic (+z) points back at a player who looks north, and at one looking east: west.
    assertEquals(0 to 1, north.direction(corner, 0, 1))
    assertEquals(-1 to 0, east.direction(corner, 0, 1))
    assertEquals(-1 to 0, north.copy(mirrored = true).direction(corner, 1, 0))
  }

  @Test fun `a painting of even size hangs in the block the game spawns it at, turned and mirrored`() {
    // A 2×2 painting facing south on a wall at z 0, its block x 1, y 1, z 1: its middle lies a half block east and up.
    val box = Blueprint.Builder(4, 4, 4).build(listOf("minecraft:air"), 0, emptyList(), "", "")
    val painting = Blueprint.Entity(2.0, 2.0, 1.03125, "painting", facing = 3, variant = "kebab")
    val north = Placement(Triple(0, 0, 0), DraftSide.NORTH)
    assertEquals(north.world(box, 1, 1, 1), Construction.hangs(painting, box, north))
    // One block wide and tall sits where its middle is.
    val small = painting.copy(x = 1.5, y = 1.5)
    assertEquals(north.world(box, 1, 1, 1), Construction.hangs(small, box, north))
    // Mirrored, it reaches west from its block, so the block is the other one of the two.
    val mirrored = north.copy(mirrored = true)
    assertEquals(mirrored.world(box, 2, 1, 1), Construction.hangs(painting, box, mirrored))
    // An item frame is its block, whatever the build does.
    val frame = painting.copy(id = "item_frame", x = 1.5, y = 1.5)
    assertEquals(mirrored.world(box, 1, 1, 1), Construction.hangs(frame, box, mirrored))
  }
}
