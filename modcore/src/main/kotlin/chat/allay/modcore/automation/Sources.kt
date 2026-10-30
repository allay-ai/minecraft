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
 * Where automation takes ingredients from: the player's inventory, the ender chest when one stands near, and chests and
 * barrels nearby the player may open, each with the bundles it holds (bundles inside bundles too, as deep as the game
 * nests them). Never shulker boxes, and never items with a name, enchantments, or anything else of their own. Server thread only.
 */
class Sources private constructor(val list: List<Source>, private val dropped: List<Source> = emptyList()) {
  /**
   * One place items come from, read and written as a whole list of slots. [block] is the chest it is; [chest] the
   * chest it lies in, a bundle's too.
   */
  class Source(
    val place: String, val own: Boolean, val block: BlockAt?, private val read: () -> List<ItemStack?>, private val write: (List<ItemStack?>) -> Unit,
    val chest: BlockAt? = block,
  ) {
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

  /**
   * These places as they are now for [player], for a job that takes what it reserved long after its start: a chest
   * that turned unfit since (someone's claim, open in a window, a hopper on it, gone, another world, farther than
   * [reach]) and the ender chest once none stands within [radius] drop out, as they would at a start. What the task
   * holds there can't be taken then: [takeReserved] names the place it fell short at.
   */
  fun now(player: ServerPlayer, radius: Int, reach: Int): Sources {
    val ender = list.any { it.place.startsWith("ender:") } && enderNear(player, radius)
    val chests = list.mapNotNull { it.chest }.distinct().associateWith { fits(player, it, reach) }
    val (kept, out) = list.partition { source ->
      when {
        source.chest != null -> chests.getValue(source.chest)
        source.place.startsWith("ender:") -> ender
        else -> true
      }
    }
    return Sources(kept, out)
  }

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
  fun takeReserved(match: (ItemStack) -> Boolean, key: String, amount: Int, holds: Reservations, task: String): Pair<List<ItemStack>, Source?> =
    takeReservedFrom(match, key, amount, holds, task).let { (parts, short) -> parts.flatMap { it.second } to short }

  /**
   * [takeReserved], keeping each stack with the place it came from, so a refusal can put it back there. A place this
   * look left out ([now]) where the task holds [key] counts as the place it fell short at.
   */
  fun takeReservedFrom(match: (ItemStack) -> Boolean, key: String, amount: Int, holds: Reservations, task: String): Pair<List<Pair<Source, List<ItemStack>>>, Source?> {
    val taken = mutableListOf<Pair<Source, List<ItemStack>>>()
    var left = amount
    val held = holds.of(task)
    for (source in list) {
      if (left <= 0) break
      val mine = held[source.place]?.get(key) ?: continue
      val want = minOf(left, mine)
      val got = source.take(match, want)
      val n = got.sumOf { it.count }
      if (got.isNotEmpty()) taken += source to got
      holds.take(task, source.place, key, n)
      left -= n
      if (n < want) return taken to source
    }
    if (left > 0) dropped.firstOrNull { held[it.place]?.get(key) != null }?.let { return taken to it }
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
      for (slot in Slots.STORAGE) bundles(list, "${Blocks.inventory(player)}#$slot", 1, true, { Slots.copy(inventory().getItem(slot)) }, { inventory().setItem(slot, it ?: ItemStack.EMPTY) })
      if (enderNear(player, radius)) {
        val place = "ender:${player.uuid}"
        val ender = { owner.player.enderChestInventory }
        list += Source(place, true, null, { Slots.read(ender()) }, { Slots.write(ender(), it) })
        for (slot in 0 until ender().containerSize) bundles(list, "$place#$slot", 1, true, { Slots.copy(ender().getItem(slot)) }, { ender().setItem(slot, it ?: ItemStack.EMPTY) })
      }
      Blocks.near(player, radius) { Blocks.storage(it.state) }.map(Blocks::root).distinct().forEach { block ->
        // A farm's output or a sorter's chest (a hopper on it) stays out, as it does for sorting.
        if (Blocks.inventory(block) == null || Blocks.unsuitable(player, block) != null) return@forEach
        list += Source(Blocks.place(block), false, block, { Blocks.inventory(block)?.let(Slots::read).orEmpty() }, { items -> Blocks.inventory(block)?.let { Slots.write(it, items) } })
        for (slot in 0 until (Blocks.inventory(block)?.containerSize ?: 0)) {
          bundles(list, "${Blocks.place(block)}#$slot", 1, false, { Blocks.inventory(block)?.getItem(slot)?.let(Slots::copy) }, { item -> Blocks.inventory(block)?.setItem(slot, item ?: ItemStack.EMPTY) }, block)
        }
      }
      return Sources(list)
    }

    /**
     * Whether a chest is still one to take from or put into for [player]: in their world, within [reach], and fit as
     * [Blocks.unsuitable] says; with [adding] a sorter's input chest counts too.
     */
    fun fits(player: ServerPlayer, block: BlockAt, reach: Int, adding: Boolean = false): Boolean =
      block.level == player.level() && block.center.distanceTo(player.position()) <= reach && Blocks.unsuitable(player, block, adding = adding) == null

    /** Whether an ender chest stands within [radius]: only then is the player's own a source, as in the game. */
    fun enderNear(player: ServerPlayer, radius: Int) = Blocks.near(player, radius) { it.state.`is`(GameBlocks.ENDER_CHEST) }.isNotEmpty()

    /** A bundle at [get] becomes a source, and each bundle inside it too, down to [BUNDLE_DEPTH]. */
    private fun bundles(list: MutableList<Source>, place: String, depth: Int, own: Boolean, get: () -> ItemStack?, set: (ItemStack?) -> Unit, chest: BlockAt? = null) {
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
      list += Source(place, own, null, read, write, chest)
      repeat(contents.size()) { index ->
        bundles(list, "$place/$index", depth + 1, own, { read().getOrNull(index) }, chest = chest, set = { inner ->
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

/**
 * Whether [player] can take items now: online and alive. On the death screen the inventory is already gone, and what
 * goes into it then is lost at the respawn, which makes a new player.
 */
fun present(player: ServerPlayer) = !player.hasDisconnected() && player.isAlive

/**
 * Hands [items] to [player] as the game has them now: into [preferred] if it has room, then the inventory, then at
 * their feet. While they're dead or gone nothing goes into the inventory: it drops at [at] (the furnace or stand a job
 * works at), else where they are. Returns how many fell.
 */
fun give(player: ServerPlayer, items: List<ItemStack>, preferred: Sources.Source? = null, at: BlockAt? = null): Int {
  val now = player.audience.player
  val here = present(now)
  var dropped = 0
  items.forEach { item ->
    var left: ItemStack? = item
    // A bundle source refuses nothing, so only containers and the inventory take items back.
    if (preferred != null && !preferred.place.contains('#')) left = left?.let(preferred::add)
    if (here) left = left?.let { Slots.add(now, it) }
    left?.let {
      if (here || at == null) Slots.drop(now, it) else Slots.drop(at, it)
      dropped += it.count
    }
  }
  return dropped
}
