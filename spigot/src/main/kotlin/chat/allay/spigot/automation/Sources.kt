package chat.allay.spigot.automation

import chat.allay.core.tasks.Reservations
import chat.allay.spigot.Claims

import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.block.Block
import org.bukkit.block.Container
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BundleMeta

/**
 * Where automation takes ingredients from: the player's inventory, the bundles in it (bundles inside bundles too, as
 * deep as the game nests them), the ender chest when one stands near, and chests and barrels nearby the player may
 * open. Never shulker boxes, and never items with a name, enchantments, or anything else of their own. Server thread only.
 */
class Sources private constructor(val list: List<Source>) {
  /** One place items come from, read and written as a whole list of slots. */
  class Source(val place: String, val own: Boolean, val block: Block?, private val read: () -> List<ItemStack?>, private val write: (List<ItemStack?>) -> Unit) {
    fun slots() = read()

    fun count(match: (ItemStack) -> Boolean) = read().filterNotNull().filter(match).sumOf { it.amount }

    /** Takes up to [amount] of what [match] finds; returns what it took. */
    fun take(match: (ItemStack) -> Boolean, amount: Int): List<ItemStack> {
      val slots = read().toMutableList()
      val taken = mutableListOf<ItemStack>()
      var left = amount
      for (index in slots.indices) {
        if (left <= 0) break
        val item = slots[index] ?: continue
        if (!match(item)) continue
        val take = minOf(left, item.amount)
        taken += item.clone().apply { this.amount = take }
        slots[index] = if (item.amount > take) item.clone().apply { this.amount = item.amount - take } else null
        left -= take
      }
      if (taken.isNotEmpty()) write(slots)
      return taken
    }

    /** Puts [item] in, merging first; returns what didn't fit. */
    fun add(item: ItemStack): ItemStack? {
      val slots = read().toMutableList()
      var left = item.amount
      for (index in slots.indices) {
        val there = slots[index] ?: continue
        if (left == 0) break
        if (!there.isSimilar(item) || there.amount >= there.maxStackSize) continue
        val put = minOf(left, there.maxStackSize - there.amount)
        slots[index] = there.clone().apply { amount += put }
        left -= put
      }
      for (index in slots.indices) {
        if (left == 0) break
        if (slots[index] != null) continue
        val put = minOf(left, item.maxStackSize)
        slots[index] = item.clone().apply { amount = put }
        left -= put
      }
      write(slots)
      return if (left > 0) item.clone().apply { amount = left } else null
    }
  }

  fun count(match: (ItemStack) -> Boolean) = list.sumOf { it.count(match) }

  /** How many [match] finds that no other task than [task] holds. */
  fun free(match: (ItemStack) -> Boolean, key: String, holds: Reservations, task: String): Int =
    list.sumOf { (it.count(match) - holds.held(it.place, key, except = task)).coerceAtLeast(0) }

  /** Reserves [amount] of [key] for [task], from the places that have them; false when there aren't enough. */
  fun reserve(match: (ItemStack) -> Boolean, key: String, amount: Int, holds: Reservations, task: String): Boolean {
    var left = amount
    for (source in list) {
      if (left <= 0) break
      val free = (source.count(match) - holds.held(source.place, key, except = task)).coerceAtLeast(0)
      val take = minOf(left, free)
      if (take > 0) holds.reserve(task, source.place, key, take)
      left -= take
    }
    if (left > 0) holds.release(task)
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
      val n = got.sumOf { it.amount }
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

    /** An item automation may use up: plain, as it comes from the game, and no shulker box. */
    fun plain(item: ItemStack): Boolean = !Tag.SHULKER_BOXES.isTagged(item.type) && item.type != Material.BUNDLE && !item.type.name.endsWith("_BUNDLE") &&
      item.isSimilar(ItemStack(item.type))

    /** The places around [player] within [radius], the inventory and its bundles first. */
    fun around(player: Player, radius: Int): Sources {
      val list = mutableListOf<Source>()
      val inventory = player.inventory
      list += Source(Blocks.inventory(player), true, null, { inventory.storageContents.toList() }, { inventory.storageContents = it.toTypedArray() })
      for (slot in 0 until 36) bundles(list, "${Blocks.inventory(player)}#$slot", 1, { inventory.getItem(slot) }, { inventory.setItem(slot, it) })
      val ender = Blocks.near(player.location, radius) { it.type == Material.ENDER_CHEST }.isNotEmpty()
      if (ender) list += Source("ender:${player.uniqueId}", true, null, { player.enderChest.contents.toList() }, { player.enderChest.contents = it.toTypedArray() })
      Blocks.near(player.location, radius) { Blocks.storage(it.type) }.map(Blocks::root).distinct().forEach { block ->
        val state = block.state as? Container ?: return@forEach
        if (!Claims.mayOpen(player, state) || Blocks.open(block)) return@forEach
        list += Source(Blocks.place(block), false, block, { (block.state as Container).inventory.contents.toList() }, { (block.state as Container).inventory.contents = it.toTypedArray() })
      }
      return Sources(list)
    }

    /** A bundle at [get] becomes a source, and each bundle inside it too, down to [BUNDLE_DEPTH]. */
    private fun bundles(list: MutableList<Source>, place: String, depth: Int, get: () -> ItemStack?, set: (ItemStack?) -> Unit) {
      if (depth > BUNDLE_DEPTH) return
      val bundle = get() ?: return
      val meta = bundle.itemMeta as? BundleMeta ?: return
      val read = { (get()?.itemMeta as? BundleMeta)?.items?.toList().orEmpty() }
      val write: (List<ItemStack?>) -> Unit = { items ->
        val item = get()
        val inner = item?.itemMeta as? BundleMeta
        if (item != null && inner != null) {
          inner.setItems(items.filterNotNull())
          item.itemMeta = inner
          set(item)
        }
      }
      // A bundle has no empty slots: what goes in is added at the end, never into a full bundle.
      list += Source(place, true, null, read, write)
      meta.items.forEachIndexed { index, _ ->
        bundles(list, "$place/$index", depth + 1, { read().getOrNull(index) }, { inner ->
          val items = read().toMutableList()
          if (index < items.size) {
            if (inner == null) items.removeAt(index) else items[index] = inner
            write(items)
          }
        })
      }
    }
  }
}

/** Hands [items] to [player]: into [preferred] if it has room, then the inventory, then at their feet. */
fun give(player: Player, items: List<ItemStack>, preferred: Sources.Source? = null) {
  items.forEach { item ->
    var left: ItemStack? = item
    // A bundle source refuses nothing, so only containers and the inventory take items back.
    if (preferred != null && !preferred.place.contains('#')) left = left?.let(preferred::add)
    left = left?.let { player.inventory.addItem(it).values.firstOrNull() }
    left?.let { player.world.dropItemNaturally(player.location, it) }
  }
}
