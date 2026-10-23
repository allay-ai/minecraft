package chat.allay.spigot.menus

import chat.allay.core.*
import chat.allay.core.tools.*

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask

/**
 * A window that only shows things. Every item in it is a picture: [MenuListener] cancels every click and drag,
 * so nothing can leave it, and clicks become [click] calls instead.
 */
abstract class Menu(val player: Player, size: Int, title: String) : InventoryHolder {
  private val inventory: Inventory = Bukkit.createInventory(this, size, Legacy.color(title))

  override fun getInventory(): Inventory = inventory

  /** A click on one of the window's own slots. */
  abstract fun click(slot: Int)

  /** The same, for windows where a shift-click means more, such as confirming. */
  open fun click(slot: Int, shift: Boolean) = click(slot)

  /** Once a second while the window is open: cycling ingredients and the like. */
  open fun tick() {}

  fun open() {
    draw()
    player.openInventory(inventory)
  }

  abstract fun draw()

  protected fun set(slot: Int, item: ItemStack?) = inventory.setItem(slot, item)

  protected fun clear() {
    inventory.clear()
    for (slot in 0 until inventory.size) inventory.setItem(slot, FILLER)
  }

  companion object {
    private val FILLER = icon(Material.GRAY_STAINED_GLASS_PANE).apply { itemMeta = itemMeta!!.apply { isHideTooltip = true } }

    /** An item with our own name and lore; [name] null keeps the game's name in the player's language. */
    fun icon(material: Material, name: String? = null, lore: List<String> = emptyList(), amount: Int = 1): ItemStack =
      ItemStack(material, amount.coerceIn(1, material.maxStackSize)).also { item -> item.lore(name, lore) }

    fun ItemStack.lore(name: String?, lore: List<String>): ItemStack = apply {
      val meta = itemMeta ?: return@apply
      name?.let { meta.setDisplayName(Legacy.color(it)) }
      if (lore.isNotEmpty()) meta.lore = (meta.lore.orEmpty()) + lore.map(Legacy::color)
      itemMeta = meta
    }
  }
}

/** Cancels anything that could move items in or out of a [Menu], and hands clicks to it. */
class MenuListener(plugin: Plugin) : Listener {
  private val ticker: BukkitTask = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
    plugin.server.onlinePlayers.forEach { (it.openInventory.topInventory.holder as? Menu)?.tick() }
  }, 20L, 20L)

  @EventHandler(priority = EventPriority.LOWEST)
  fun onClick(event: InventoryClickEvent) {
    val menu = event.view.topInventory.holder as? Menu ?: return
    // Shift-clicks from the player's inventory, number keys, double clicks: all of it stops here.
    event.isCancelled = true
    if (event.clickedInventory == event.view.topInventory && event.whoClicked == menu.player) menu.click(event.rawSlot, event.isShiftClick)
  }

  @EventHandler(priority = EventPriority.LOWEST)
  fun onDrag(event: InventoryDragEvent) {
    if (event.view.topInventory.holder is Menu) event.isCancelled = true
  }

  @EventHandler(priority = EventPriority.LOWEST)
  fun onMove(event: InventoryMoveItemEvent) {
    if (event.source.holder is Menu || event.destination.holder is Menu) event.isCancelled = true
  }

  fun stop() {
    ticker.cancel()
    Bukkit.getOnlinePlayers().filter { it.openInventory.topInventory.holder is Menu }.forEach(Player::closeInventory)
  }
}
