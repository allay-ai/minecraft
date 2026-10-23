package chat.allay.modcore.automation

import chat.allay.core.tasks.Reservations
import chat.allay.modcore.audience
import chat.allay.modcore.player
import chat.allay.modcore.tools.BlockAt

import net.minecraft.core.component.DataComponents
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.ItemTags
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.ItemStackTemplate
import net.minecraft.world.item.component.BundleContents
import net.minecraft.world.level.block.Blocks as GameBlocks

/**
 * Where automation takes ingredients from: the player's inventory, the bundles in it (bundles inside bundles too, as
 * deep as the game nests them), the ender chest when one stands near, and chests and barrels nearby the player may
 * open. Never shulker boxes, and never items with a name, enchantments, or anything else of their own. Server thread only.
 */
class Sources private constructor(val list: List<Source>) {
  /** One place items come from, read and written as a whole list of slots. */
  class Source(val place: String, val own: Boolean, val block: BlockAt?, private val read: () -> List<ItemStack?>, private val write: (List<ItemStack?>) -> Unit) {
    fun slots() = read()

    fun count(match: (ItemStack) -> Boolean) = read().filterNotNull().filter(match).sumOf { it.count }

    /** Takes up to [amount] of what [match] finds; returns what it took. */
    fun take(match: (ItemStack) -> Boolean, amount: Int): List<ItemStack> {
      val slots = read().toMutableList()
      val taken = mutableListOf<ItemStack>()
      var left = amount
      for (index in slots.indices) {
        if (left <= 0) break
        val item = slots[index] ?: continue
        if (!match(item)) continue
        val take = minOf(left, item.count)
        taken += item.copyWithCount(take)
        slots[index] = if (item.count > take) item.copyWithCount(item.count - take) else null
        left -= take
      }
      if (taken.isNotEmpty()) write(slots)
      return taken
    }

    /** Puts [item] in, merging first; returns what didn't fit. */
    fun add(item: ItemStack): ItemStack? {
      val slots = read().toMutableList()
      var left = item.count
      for (index in slots.indices) {
        val there = slots[index] ?: continue
        if (left == 0) break
        if (!Slots.similar(there, item) || there.count >= there.maxStackSize) continue
        val put = minOf(left, there.maxStackSize - there.count)
        slots[index] = there.copyWithCount(there.count + put)
        left -= put
      }
      for (index in slots.indices) {
        if (left == 0) break
        if (slots[index] != null) continue
        val put = minOf(left, item.maxStackSize)
        slots[index] = item.copyWithCount(put)
        left -= put
      }
      write(slots)
      return if (left > 0) item.copyWithCount(left) else null
    }
  }

  fun count(match: (ItemStack) -> Boolean) = list.sumOf { it.count(match) }

  /** How many [match] finds that nobody holds yet, [task] included: a task never books the same items twice. */
  fun free(match: (ItemStack) -> Boolean, key: String, holds: Reservations, task: String): Int =
    list.sumOf { (it.count(match) - holds.held(it.place, key)).coerceAtLeast(0) }

  /** Reserves [amount] of [key] for [task], from the places that have them; false when there aren't enough. */
  fun reserve(match: (ItemStack) -> Boolean, key: String, amount: Int, holds: Reservations, task: String): Boolean {
    var left = amount
    for (source in list) {
      if (left <= 0) break
      val free = (source.count(match) - holds.held(source.place, key)).coerceAtLeast(0)
      val take = minOf(left, free)
      if (take > 0) holds.reserve(task, source.place, key, take)
      left -= take
    }
    return left <= 0
  }

  /**
   * Takes [amount] the task reserved, place by place. Returns what it took and the place it fell short at, if any:
   * someone took what was reserved there.
   */
  fun takeReserved(match: (ItemStack) -> Boolean, key: String, amount: Int, holds: Reservations, task: String): Pair<List<ItemStack>, Source?> {
    val taken = mutableListOf<ItemStack>()
    var left = amount
    val held = holds.of(task)
    for (source in list) {
      if (left <= 0) break
      val mine = held[source.place]?.get(key) ?: continue
      val want = minOf(left, mine)
      val got = source.take(match, want)
      val n = got.sumOf { it.count }
      taken += got
      holds.take(task, source.place, key, n)
      left -= n
      if (n < want) return taken to source
    }
    return taken to null
  }

