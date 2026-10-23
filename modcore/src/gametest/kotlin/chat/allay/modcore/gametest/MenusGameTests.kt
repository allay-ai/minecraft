package chat.allay.modcore.gametest

import chat.allay.core.Lang
import chat.allay.core.Legacy
import chat.allay.core.Run
import chat.allay.core.tools.Button
import chat.allay.core.tools.InlineButtons
import chat.allay.core.tools.ToolError
import chat.allay.modcore.Mod
import chat.allay.modcore.ModGame
import chat.allay.modcore.ModPlayer
import chat.allay.modcore.SourceAsker
import chat.allay.modcore.Texts
import chat.allay.modcore.gametest.Players.survivor
import chat.allay.modcore.menus.AnvilPlanMenu
import chat.allay.modcore.menus.BrewingMenu
import chat.allay.modcore.menus.EnchantMenu
import chat.allay.modcore.menus.LanguageMenu
import chat.allay.modcore.menus.Menu
import chat.allay.modcore.menus.Menus
import chat.allay.modcore.menus.RecipeMenu
import chat.allay.modcore.menus.SettingsMenu
import chat.allay.modcore.menus.VillagerMenu
import chat.allay.modcore.of
import chat.allay.modcore.tools.Anvils
import chat.allay.modcore.tools.Trails

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.network.HashedStack
import net.minecraft.network.protocol.game.ClientboundOpenBookPacket
import net.minecraft.network.protocol.game.ClientboundOpenScreenPacket
import net.minecraft.network.protocol.game.ClientboundSetDefaultSpawnPositionPacket
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.item.enchantment.ItemEnchantments

/** The windows that only show pictures, and the answer buttons that open them, with real players. */
class MenusGameTests {
  private fun equal(helper: GameTestHelper, actual: Any?, expected: Any?, what: String) =
    helper.assertTrue(actual == expected, "$what: expected $expected, got $actual")

  private val allay get() = Mod.allay!!
  private val game get() = allay.game as ModGame

  private fun lang(player: ServerPlayer): Lang = Players.features.locales.of(player)

  /** A line from the language file as the player reads it, without colors. */
  private fun text(player: ServerPlayer, key: String, vararg values: Pair<String, Any?>) = Texts.legacy(Legacy.color(lang(player).text(key, *values))).string

  private fun view(player: ServerPlayer): Menu.View? = player.containerMenu as? Menu.View

  /** The window's title as the client got it with the last window that opened. */
  private fun title(player: Players.Survivor): String? = player.packets().filterIsInstance<ClientboundOpenScreenPacket>().lastOrNull()?.title?.string

  /** What the player and the window hold: the inventory, the window's slots, and the item on the cursor. */
  private fun snapshot(player: ServerPlayer): List<ItemStack> {
    val view = view(player)!!
    return (0 until player.inventory.containerSize).map { player.inventory.getItem(it).copy() } +
      (0 until view.slots.size - 36).map { view.getSlot(it).item.copy() } + view.carried.copy()
  }

  private fun same(a: List<ItemStack>, b: List<ItemStack>) = a.size == b.size && a.indices.all { ItemStack.matches(a[it], b[it]) }

  /** A click as the client sends it, through the game's own packet handling. */
  private fun click(player: ServerPlayer, slot: Int, button: Int, input: ContainerInput) {
    val menu = player.containerMenu
    player.connection.handleContainerClick(ServerboundContainerClickPacket(menu.containerId, menu.stateId, slot.toShort(), button.toByte(), input, Int2ObjectOpenHashMap(), HashedStack.EMPTY))
  }

