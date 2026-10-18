package chat.allay.spigot

import chat.allay.spigot.tools.Drafts
import org.bukkit.block.BlockFace
import org.bukkit.block.structure.StructureRotation
import kotlin.test.Test
import kotlin.test.assertEquals

/** A draft is written as if the player faced north; these hold the turn to where they really look. */
class DraftsTest {
  @Test fun `right is a quarter turn clockwise from forward`() {
    assertEquals(BlockFace.EAST, Drafts.right(BlockFace.NORTH))
    assertEquals(BlockFace.SOUTH, Drafts.right(BlockFace.EAST))
    assertEquals(BlockFace.WEST, Drafts.right(BlockFace.SOUTH))
    assertEquals(BlockFace.NORTH, Drafts.right(BlockFace.WEST))
  }

  @Test fun `block states turn the same way as the cells`() {
    // North in the draft is forward: facing east, it must become east, so the turn is a clockwise quarter.
    assertEquals(StructureRotation.NONE, Drafts.rotation(BlockFace.NORTH))
    assertEquals(StructureRotation.CLOCKWISE_90, Drafts.rotation(BlockFace.EAST))
    assertEquals(StructureRotation.CLOCKWISE_180, Drafts.rotation(BlockFace.SOUTH))
    assertEquals(StructureRotation.COUNTERCLOCKWISE_90, Drafts.rotation(BlockFace.WEST))
  }

  @Test fun `states read from the game's text`() {
    val data = java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(org.bukkit.block.data.BlockData::class.java)) { _, method, _ ->
      if (method.name == "getAsString") "minecraft:repeater[delay=2,facing=north,locked=false,powered=false]" else throw UnsupportedOperationException(method.name)
    } as org.bukkit.block.data.BlockData
    assertEquals(mapOf("delay" to "2", "facing" to "north", "locked" to "false", "powered" to "false"), Drafts.states(data))
  }
}
