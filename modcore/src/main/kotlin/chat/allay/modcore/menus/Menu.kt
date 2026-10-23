package chat.allay.modcore.menus

import chat.allay.core.*
import chat.allay.modcore.Hooks
import chat.allay.modcore.Texts

import net.minecraft.core.component.DataComponents
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.SimpleContainer
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.inventory.MenuType
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.item.component.TooltipDisplay

/**
 * A window that only shows things. Every item in it is a picture: its [View] takes no click, drag, or shift-click, so
 * nothing can leave it, and clicks on its own slots become [click] calls instead. It lives on the server alone, as a
 * chest the client already knows how to draw.
 */
abstract class Menu(val player: ServerPlayer, size: Int, private val title: String) {
  private val container = SimpleContainer(size)

  /** A click on one of the window's own slots. */
  abstract fun click(slot: Int)

  /** The same, for windows where a shift-click means more, such as confirming. */
  open fun click(slot: Int, shift: Boolean) = click(slot)

  /** Once a second while the window is open: cycling ingredients and the like. */
  open fun tick() {}

  fun open() {
    draw()
    val rows = container.containerSize / 9
    player.openMenu(SimpleMenuProvider({ id, inventory, _ -> View(id, inventory, this, rows) }, Texts.legacy(Legacy.color(title))))
  }

  /** Closes the window, as Bukkit's `closeInventory`. */
  fun close() = player.closeContainer()

  abstract fun draw()

  protected fun set(slot: Int, item: ItemStack?) = container.setItem(slot, item ?: ItemStack.EMPTY)

  protected fun clear() {
    for (slot in 0 until container.containerSize) container.setItem(slot, FILLER.copy())
  }

  /** The chest the client draws; every way to move an item through it ends here. */
  class View(id: Int, inventory: Inventory, val menu: Menu, rows: Int) : ChestMenu(TYPES.getValue(rows), id, inventory, menu.container, rows) {
    override fun clicked(slotIndex: Int, buttonNum: Int, containerInput: ContainerInput, player: Player) {
      // Shift-clicks from the player's inventory, number keys, double clicks: all of it stops here.
      if (player.uuid != menu.player.uuid || slotIndex !in 0 until menu.container.containerSize) return
      if (containerInput != ContainerInput.QUICK_CRAFT) menu.click(slotIndex, containerInput == ContainerInput.QUICK_MOVE)
    }

    override fun quickMoveStack(player: Player, slotIndex: Int): ItemStack = ItemStack.EMPTY

    override fun stillValid(player: Player) = true
  }

  companion object {
    private val TYPES = mapOf(1 to MenuType.GENERIC_9x1, 2 to MenuType.GENERIC_9x2, 3 to MenuType.GENERIC_9x3, 4 to MenuType.GENERIC_9x4, 5 to MenuType.GENERIC_9x5, 6 to MenuType.GENERIC_9x6)

    private val FILLER = ItemStack(Items.STAINED_GLASS_PANE.gray()).apply { set(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay(true, linkedSetOf())) }

    /** Ticks open windows once a second; the mod calls it at start. */
    fun start() {
      var ticks = 0
      Hooks.tick += {
        if (++ticks % 20 == 0) chat.allay.modcore.Mod.platform?.server?.playerList?.players?.forEach { (it.containerMenu as? View)?.menu?.tick() }
      }
    }

    /** An item with our own name and lore; [name] null keeps the game's name in the player's language. */
    fun icon(item: Item, name: String? = null, lore: List<String> = emptyList(), amount: Int = 1): ItemStack =
      ItemStack(item, amount.coerceIn(1, item.defaultMaxStackSize)).also { stack -> stack.lore(name, lore) }

    fun ItemStack.lore(name: String?, lore: List<String>): ItemStack = apply {
      name?.let { set(DataComponents.CUSTOM_NAME, Texts.legacy(Legacy.color(it))) }
      if (lore.isNotEmpty()) {
        val before = get(DataComponents.LORE)?.lines().orEmpty()
        set(DataComponents.LORE, ItemLore(before + lore.map { Texts.legacy(Legacy.color(it)) }))
      }
    }
  }
}