  /** Every way to take an item out of a window, on [slot]: none of them moves anything, and the window stays open. */
  private fun takesNothing(helper: GameTestHelper, player: ServerPlayer, slot: Int, what: String) {
    val view = view(player)!!
    helper.assertTrue(!view.getSlot(slot).item.isEmpty, "$what: slot $slot shows something")
    val size = view.slots.size - 36
    val before = snapshot(player)
    val clicks = listOf(
      Triple(slot, 0, ContainerInput.PICKUP), Triple(slot, 1, ContainerInput.PICKUP), Triple(slot, 0, ContainerInput.QUICK_MOVE),
      Triple(slot, 0, ContainerInput.SWAP), Triple(slot, Inventory.SLOT_OFFHAND, ContainerInput.SWAP), Triple(slot, 0, ContainerInput.THROW),
      Triple(slot, 1, ContainerInput.THROW), Triple(slot, 2, ContainerInput.CLONE), Triple(slot, 0, ContainerInput.PICKUP_ALL),
      // A shift-click from the player's own hotbar, and a plain pick from it.
      Triple(size + 27, 0, ContainerInput.QUICK_MOVE), Triple(size + 27, 0, ContainerInput.PICKUP),
    )
    for ((at, button, input) in clicks) {
      click(player, at, button, input)
      helper.assertTrue(player.containerMenu === view, "$what: the window stays open after $input $button on $at")
      helper.assertTrue(same(before, snapshot(player)), "$what: nothing moved after $input $button on $at")
    }
  }

  private fun give(player: ServerPlayer) {
    player.inventory.setItem(0, ItemStack(Items.STONE, 32))
    player.inventory.setItem(Inventory.SLOT_OFFHAND, ItemStack(Items.TORCH, 5))
  }

  private fun done(helper: GameTestHelper, vararg players: ServerPlayer) {
    players.forEach {
      it.closeContainer()
      Trails.stop(it)
      it.discard()
    }
    helper.succeed()
  }

  private fun tag(vararg attributes: Pair<String, String>, label: String = "Go") = InlineButtons.Part.Tag(label, mapOf(*attributes))

  private fun check(player: ServerPlayer, tag: InlineButtons.Part.Tag): Button {
    val audience = ModPlayer(player)
    return game.buttons.check(Run(SourceAsker(audience), Run.Mode.ASK, lang(player), 1), audience, tag)
  }

  private fun press(player: ServerPlayer, button: Button) = game.buttons.press(ModPlayer(player), button)

  /** A press as `/allay do <id>` makes it, through the core's store. */
  private fun pressStored(player: ServerPlayer, button: Button) = allay.clicks.press(ModPlayer(player), allay.buttons.add(player.uuid, button))

  @GameTest
  fun theBrewingWindowShowsTheStepsAndTakesNothing(helper: GameTestHelper) {
    val player = survivor(helper)
    give(player)
    val button = check(player, tag("action" to "brewing", "potion" to "healing"))
    helper.assertTrue(button is Button.Brewing, "a brewing button: $button")
    player.packets()
    press(player, button)
    val menu = view(player)?.menu
    helper.assertTrue(menu is BrewingMenu, "the brewing window opens: $menu")
    equal(helper, title(player), text(player, "menus.brewing.title"), "the title")
    val view = view(player)!!
    equal(helper, view.getSlot(45).item.item, Items.BLAZE_POWDER, "the fuel")
    equal(helper, view.getSlot(49).item.item, Items.POTION, "the potion it ends with")
    equal(helper, view.getSlot(3).item.item, Items.NETHER_WART, "the first reagent")
    equal(helper, view.getSlot(53).item.item, Items.BARRIER, "close")
    equal(helper, view.getSlot(50).item.item, Items.STAINED_GLASS_PANE.gray(), "no pages for two steps")
    takesNothing(helper, player, 1, "a bottle")
    takesNothing(helper, player, 49, "the target")
    click(player, 53, 0, ContainerInput.PICKUP)
    helper.assertTrue(view(player) == null, "close closes")
    done(helper, player)
  }

