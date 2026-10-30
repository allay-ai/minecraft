package chat.allay.spigot.automation

import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.data.Levelled
import org.bukkit.block.data.Waterlogged
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.PotionMeta
import org.bukkit.potion.PotionType

/**
 * Where glass bottles fill with water, as clicking with one does: a water source near the player fills any number,
 * a water cauldron one bottle a level, and an emptied cauldron turns back into a plain one. Brewing and crafting both
 * fill here. Server thread only.
 */
object Water {
  /** How far water and cauldrons may be for filling bottles. */
  const val REACH = 8

  /** Plants that always stand in a water source. */
  private val UNDERWATER = setOf(Material.KELP, Material.KELP_PLANT, Material.SEAGRASS, Material.TALL_SEAGRASS, Material.BUBBLE_COLUMN)

  /** A water source a glass bottle fills at, as the game's bottle sees it: flowing water doesn't count. */
  private fun source(block: Block) = (block.type == Material.WATER && (block.blockData as? Levelled)?.level == 0) || block.type in UNDERWATER ||
    (block.blockData as? Waterlogged)?.isWaterlogged == true

  /**
   * Water the player could fill bottles at near them: null for none, [Int.MAX_VALUE] for water itself, else the
   * cauldrons' levels. A cauldron counts as a station does ([Blocks.unusable]): not in someone's claim, within
   * `automation.radius`, and in plain sight.
   */
  fun near(player: Player): Pair<Int, List<Block>>? {
    val settings = Jobs.automation.settings()
    val origin = player.location.block
    val cauldrons = mutableListOf<Block>()
    var levels = 0
    for (dx in -REACH..REACH) for (dy in -REACH..REACH) for (dz in -REACH..REACH) {
      val block = origin.getRelative(dx, dy, dz)
      if (source(block)) return Int.MAX_VALUE to emptyList()
      if (block.type == Material.WATER_CAULDRON && Blocks.unusable(player, block, settings.radius, settings.lineOfSight) == null) {
        cauldrons += block
        levels += (block.blockData as? Levelled)?.level ?: 0
      }
    }
    return if (levels > 0) levels to cauldrons else null
  }

  /** Takes a level from [cauldrons] for each of [bottles], as the game does; nothing for water itself. */
  fun drain(near: Pair<Int, List<Block>>, bottles: Int) {
    var lower = if (near.first == Int.MAX_VALUE) 0 else bottles
    for (cauldron in near.second) {
      if (lower <= 0) break
      val data = cauldron.blockData as? Levelled ?: continue
      val take = minOf(lower, data.level)
      lower -= take
      if (data.level - take <= 0) cauldron.type = Material.CAULDRON else cauldron.blockData = data.apply { level -= take }
    }
  }

  /** A water bottle, as filling a glass bottle makes it. */
  fun bottle(): ItemStack = ItemStack(Material.POTION).apply { itemMeta = (itemMeta as PotionMeta).apply { basePotionType = PotionType.WATER } }
}
