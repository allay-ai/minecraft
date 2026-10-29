package chat.allay.spigot

import chat.allay.core.*
import chat.allay.core.tools.*
import chat.allay.spigot.tools.BookItems
import chat.allay.spigot.tools.id

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import chat.allay.spigot.menus.AnvilPlanMenu
import chat.allay.spigot.menus.BrewingMenu
import chat.allay.spigot.menus.EnchantMenu
import chat.allay.spigot.tools.Anvils
import chat.allay.spigot.tools.Catalogs
import chat.allay.spigot.tools.BrewingRecipes
import chat.allay.spigot.menus.RecipeMenu
import chat.allay.spigot.menus.VillagerMenu
import chat.allay.spigot.tools.Trails
import chat.allay.core.tools.Books
import chat.allay.core.tools.Button
import chat.allay.core.tools.ButtonStore
import chat.allay.core.tools.Dimensions
import chat.allay.core.tools.Facts
import chat.allay.spigot.tools.Features
import chat.allay.spigot.tools.InteractTools
import chat.allay.spigot.tools.ToolContext
import chat.allay.spigot.tools.Locator
import chat.allay.core.tools.ToolError
import chat.allay.spigot.tools.Villagers

/**
 * The buttons that reach into the world on Bukkit: the way to a spot or a player, the recipe, enchanting, brewing, anvil,
 * and villager windows, books, and automation's replies, picking, and undo. [Clicks] in the core handles the rest.
 */