  @GameTest
  fun theEnchantingWindowPlansBooksOverPages(helper: GameTestHelper) {
    val player = survivor(helper)
    player.inventory.setItem(player.inventory.selectedSlot, ItemStack(Items.DIAMOND_SWORD))
    player.packets()
    press(player, Button.Enchants(""))
    helper.assertTrue(view(player)?.menu is EnchantMenu, "the enchanting window opens")
    equal(helper, title(player), text(player, "menus.enchant.title"), "the title")
    val view = view(player)!!
    equal(helper, view.getSlot(4).item.item, Items.DIAMOND_SWORD, "the item")
    takesNothing(helper, player, 4, "the item")
    val options = (9 until 45).count { view.getSlot(it).item.item == Items.ENCHANTED_BOOK }
    helper.assertTrue(options >= 8, "a sword takes many enchantments: $options")
    // Picking every book: the ones that clash with an earlier pick stay out.
    for (slot in 9 until 9 + options) click(player, slot, 0, ContainerInput.PICKUP)
    click(player, 49, 0, ContainerInput.PICKUP)
    equal(helper, view.getSlot(45).item.item, Items.ARROW, "back to the choice")
    equal(helper, view.getSlot(50).item.item, Items.SPECTRAL_ARROW, "more steps than a page")
    equal(helper, view.getSlot(42).item.item, Items.EXPERIENCE_BOTTLE, "the fifth step's price")
    click(player, 50, 0, ContainerInput.PICKUP)
    equal(helper, view.getSlot(6).item.item, Items.EXPERIENCE_BOTTLE, "the sixth step tops the next page")
    equal(helper, view.getSlot(42).item.item, Items.STAINED_GLASS_PANE.gray(), "the second page is shorter")
    click(player, 48, 0, ContainerInput.PICKUP)
    equal(helper, view.getSlot(42).item.item, Items.EXPERIENCE_BOTTLE, "back on the first page")
    takesNothing(helper, player, 4, "a step's result")
    equal(helper, player.inventory.getSelectedItem().item, Items.DIAMOND_SWORD, "the sword stays in hand")
    helper.assertTrue(player.inventory.getSelectedItem().enchantments.isEmpty, "and unenchanted")
    done(helper, player)
  }

  @GameTest
  fun theAnvilPlanShowsWhatThePlayerHas(helper: GameTestHelper) {
    val player = survivor(helper)
    player.inventory.selectedSlot = 0
    player.inventory.setItem(0, ItemStack(Items.DIAMOND_SWORD))
    val book = ItemStack(Items.ENCHANTED_BOOK).apply {
      set(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.Mutable(ItemEnchantments.EMPTY).apply { set(Anvils.enchantment(player, "sharpness")!!, 5) }.toImmutable())
    }
    player.inventory.setItem(9, book)
    player.packets()
    pressStored(player, Button.Anvil("", null))
    helper.assertTrue(view(player)?.menu is AnvilPlanMenu, "the anvil window opens")
    equal(helper, title(player), text(player, "menus.anvil.title"), "the title")
    val view = view(player)!!
    equal(helper, view.getSlot(0).item.item, Items.DIAMOND_SWORD, "the left piece")
    equal(helper, view.getSlot(2).item.item, Items.ENCHANTED_BOOK, "the right piece")
    equal(helper, view.getSlot(4).item.item, Items.DIAMOND_SWORD, "the result")
    equal(helper, view.getSlot(49).item.item, Items.ANVIL, "the total")
    takesNothing(helper, player, 2, "the book")
    takesNothing(helper, player, 4, "the result")
    helper.assertTrue(ItemStack.matches(player.inventory.getItem(9), book), "the book stays where it was")
    done(helper, player)
  }

  @GameTest
  fun theVillagerWindowShowsTheWay(helper: GameTestHelper) {
    val player = survivor(helper)
    give(player)
    helper.spawn(EntityTypes.WANDERING_TRADER, BlockPos(3, 2, 3))
    player.packets()
    press(player, Button.Villagers("", null))
    helper.assertTrue(view(player)?.menu is VillagerMenu, "the villager window opens")
    equal(helper, title(player), text(player, "menus.villagers.title"), "the title")
    val view = view(player)!!
    equal(helper, view.getSlot(0).item.item, Items.WANDERING_TRADER_SPAWN_EGG, "the trader")
    equal(helper, view.getSlot(50).item.item, Items.STAINED_GLASS_PANE.gray(), "one trader, one page")
    val before = snapshot(player).take(player.inventory.containerSize)
    click(player, 1, 0, ContainerInput.QUICK_MOVE)
    helper.assertTrue(view(player) == null, "a click on the row shows the way and closes the window")
    helper.assertTrue((0 until player.inventory.containerSize).all { ItemStack.matches(before[it], player.inventory.getItem(it)) }, "a trade's picture stays a picture")
    helper.assertTrue(Trails.stop(player), "the trail runs")
    done(helper, player)
  }

