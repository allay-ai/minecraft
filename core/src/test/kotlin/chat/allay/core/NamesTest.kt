package chat.allay.core

import chat.allay.core.tools.ItemData
import chat.allay.core.tools.Layout
import chat.allay.core.tools.Names
import chat.allay.core.tools.ToolError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NamesTest {
  private val ids = listOf("torch", "soul_torch", "redstone_torch", "potato", "baked_potato", "oak_planks", "birch_planks", "oak_log",
    "iron_ingot", "iron_block", "raw_iron", "glass_bottle", "wheat_seeds", "sweet_berries", "shulker_box", "stick", "arrow", "potion", "splash_potion")

  private fun resolve(name: String) = Names.resolve(name, { it in ids }, { ids })

  @Test fun `names become ids however the model writes them`() {
    assertEquals("torch", resolve("Torches"))
    assertEquals("potato", resolve("potatoes"))
    assertEquals("oak_planks", resolve("minecraft:oak plank"))
    assertEquals("iron_ingot", resolve("iron ingots"))
    assertEquals("iron_ingot", resolve("iron"))
    assertEquals("glass_bottle", resolve("glass-bottles"))
    assertEquals("wheat_seeds", resolve("wheat seed"))
    assertEquals("sweet_berries", resolve("sweet_berries"))
    assertEquals("shulker_box", resolve("shulker boxes"))
    assertEquals("stick", resolve("#sticks"))
  }

  @Test fun `a name that fits nothing names the ids close to it`() {
    val error = assertFailsWith<ToolError> { resolve("planks") }
    assertTrue("oak_planks" in error.message!! && "birch_planks" in error.message!!, error.message)
    val typo = assertFailsWith<ToolError> { resolve("tourch") }
    assertTrue("torch" in typo.message!!, typo.message)
  }

  @Test fun `selectors forgive plurals and tags`() {
    fun matches(selector: String, id: String) = Layout.matches(selector, ItemData(id, 1))
    assertTrue(matches("torches", "torch"))
    assertTrue(matches("torches", "soul_torch"))
    assertTrue(matches("potatoes", "potato"))
    assertTrue(matches("arrows", "arrow"))
    assertTrue(matches("#logs", "oak_log"))
    assertTrue(matches("minecraft:oak_planks", "oak_planks"))
    assertTrue(matches("potions", "potion"))
    assertTrue(matches("diamonds", "diamond"))
    assertTrue(matches("iron ingots", "iron_ingot"))
    assertFalse(matches("torches", "stick"))
    assertFalse(matches("iron ingots", "gold_ingot"))
  }

  @Test fun `enchantments lose their level and spelling`() {
    assertEquals("sharpness", Names.enchantment("Sharpness V"))
    assertEquals("unbreaking", Names.enchantment("unbreaking_3"))
    assertEquals("silk_touch", Names.enchantment("minecraft:Silk Touch"))
    assertEquals("luck_of_the_sea", Names.enchantment("luck of the sea II"))
    assertEquals("protection", Names.enchantment("protection iv"))
  }
}
