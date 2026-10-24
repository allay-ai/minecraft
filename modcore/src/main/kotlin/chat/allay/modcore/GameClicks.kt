package chat.allay.modcore

import chat.allay.core.*
import chat.allay.core.tools.*
import chat.allay.modcore.menus.AnvilPlanMenu
import chat.allay.modcore.menus.BrewingMenu
import chat.allay.modcore.menus.EnchantMenu
import chat.allay.modcore.menus.RecipeMenu
import chat.allay.modcore.menus.VillagerMenu
import chat.allay.modcore.tools.Anvils
import chat.allay.modcore.tools.BookItems
import chat.allay.modcore.tools.BrewingRecipes
import chat.allay.modcore.tools.Catalogs
import chat.allay.modcore.tools.Features
import chat.allay.modcore.tools.InteractTools
import chat.allay.modcore.tools.Locator
import chat.allay.modcore.tools.ToolContext
import chat.allay.modcore.tools.Trails
import chat.allay.modcore.tools.Villagers
import chat.allay.modcore.tools.id

import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket
import net.minecraft.network.protocol.game.ClientboundOpenBookPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.inventory.InventoryMenu
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.Block
import net.minecraft.world.phys.Vec3

/**
 * The buttons that reach into the world in the mods: the way to a spot or a player, the recipe, enchanting, brewing, anvil,
 * and villager windows, books, and automation's replies, picking, and undo. [Clicks] in the core handles the rest.
 */
class GameClicks(private val features: Features) : Clicks.Game {
  private val ui get() = features.config().ui
  private fun lang(player: ServerPlayer) = features.locales.of(player)

  /** Checked as the old `add_buttons` tool checked it, with the asker's view of the world. */
  override fun check(run: Run, player: PlayerAudience, tag: InlineButtons.Part.Tag): Button =
    InteractTools.fromTag(ToolContext(run.audience, features, task = run.task, others = run.others), player.player, tag)

  override fun press(player: PlayerAudience, button: Button) {
    val game = player.player
    val lang = lang(game)
    when (button) {
      is Button.Point -> point(game, button)
      is Button.Follow -> follow(game, button)
      is Button.Recipe -> recipe(game, button.item)
      is Button.Enchants -> enchants(game)
      is Button.Brewing -> brewing(game, button.potion, button.form)
      is Button.Anvil -> anvil(game, button.slots, button.target)
      is Button.Villagers -> villagers(game, button.item)
      is Button.ReadBook -> features.books.find(game.uuid, button.book)?.let { openBook(game, BookItems.item(it, ui.bookAuthor)) }
        ?: lang.send(player, "buttons.book_gone")
      is Button.TakeBook -> features.books.find(game.uuid, button.book)?.let { book ->
        // Free on purpose: a book of the assistant's own text is no gear, so the “no items” rule makes room for it.
        val item = BookItems.item(book, ui.bookAuthor)
        game.inventory.add(item)
        // What doesn't fit falls at their feet, as Bukkit's `dropItemNaturally`.
        if (!item.isEmpty) Block.popResource(game.level(), game.blockPosition(), item)
        lang.send(player, "buttons.book_taken")
      } ?: lang.send(player, "buttons.book_gone")
      is Button.Reply -> features.automation.reply(game, button.task, button.label, button.reply)
      is Button.Pick -> features.automation.picking.button(game, button.action)
      is Button.Undo -> try {
        val restored = features.automation.undo.restore(game, features.config().automation.undoMinutes)
        if (restored.chests > 0) lang.send(player, "automation.restored", "count" to restored.chests)
        else lang.send(player, "automation.restored_inventory")
      } catch (error: ToolError) {
        lang.send(player, "automation.not_restored", "reason" to (error.message ?: ""))
      }
      // The core handles these itself.
      is Button.Ask, is Button.Command, is Button.Say, is Button.Note, is Button.Copy, is Button.ChatMode, is Button.HideTips -> Unit
    }
  }

  /**
   * Opens a written book without giving it, as Bukkit's `openBook`: the client reads the book from the hand, so it
   * hears of the book there for a moment and of the real item right after. The inventory itself never changes.
   */
  private fun openBook(player: ServerPlayer, book: ItemStack) {
    val slot = InventoryMenu.USE_ROW_SLOT_START + player.inventory.selectedSlot
    val menu = player.inventoryMenu
    player.connection.send(ClientboundContainerSetSlotPacket(menu.containerId, menu.stateId, slot, book))
    player.connection.send(ClientboundOpenBookPacket(InteractionHand.MAIN_HAND))
    player.connection.send(ClientboundContainerSetSlotPacket(menu.containerId, menu.stateId, slot, player.inventory.getSelectedItem().copy()))
  }