  @GameTest
  fun theSettingsWindowSwitchesSettings(helper: GameTestHelper) {
    val player = survivor(helper)
    give(player)
    val audience = ModPlayer(player)
    player.packets()
    Menus.settings(audience, allay, Players.features) {}
    helper.assertTrue(view(player)?.menu is SettingsMenu, "the settings window opens")
    equal(helper, title(player), text(player, "menus.settings.title"), "the title")
    val view = view(player)!!
    equal(helper, view.getSlot(4).item.item, Items.BOOK, "the header")
    equal(helper, view.getSlot(10).item.item, Items.WRITABLE_BOOK, "chat mode")
    equal(helper, view.getSlot(22).item.item, Items.GLOBE_BANNER_PATTERN, "the language")
    equal(helper, view.getSlot(40).item.item, Items.BARRIER, "close")
    // A player who isn't an operator sees no admins' row.
    equal(helper, view.getSlot(38).item.item, Items.STAINED_GLASS_PANE.gray(), "no reload")
    equal(helper, view.getSlot(42).item.item, Items.STAINED_GLASS_PANE.gray(), "no status")
    takesNothing(helper, player, 4, "the header")
    val settings = Players.features.settings
    val chat = settings.of(player.uuid).chat_mode
    equal(helper, view.getSlot(19).item.item, if (chat) Items.STAINED_GLASS_PANE.lime() else Items.STAINED_GLASS_PANE.red(), "chat mode's pane")
    click(player, 10, 0, ContainerInput.PICKUP)
    equal(helper, settings.of(player.uuid).chat_mode, !chat, "chat mode switched")
    equal(helper, view.getSlot(19).item.item, if (!chat) Items.STAINED_GLASS_PANE.lime() else Items.STAINED_GLASS_PANE.red(), "its pane follows")
    val hints = settings.of(player.uuid).hints
    click(player, 20, 0, ContainerInput.PICKUP)
    equal(helper, settings.of(player.uuid).hints, !hints, "the tip switched")
    click(player, 10, 0, ContainerInput.QUICK_MOVE)
    equal(helper, settings.of(player.uuid).chat_mode, chat, "chat mode back")
    takesNothing(helper, player, 13, "the time zone")
    done(helper, player)
  }

  @GameTest
  fun theLanguageWindowPicksAndGoesBack(helper: GameTestHelper) {
    val player = survivor(helper)
    give(player)
    val audience = ModPlayer(player)
    var back = 0
    player.packets()
    Menus.languages(audience, allay) { back++ }
    helper.assertTrue(view(player)?.menu is LanguageMenu, "the language window opens")
    equal(helper, title(player), text(player, "menus.language.title"), "the title")
    val view = view(player)!!
    equal(helper, view.getSlot(10).item.item, Items.COMPASS, "automatic")
    equal(helper, view.getSlot(12).item.item, Items.PAPER, "the first language")
    equal(helper, view.getSlot(22).item.item, Items.ARROW, "back")
    takesNothing(helper, player, 0, "the frame")
    equal(helper, back, 0, "no click went back yet")
    val first = allay.languages.codes().first()
    click(player, 12, 0, ContainerInput.PICKUP)
    equal(helper, allay.languages.chosen(audience), first, "the language picked")
    equal(helper, back, 1, "back to the settings")
    allay.languages.choose(audience, null)
    done(helper, player)
  }