  companion object {
    /** How deep bundles nest in bundles, as the game allows. */
    private const val BUNDLE_DEPTH = 4

    /** An item automation may use up: plain, as it comes from the game, and no shulker box or bundle. */
    fun plain(item: ItemStack): Boolean = !item.`is`(ItemTags.SHULKER_BOXES) && !item.`is`(ItemTags.BUNDLES) &&
      ItemStack.isSameItemSameComponents(item, ItemStack(item.item))

    /**
     * The places around [player] within [radius], the inventory and its bundles first. The player's own places read the
     * player as the game has them at each use: a respawn makes a new one, and with keepInventory it holds the same
     * stacks, so a job refilling from the old inventory would hand out items twice.
     */
    fun around(player: ServerPlayer, radius: Int): Sources {
      val list = mutableListOf<Source>()
      val owner = player.audience
      val inventory = { owner.player.inventory }
      list += Source(Blocks.inventory(player), true, null, { Slots.STORAGE.map { Slots.copy(inventory().getItem(it)) } }, { items ->
        Slots.STORAGE.forEach { inventory().setItem(it, Slots.copy(items.getOrNull(it)) ?: ItemStack.EMPTY) }
      })
      for (slot in Slots.STORAGE) bundles(list, "${Blocks.inventory(player)}#$slot", 1, { Slots.copy(inventory().getItem(slot)) }, { inventory().setItem(slot, it ?: ItemStack.EMPTY) })
      val ender = Blocks.near(player, radius) { it.state.`is`(GameBlocks.ENDER_CHEST) }.isNotEmpty()
      if (ender) list += Source("ender:${player.uuid}", true, null, { Slots.read(owner.player.enderChestInventory) }, { Slots.write(owner.player.enderChestInventory, it) })
      Blocks.near(player, radius) { Blocks.storage(it.state) }.map(Blocks::root).distinct().forEach { block ->
        // A farm's output or a sorter's chest (a hopper on it) stays out, as it does for sorting.
        if (Blocks.inventory(block) == null || Blocks.unsuitable(player, block) != null) return@forEach
        list += Source(Blocks.place(block), false, block, { Blocks.inventory(block)?.let(Slots::read).orEmpty() }, { items -> Blocks.inventory(block)?.let { Slots.write(it, items) } })
      }
      return Sources(list)
    }

    /** A bundle at [get] becomes a source, and each bundle inside it too, down to [BUNDLE_DEPTH]. */
    private fun bundles(list: MutableList<Source>, place: String, depth: Int, get: () -> ItemStack?, set: (ItemStack?) -> Unit) {
      if (depth > BUNDLE_DEPTH) return
      val bundle = get() ?: return
      val contents = bundle.get(DataComponents.BUNDLE_CONTENTS) ?: return
      val read = { get()?.get(DataComponents.BUNDLE_CONTENTS)?.itemCopies()?.toList().orEmpty() }
      val write: (List<ItemStack?>) -> Unit = { items ->
        val item = get()
        if (item != null && item.has(DataComponents.BUNDLE_CONTENTS)) {
          item.set(DataComponents.BUNDLE_CONTENTS, BundleContents(items.filterNotNull().filter { !it.isEmpty }.map(ItemStackTemplate::fromNonEmptyStack)))
          set(item)
        }
      }
      // A bundle has no empty slots: what goes in is added at the end, never into a full bundle.
      list += Source(place, true, null, read, write)
      repeat(contents.size()) { index ->
        bundles(list, "$place/$index", depth + 1, { read().getOrNull(index) }, { inner ->
          val items = read().toMutableList<ItemStack?>()
          if (index < items.size) {
            if (inner == null || inner.isEmpty) items.removeAt(index) else items[index] = inner
            write(items)
          }
        })
      }
    }
  }
}

/** Hands [items] to [player]: into [preferred] if it has room, then the inventory, then at their feet. Returns how many fell there. */
fun give(player: ServerPlayer, items: List<ItemStack>, preferred: Sources.Source? = null): Int {
  var dropped = 0
  items.forEach { item ->
    var left: ItemStack? = item
    // A bundle source refuses nothing, so only containers and the inventory take items back.
    if (preferred != null && !preferred.place.contains('#')) left = left?.let(preferred::add)
    left = left?.let { Slots.add(player, it) }
    left?.let { Slots.drop(player, it); dropped += it.count }
  }
  return dropped
}
