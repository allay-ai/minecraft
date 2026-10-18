package chat.allay.spigot

import chat.allay.core.*
import chat.allay.core.tools.*
import chat.allay.spigot.tools.BookItems
import chat.allay.spigot.tools.id

import net.md_5.bungee.api.chat.BaseComponent
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.HoverEvent
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.chat.hover.content.Text
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import chat.allay.spigot.Minecraft.formatMessage
import chat.allay.spigot.menus.AnvilPlanMenu
import chat.allay.spigot.menus.BrewingMenu
import chat.allay.spigot.menus.EnchantMenu
import chat.allay.spigot.tools.Anvils
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
import chat.allay.spigot.tools.Locator
import chat.allay.core.tools.Note
import chat.allay.core.tools.ToolError
import chat.allay.spigot.tools.Villagers

/**
 * Buttons under answers and the windows behind them. A button runs `/allay do <id>`; the id leads to an action
 * this player was offered, so nobody can press a button they weren't shown.
 */
class Clicks(
  private val plugin: JavaPlugin,
  private val config: Config,
  private val features: Features,
  private val store: ButtonStore,
  /** Asks for the player, publicly when the third argument says so. */
  private val ask: (Player, String, Boolean) -> Unit,
) {
  private val ui = config.ui
  private fun lang(player: Player) = features.locales.of(player)

  /** Draws one chat line of `[label]` buttons. */
  fun show(player: Player, buttons: List<Button>) {
    if (buttons.isEmpty()) return
    val lang = lang(player)
    val parts = mutableListOf<BaseComponent>()
    buttons.forEachIndexed { index, button ->
      if (index > 0) parts += TextComponent(" ")
      val label = button.label.ifBlank { defaultLabel(lang, button) }
      parts += if (button is Button.Copy) {
        copyable(lang.text("buttons.format", "label" to label), button.text, hover(lang, player, button))
      } else {
        clickable(lang.text("buttons.format", "label" to label), store.add(player.uniqueId, button), hover(lang, player, button))
      }
    }
    player.spigot().sendMessage(*parts.toTypedArray())
  }

  /** The quiet tip under a second `/ask`, like Claude Code's: chat mode exists, with buttons to try it or hide the tip. */
  fun tip(player: Player) {
    val lang = lang(player)
    val on = store.add(player.uniqueId, Button.ChatMode(""))
    val off = store.add(player.uniqueId, Button.HideTips(""))
    player.spigot().sendMessage(
      *TextComponent.fromLegacyText(formatMessage(lang.text("chat_mode.tip"))),
      TextComponent("  "),
      clickable(lang.text("chat_mode.tip_enable"), on, lang.text("chat_mode.tip_enable_hover")),
      TextComponent(" "),
      clickable(lang.text("chat_mode.tip_hide"), off, lang.text("chat_mode.tip_hide_hover")),
    )
  }

  private fun clickable(text: String, id: String, hover: String) = TextComponent(*TextComponent.fromLegacyText(formatMessage(text))).apply {
    clickEvent = ClickEvent(ClickEvent.Action.RUN_COMMAND, "/allay do $id")
    hoverEvent = HoverEvent(HoverEvent.Action.SHOW_TEXT, Text(TextComponent.fromLegacyText(formatMessage(hover))))
  }

  private fun copyable(text: String, copied: String, hover: String) = TextComponent(*TextComponent.fromLegacyText(formatMessage(text))).apply {
    clickEvent = ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, copied)
    hoverEvent = HoverEvent(HoverEvent.Action.SHOW_TEXT, Text(TextComponent.fromLegacyText(formatMessage(hover))))
  }

  private fun defaultLabel(lang: Lang, button: Button) = when (button) {
    is Button.ReadBook -> lang.text("buttons.read_book")
    is Button.TakeBook -> lang.text("buttons.take_book")
    else -> "?"
  }

  private fun hover(lang: Lang, player: Player, button: Button): String = when (button) {
    is Button.Ask -> lang.text("buttons.hover.ask", "question" to button.question)
    is Button.Point -> lang.text("buttons.hover.point", "x" to button.x, "y" to button.y, "z" to button.z)
    is Button.Follow -> lang.text("buttons.hover.follow", "name" to button.player)
    is Button.Note -> lang.text("buttons.hover.note", "text" to button.text, "x" to button.x, "y" to button.y, "z" to button.z)
    is Button.Copy -> lang.text("buttons.hover.copy", "text" to button.text)
    is Button.Recipe -> lang.text("buttons.hover.recipe")
    is Button.Enchants -> lang.text("buttons.hover.enchants")
    is Button.Brewing -> lang.text("buttons.hover.brewing")
    is Button.Anvil -> lang.text("buttons.hover.anvil")
    is Button.Villagers -> lang.text("buttons.hover.villagers")
    is Button.ReadBook -> lang.text("buttons.hover.read_book", "title" to (features.books.find(player.uniqueId, button.book)?.title ?: ""))
    is Button.TakeBook -> lang.text("buttons.hover.take_book", "title" to (features.books.find(player.uniqueId, button.book)?.title ?: ""))
    is Button.ChatMode -> lang.text("chat_mode.tip_enable_hover")
    is Button.HideTips -> lang.text("chat_mode.tip_hide_hover")
    is Button.Reply -> lang.text("buttons.hover.reply")
    is Button.Pick -> lang.text("buttons.hover.pick_${button.action}")
    is Button.Undo -> lang.text("buttons.hover.undo")
  }

  /** `/allay do <id>`. */
  fun press(player: Player, id: String) {
    val lang = lang(player)
    when (val button = store.take(player.uniqueId, id)) {
      null -> lang.send(player, "buttons.expired")
      is Button.Ask -> {
        // A public question shows itself to everyone; one the player may not ask publicly stays theirs.
        val public = button.public && player.hasPermission(Permissions.ASKALL)
        if (!public) lang.send(player, "buttons.question", "question" to button.question)
        ask(player, button.question, public)
      }
      is Button.Point -> point(player, button)
      is Button.Follow -> follow(player, button)
      is Button.Note -> note(player, button)
      // Copying happens in the client and never reaches the store; kept for a complete `when`.
      is Button.Copy -> Unit
      is Button.Recipe -> recipe(player, button.item)
      is Button.Enchants -> enchants(player)
      is Button.Brewing -> brewing(player, button.potion, button.form)
      is Button.Anvil -> anvil(player, button.slots)
      is Button.Villagers -> villagers(player, button.item)
      is Button.ReadBook -> features.books.find(player.uniqueId, button.book)?.let { player.openBook(BookItems.item(it, ui.bookAuthor)) }
        ?: lang.send(player, "buttons.book_gone")
      is Button.TakeBook -> features.books.find(player.uniqueId, button.book)?.let { book ->
        // Free on purpose: a book of the assistant's own text is no gear, so the “no items” rule makes room for it.
        player.inventory.addItem(BookItems.item(book, ui.bookAuthor)).values.forEach { player.world.dropItemNaturally(player.location, it) }
        lang.send(player, "buttons.book_taken")
      } ?: lang.send(player, "buttons.book_gone")
      is Button.ChatMode -> features.chat.set(player, true)
      is Button.HideTips -> features.chat.hints(player, false)
      is Button.Reply -> features.automation.reply(player, button.task, button.reply)
      is Button.Pick -> features.automation.picking.button(player, button.action)
      is Button.Undo -> try {
        lang.send(player, "automation.restored", "count" to features.automation.undo.restore(player, features.config().automation.undoMinutes))
      } catch (error: ToolError) {
        lang.send(player, "automation.not_restored", "reason" to (error.message ?: ""))
      }
    }
  }

  private fun point(player: Player, button: Button.Point) {
    if (button.dimension != null && Dimensions.normalize(button.dimension) != Dimensions.id(player.world)) {
      lang(player).send(player, "buttons.other_dimension")
      return
    }
    val target = Location(player.world, button.x + 0.5, button.y.toDouble(), button.z + 0.5)
    player.compassTarget = target
    Trails.show(plugin, player, target, features.config().tools.trailSeconds) { lang(player).send(player, "messages.arrived") }
    val dx = target.x - player.location.x
    val dz = target.z - player.location.z
    val lang = lang(player)
    lang.send(player, "buttons.path_shown", "distance" to Math.round(Math.hypot(dx, dz)), "direction" to lang.text("directions.${Facts.direction(dx, dz)}"))
  }

  private fun follow(player: Player, button: Button.Follow) {
    val lang = lang(player)
    // Checked again on the press: the button may be an hour old, and the player may have hidden since.
    val target = Locator.named(player, button.player)?.takeIf { Locator.shows(player, it) }
      ?: return lang.send(player, "buttons.player_hidden", "name" to button.player)
    Trails.follow(plugin, player, target, features.config().tools.followSeconds,
      found = { lang.send(player, "messages.found", "name" to target.name) },
      lost = { lang.send(player, "messages.trail_lost", "name" to target.name) })
    val dx = target.location.x - player.location.x
    val dz = target.location.z - player.location.z
    lang.send(player, "buttons.path_to_player", "name" to target.name, "distance" to Math.round(target.location.distance(player.location)), "direction" to lang.text("directions.${Facts.direction(dx, dz)}"))
  }

  private fun note(player: Player, button: Button.Note) {
    val current = Dimensions.id(player.world)
    val dimension = Dimensions.normalize(button.dimension) ?: current
    try {
      features.notes.of(player.uniqueId).add { id ->
        Note(id, button.text, button.x, button.y, button.z, if (dimension == current) player.world.name else null, dimension, System.currentTimeMillis())
      }
      features.notes.save(player.uniqueId)
      lang(player).send(player, "buttons.note_saved", "text" to button.text)
    } catch (error: ToolError) {
      player.sendMessage(formatMessage("&c${error.message}"))
    }
  }

  /** `/allay recipe <item>` and the recipe button. */
  fun recipe(player: Player, item: String) {
    val material = try {
      InteractTools.material(item)
    } catch (_: ToolError) {
      lang(player).send(player, "messages.unknown_item", "item" to item)
      return
    }
    if (RecipeMenu.recipesFor(material).isEmpty()) {
      lang(player).send(player, "messages.no_recipe")
      return
    }
    RecipeMenu(player, lang(player), material).open()
  }

  /** `/allay enchants` and the enchanting button: the window for whatever is in hand right now. */
  fun enchants(player: Player) {
    val item = player.inventory.itemInMainHand.takeUnless { it.type.isAir || it.type == Material.BOOK || it.type == Material.ENCHANTED_BOOK }
      ?: return lang(player).send(player, "messages.hold_item")
    if (EnchantMenu.applicable(item).isEmpty()) return lang(player).send(player, "messages.no_enchants")
    EnchantMenu(player, lang(player), item.clone()).open()
  }

  /** `/allay brewing <potion>` and the brewing button: the steps from a water bottle, as the game's recipes have them. */
  fun brewing(player: Player, potion: String, form: String?) {
    val lang = lang(player)
    val chain = try {
      val (brews, target) = BrewingRecipes.target(player.server, potion, form)
      Brewing.chain(brews, target)?.let { target to it }
    } catch (_: ToolError) {
      null
    } ?: return player.sendMessage(Minecraft.withText(lang.text("messages.no_brewing", "potion" to Minecraft.SLOT), potion))
    BrewingMenu(player, lang, chain.first, chain.second).open()
  }

  /** `/allay anvil` and the anvil button: the cheapest way to merge what the player has onto the item in hand. */
  fun anvil(player: Player, slots: List<Int>?) {
    val planned = try {
      Anvils.plan(player, slots)
    } catch (error: ToolError) {
      return lang(player).send(player, "messages.hold_item")
    } ?: return lang(player).send(player, "messages.anvil_nothing")
    AnvilPlanMenu(player, lang(player), planned).open()
  }

  /** `/allay villagers` and the villagers button. */
  fun villagers(player: Player, item: String?) {
    val material = item?.let { runCatching { InteractTools.material(it) }.getOrNull() }
    val found = Villagers.around(player, features.villagerRadius(), material)
    if (found.isEmpty()) return lang(player).send(player, "messages.no_villagers")
    VillagerMenu(player, plugin, lang(player), found).open()
  }
}