class GameClicks(
  private val plugin: JavaPlugin,
  private val features: Features,
) : Clicks.Game {
  private val ui get() = features.config().ui
  private fun lang(player: Player) = features.locales.of(player)

  /** Checked as the old `add_buttons` tool checked it, with the asker's view of the world. */
  override fun check(run: Run, player: PlayerAudience, tag: InlineButtons.Part.Tag): Button =
    InteractTools.fromTag(ToolContext(run.sender, features, task = run.task, others = run.others), player.player, tag)

  override fun press(player: PlayerAudience, button: Button) {
    val bukkit = player.player
    val lang = lang(bukkit)
    when (button) {
      is Button.Point -> point(bukkit, button)
      is Button.Follow -> follow(bukkit, button)
      is Button.Recipe -> recipe(bukkit, button.item)
      is Button.Enchants -> enchants(bukkit)
      is Button.Brewing -> brewing(bukkit, button.potion, button.form)
      is Button.Anvil -> anvil(bukkit, button.slots, button.target)
      is Button.Villagers -> villagers(bukkit, button.item)
      is Button.ReadBook -> features.books.find(bukkit.uniqueId, button.book)?.let { bukkit.openBook(BookItems.item(it, ui.bookAuthor)) }
        ?: lang.send(bukkit, "buttons.book_gone")
      is Button.TakeBook -> features.books.find(bukkit.uniqueId, button.book)?.let { book ->
        // Free on purpose: a book of the assistant's own text is no gear, so the “no items” rule makes room for it.
        bukkit.inventory.addItem(BookItems.item(book, ui.bookAuthor)).values.forEach { bukkit.world.dropItemNaturally(bukkit.location, it) }
        lang.send(bukkit, "buttons.book_taken")
      } ?: lang.send(bukkit, "buttons.book_gone")
      is Button.Reply -> features.automation.reply(bukkit, button.task, button.label, button.reply)
      is Button.Pick -> features.automation.picking.button(bukkit, button.action)
      is Button.Undo -> try {
        val restored = features.automation.undo.restore(bukkit, features.config().automation.undoMinutes)
        if (restored.chests > 0) lang.send(bukkit, "automation.restored", "count" to restored.chests)
        else lang.send(bukkit, "automation.restored_inventory")
      } catch (error: ToolError) {
        lang.send(bukkit, "automation.not_restored", "reason" to (error.message ?: ""))
      }
      // The core handles these itself.
      is Button.Ask, is Button.Command, is Button.Say, is Button.Note, is Button.Copy, is Button.ChatMode, is Button.HideTips -> Unit
    }
  }

  private fun point(player: Player, button: Button.Point) {
    if (button.dimension != null && Dimensions.normalize(button.dimension) != Dimensions.id(player.world)) {
      lang(player).send(player, "buttons.other_dimension")
      return
    }
    val target = Location(player.world, button.x + 0.5, button.y.toDouble(), button.z + 0.5)
    val lang = lang(player)
    val config = features.config()
    val audience = player.audience
    Trails.show(plugin, player, target, config.tools.trailSeconds, bar = TrailBar.of(config, audience, lang), ended = { TrailNotes.ended(lang, audience, it) }) {
      TrailNotes.arrived(lang, audience, it)
    }
    player.compassTarget = target
    val dx = target.x - player.location.x
    val dz = target.z - player.location.z
    lang.send(player, "buttons.path_shown", "distance" to Math.round(Math.hypot(dx, dz)), "direction" to lang.text("directions.${Facts.direction(dx, dz)}"))
  }

  private fun follow(player: Player, button: Button.Follow) {
    val lang = lang(player)
    // Checked again on the press: the button may be an hour old, and the player may have hidden since.
    val target = Locator.named(player, button.player)?.takeIf { Locator.shows(player, it) }
      ?: return lang.send(player, "buttons.player_hidden", "name" to button.player)
    val config = features.config()
    Trails.follow(plugin, player, target, config.tools.followSeconds, bar = TrailBar.of(config, player.audience, lang),
      found = { lang.send(player, "messages.found", "name" to target.name) },
      lost = { lang.send(player, "messages.trail_lost", "name" to target.name) })
    val dx = target.location.x - player.location.x
    val dz = target.location.z - player.location.z
    lang.send(player, "buttons.path_to_player", "name" to target.name, "distance" to Math.round(target.location.distance(player.location)), "direction" to lang.text("directions.${Facts.direction(dx, dz)}"))
  }

  /** The recipe button. */
  fun recipe(player: Player, item: String) {
    val target = try {
      InteractTools.recipeTarget(Catalogs.build(features.expansions, features.names()), item)
    } catch (_: ToolError) {
      lang(player).send(player, "messages.unknown_item", "item" to item)
      return
    }
    if (RecipeMenu.recipesFor(target).isEmpty()) {
      lang(player).send(player, "messages.no_recipe")
      return
    }
    RecipeMenu(player, lang(player), target).open()
  }

  /** The enchanting button: the window for whatever is in hand right now. */
  fun enchants(player: Player) {
    val item = player.inventory.itemInMainHand.takeUnless { it.type.isAir || it.type == Material.BOOK || it.type == Material.ENCHANTED_BOOK }
      ?: return lang(player).send(player, "messages.hold_item")
    if (EnchantMenu.applicable(item).isEmpty()) return lang(player).send(player, "messages.no_enchants")
    EnchantMenu(player, lang(player), item.clone()).open()
  }

  /** The brewing button: the steps from a water bottle, as the game's recipes have them. */
  fun brewing(player: Player, potion: String, form: String?) {
    val lang = lang(player)
    val chain = try {
      val (brews, target) = BrewingRecipes.target(player.server, potion, form)
      Brewing.chain(brews, target)?.let { target to it }
    } catch (_: ToolError) {
      null
    } ?: return player.sendMessage(Legacy.withText(lang.text("messages.no_brewing", "potion" to Legacy.SLOT), potion))
    BrewingMenu(player, lang, chain.first, chain.second).open()
  }

  /** The anvil button: the cheapest way to merge what the player has onto the item in [target], or in hand. */
  fun anvil(player: Player, slots: List<Int>?, target: Int? = null) {
    val planned = try {
      Anvils.plan(player, slots, target ?: player.inventory.heldItemSlot)
    } catch (error: ToolError) {
      return lang(player).send(player, "messages.hold_item")
    } ?: return lang(player).send(player, "messages.anvil_nothing")
    AnvilPlanMenu(player, lang(player), planned).open()
  }

  /** The villagers button. */
  fun villagers(player: Player, item: String?) {
    val material = item?.let { runCatching { InteractTools.material(it) }.getOrNull() }
    val found = Villagers.around(player, features.villagerRadius(), material)
    if (found.isEmpty()) return lang(player).send(player, "messages.no_villagers")
    VillagerMenu(player, plugin, lang(player), found).open()
  }
}
