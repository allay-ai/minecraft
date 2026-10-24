package chat.allay.modcore.automation

import chat.allay.modcore.tools.BlockAt
import chat.allay.modcore.tools.at

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.alchemy.PotionContents
import net.minecraft.world.item.alchemy.Potions
import net.minecraft.world.level.block.Blocks as GameBlocks
import net.minecraft.world.level.block.LayeredCauldronBlock
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.state.properties.BlockStateProperties

/**
 * Where glass bottles fill with water, as clicking with one does: a water source near the player fills any number,
 * a water cauldron one bottle a level, and an emptied cauldron turns back into a plain one. Brewing and crafting both
 * fill here. Server thread only.
 */
object Water {
  /** How far water and cauldrons may be for filling bottles. */
  const val REACH = 8

  /** Plants that always stand in a water source. */
  private val UNDERWATER = setOf(GameBlocks.KELP, GameBlocks.KELP_PLANT, GameBlocks.SEAGRASS, GameBlocks.TALL_SEAGRASS, GameBlocks.BUBBLE_COLUMN)

  /** A water source a glass bottle fills at, as the game's bottle sees it: flowing water doesn't count. */
  private fun source(block: BlockAt): Boolean {
    val state = block.state
    return (state.`is`(GameBlocks.WATER) && state.getValue(LiquidBlock.LEVEL) == 0) || state.block in UNDERWATER ||
      (state.hasProperty(BlockStateProperties.WATERLOGGED) && state.getValue(BlockStateProperties.WATERLOGGED))
  }

  /** Water the player could fill bottles at near them: null for none, [Int.MAX_VALUE] for water itself, else the cauldrons' levels. */
  fun near(player: ServerPlayer): Pair<Int, List<BlockAt>>? {
    val origin = player.blockPosition()
    val level = player.level()
    val cauldrons = mutableListOf<BlockAt>()
    var levels = 0
    for (dx in -REACH..REACH) for (dy in -REACH..REACH) for (dz in -REACH..REACH) {
      val block = level.at(origin.offset(dx, dy, dz))
      if (source(block)) return Int.MAX_VALUE to emptyList()
      if (block.state.`is`(GameBlocks.WATER_CAULDRON)) {
        cauldrons += block
        levels += block.state.getValue(LayeredCauldronBlock.LEVEL)
      }
    }
    return if (levels > 0) levels to cauldrons else null
  }

  /** Takes a level from [near]'s cauldrons for each of [bottles], as the game does; nothing for water itself. */
  fun drain(near: Pair<Int, List<BlockAt>>, bottles: Int) {
    var lower = if (near.first == Int.MAX_VALUE) 0 else bottles
    for (cauldron in near.second) {
      if (lower <= 0) break
      val state = cauldron.state.takeIf { it.`is`(GameBlocks.WATER_CAULDRON) } ?: continue
      val now = state.getValue(LayeredCauldronBlock.LEVEL)
      val take = minOf(lower, now)
      lower -= take
      cauldron.level.setBlockAndUpdate(cauldron.pos, if (now - take <= 0) GameBlocks.CAULDRON.defaultBlockState() else state.setValue(LayeredCauldronBlock.LEVEL, now - take))
    }
  }

  /** A water bottle, as filling a glass bottle makes it. */
  fun bottle(): ItemStack = PotionContents.createItemStack(Items.POTION, Potions.WATER)
}