  @GameTest
  fun buttonsAreCheckedAsTheOldToolChecked(helper: GameTestHelper) {
    val player = survivor(helper)
    equal(helper, check(player, tag("action" to "ask", "question" to "Why is the sky blue?", label = "Sky")), Button.Ask("Sky", "Why is the sky blue?"), "ask")
    equal(helper, check(player, tag("action" to "point", "x" to "10", "y" to "64", "z" to "-20")), Button.Point("Go", 10, 64, -20, null), "point")
    equal(helper, check(player, tag("action" to "note", "text" to "Base", "x" to "1", "y" to "2", "z" to "3")), Button.Note("Go", "Base", 1, 2, 3, null), "note")
    equal(helper, check(player, tag("action" to "recipe", "item" to "crafting_table")), Button.Recipe("Go", "crafting_table"), "recipe")
    val refused = listOf(
      tag("action" to "ask"),
      tag("action" to "point", "x" to "1"),
      tag("action" to "recipe", "item" to "bedrock"),
      tag("action" to "give", "item" to "diamond"),
    ).map { runCatching { check(player, it) }.exceptionOrNull() }
    helper.assertTrue(refused.all { it is ToolError }, "a tag that can't work stays a label: $refused")
    done(helper, player)
  }

  @GameTest
  fun pressesShowTheWayAndOpenTheRecipe(helper: GameTestHelper) {
    val player = survivor(helper)
    give(player)
    val inventory = (0 until player.inventory.containerSize).map { player.inventory.getItem(it).copy() }
    player.packets()
    pressStored(player, Button.Ask("Sky", "Why is the sky blue?"))
    helper.assertTrue(text(player, "buttons.question", "question" to "Why is the sky blue?") in player.chat, "the question shows: ${player.chat}")

    val at = player.blockPosition().offset(40, 0, 0)
    pressStored(player, Button.Point("Go", at.x, at.y, at.z, null))
    helper.assertTrue(player.packets().any { it is ClientboundSetDefaultSpawnPositionPacket }, "the compass turns")
    helper.assertTrue(player.chat.any { it.contains("40") }, "the way's distance: ${player.chat}")
    helper.assertTrue(Trails.stop(player), "the trail runs")
    pressStored(player, Button.Point("Go", at.x, at.y, at.z, "the_nether"))
    helper.assertTrue(text(player, "buttons.other_dimension") in player.chat, "another dimension says so")
    helper.assertTrue(!Trails.stop(player), "and shows nothing")

    press(player, Button.Note("Base", "Base", 1, 2, 3, null))
    helper.assertTrue(view(player) == null, "a note opens nothing here")

    pressStored(player, Button.Recipe("Go", "crafting_table"))
    helper.assertTrue(view(player)?.menu is RecipeMenu, "the recipe window opens")
    player.closeContainer()
    pressStored(player, Button.Recipe("Go", "no_such_thing"))
    helper.assertTrue(text(player, "messages.unknown_item", "item" to "no_such_thing") in player.chat, "an unknown item says so")
    helper.assertTrue((0 until player.inventory.containerSize).all { ItemStack.matches(inventory[it], player.inventory.getItem(it)) }, "no button gives anything")
    done(helper, player)
  }

  @GameTest
  fun aBookOpensWithoutGivingAndIsTakenOnce(helper: GameTestHelper) {
    val player = survivor(helper)
    val book = Players.features.books.add(player.uuid, "Redstone", "Dust carries power fifteen blocks.")
    player.packets()
    pressStored(player, Button.ReadBook("Read", book.id))
    helper.assertTrue(player.packets().any { it is ClientboundOpenBookPacket }, "the book opens")
    equal(helper, books(player), 0, "reading gives nothing")

    val take = allay.buttons.add(player.uuid, Button.TakeBook("Take", book.id))
    allay.clicks.press(ModPlayer(player), take)
    equal(helper, books(player), 1, "the book is taken")
    helper.assertTrue(text(player, "buttons.book_taken") in player.chat, "and says so")
    allay.clicks.press(ModPlayer(player), take)
    equal(helper, books(player), 1, "once per book")
    helper.assertTrue(text(player, "buttons.expired") in player.chat, "the second press is spent")

    pressStored(player, Button.ReadBook("Read", "nope"))
    helper.assertTrue(text(player, "buttons.book_gone") in player.chat, "a book that's gone says so")
    done(helper, player)
  }

  private fun books(player: ServerPlayer) = (0 until player.inventory.containerSize).sumOf { slot ->
    player.inventory.getItem(slot).takeIf { it.item == Items.WRITTEN_BOOK }?.count ?: 0
  }
}