  private fun point(player: ServerPlayer, button: Button.Point) {
    val lang = lang(player)
    if (button.dimension != null && Dimensions.normalize(button.dimension) != Dimensions.id(player.level())) {
      lang.send(player.audience, "buttons.other_dimension")
      return
    }
    val target = Vec3(button.x + 0.5, button.y.toDouble(), button.z + 0.5)
    Trails.compass(player, player.level().dimension(), BlockPos(button.x, button.y, button.z))
    Trails.show(features.scheduler, player, Trails.Target(player.level().dimension(), target), features.config().tools.trailSeconds) {
      lang(player).send(player.audience, "messages.arrived")
    }
    val dx = target.x - player.x
    val dz = target.z - player.z
    lang.send(player.audience, "buttons.path_shown", "distance" to Math.round(Math.hypot(dx, dz)), "direction" to lang.text("directions.${Facts.direction(dx, dz)}"))
  }

  private fun follow(player: ServerPlayer, button: Button.Follow) {
    val lang = lang(player)
    // Checked again on the press: the button may be an hour old, and the player may have hidden since.
    val target = Locator.named(player, button.player)?.takeIf { Locator.shows(player, it) }
      ?: return lang.send(player.audience, "buttons.player_hidden", "name" to button.player)
    val name = target.gameProfile.name
    Trails.follow(features.scheduler, player, target, features.config().tools.followSeconds,
      found = { lang.send(player.audience, "messages.found", "name" to name) },
      lost = { lang.send(player.audience, "messages.trail_lost", "name" to name) })
    val dx = target.x - player.x
    val dz = target.z - player.z
    lang.send(player.audience, "buttons.path_to_player", "name" to name, "distance" to Math.round(target.position().distanceTo(player.position())), "direction" to lang.text("directions.${Facts.direction(dx, dz)}"))
  }

  /** The recipe button. */
  fun recipe(player: ServerPlayer, item: String) {
    val found = try {
      InteractTools.recipeTarget(Catalogs.build(player.level().server, features.expansions, features.names()), item)
    } catch (_: ToolError) {
      lang(player).send(player.audience, "messages.unknown_item", "item" to item)
      return
    }
    if (RecipeMenu.recipesFor(player, found).isEmpty()) {
      lang(player).send(player.audience, "messages.no_recipe")
      return
    }
    RecipeMenu(player, lang(player), found).open()
  }

  /** The enchanting button: the window for whatever is in hand right now. */
  fun enchants(player: ServerPlayer) {
    val item = player.inventory.getSelectedItem().takeUnless { it.isEmpty || it.item == Items.BOOK || it.item == Items.ENCHANTED_BOOK }
      ?: return lang(player).send(player.audience, "messages.hold_item")
    if (EnchantMenu.applicable(player, item).isEmpty()) return lang(player).send(player.audience, "messages.no_enchants")
    EnchantMenu(player, lang(player), item.copy()).open()
  }

  /** The brewing button: the steps from a water bottle, as the game's recipes have them. */
  fun brewing(player: ServerPlayer, potion: String, form: String?) {
    val lang = lang(player)
    val chain = try {
      val (brews, target) = BrewingRecipes.target(potion, form)
      Brewing.chain(brews, target)?.let { target to it }
    } catch (_: ToolError) {
      null
    } ?: return player.audience.send(Legacy.withText(lang.text("messages.no_brewing", "potion" to Legacy.SLOT), potion))
    BrewingMenu(player, lang, chain.first, chain.second).open()
  }

  /** The anvil button: the cheapest way to merge what the player has onto the item in [target], or in hand. */
  fun anvil(player: ServerPlayer, slots: List<Int>?, target: Int? = null) {
    val planned = try {
      Anvils.plan(player, slots, target ?: player.inventory.selectedSlot)
    } catch (error: ToolError) {
      return lang(player).send(player.audience, "messages.hold_item")
    } ?: return lang(player).send(player.audience, "messages.anvil_nothing")
    AnvilPlanMenu(player, lang(player), planned).open()
  }

  /** The villagers button. */
  fun villagers(player: ServerPlayer, item: String?) {
    val wanted = item?.let { runCatching { InteractTools.item(it) }.getOrNull() }
    val found = Villagers.around(player, features.villagerRadius(), wanted)
    if (found.isEmpty()) return lang(player).send(player.audience, "messages.no_villagers")
    VillagerMenu(player, features.scheduler, lang(player), found).open()
  }
}
