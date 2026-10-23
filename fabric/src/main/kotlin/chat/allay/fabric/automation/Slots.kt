package chat.allay.fabric.automation

import chat.allay.fabric.tools.Recipes

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Container
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack

/**
 * The inventory calls automation leans on, as Bukkit's inventories give them: slots read as copies with empty slots as
 * null, written back whole, compared by item, components, and count, and filled the way `addItem` fills. Copies go in
 * and out, so a plan or an undo never holds a stack the world may still change. Server thread only.
 */
internal object Slots {
  /** The player's storage slots as Bukkit's `getStorageContents` has them: the hotbar 0–8, then the main inventory. */
  val STORAGE = 0 until 36

  /** The offhand's slot in the game's inventory, as in Bukkit's. */
  const val OFFHAND = 40

  /** An item's id as the model reads it. */
  fun id(item: ItemStack): String = Recipes.id(item.item)

  fun copy(item: ItemStack?): ItemStack? = item?.takeUnless { it.isEmpty }?.copy()

  /** Every slot of [container], as Bukkit's `getContents`. */
  fun read(container: Container): List<ItemStack?> = (0 until container.containerSize).map { copy(container.getItem(it)) }

  /** Writes every slot of [container], as Bukkit's `setContents`. */
  fun write(container: Container, slots: List<ItemStack?>) {
    slots.forEachIndexed { index, item -> if (index < container.containerSize) container.setItem(index, copy(item) ?: ItemStack.EMPTY) }
    container.setChanged()
  }

  /** The same item, components, and count, as Bukkit's `ItemStack.equals`. */
  fun same(a: ItemStack?, b: ItemStack?): Boolean = if (a == null || b == null) a == null && b == null else ItemStack.matches(a, b)

  fun same(a: List<ItemStack?>, b: List<ItemStack?>): Boolean = a.size == b.size && a.indices.all { same(a[it], b[it]) }

  /** The same item and components, any count, as Bukkit's `isSimilar`. */
  fun similar(a: ItemStack, b: ItemStack): Boolean = ItemStack.isSameItemSameComponents(a, b)

  /**
   * Puts [item] into [slots] of [container] as Bukkit's `addItem` does: onto stacks of the same first, slot by slot,
   * then into empty slots. Returns what didn't fit, or null.
   */
  fun add(container: Container, slots: Iterable<Int>, item: ItemStack): ItemStack? {
    var left = item.count
    val max = minOf(item.maxStackSize, container.maxStackSize)
    for (index in slots) {
      if (left == 0) break
      val there = container.getItem(index)
      if (there.isEmpty || !similar(there, item) || there.count >= max) continue
      val put = minOf(left, max - there.count)
      container.setItem(index, there.copyWithCount(there.count + put))
      left -= put
    }
    for (index in slots) {
      if (left == 0) break
      if (!container.getItem(index).isEmpty) continue
      val put = minOf(left, max)
      container.setItem(index, item.copyWithCount(put))
      left -= put
    }
    container.setChanged()
    return if (left > 0) item.copyWithCount(left) else null
  }

  /** Puts [item] into a container's every slot, as Bukkit's `Inventory.addItem`. */
  fun add(container: Container, item: ItemStack): ItemStack? = add(container, 0 until container.containerSize, item)

  /** Puts [item] into the player's storage slots, as Bukkit's `PlayerInventory.addItem`. */
  fun add(player: ServerPlayer, item: ItemStack): ItemStack? = add(player.inventory, STORAGE, item)

  /** Drops [item] at the player's feet, as Bukkit's `dropItemNaturally`. */
  fun drop(player: ServerPlayer, item: ItemStack) {
    val entity = ItemEntity(player.level(), player.x, player.y + 0.25, player.z, item.copy())
    entity.setDefaultPickUpDelay()
    player.level().addFreshEntity(entity)
  }
}
