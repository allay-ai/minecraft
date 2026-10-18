package chat.allay.spigot.menus

import chat.allay.core.*
import chat.allay.core.tools.*
import chat.allay.spigot.send

import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import chat.allay.spigot.Clicks
import chat.allay.core.Lang
import chat.allay.spigot.Languages
import chat.allay.spigot.MemoryCommands
import chat.allay.core.Permissions
import chat.allay.spigot.tools.Features
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * `/allay`: the player's Allay settings in one window. Toggles switch chat mode and its tip; the rest opens what
 * `/allay`'s subcommands open. Like every [Menu], it only shows pictures.
 */
class SettingsMenu(
  player: Player,
  private val lang: Lang,
  private val features: Features,
  private val clicks: Clicks,
  private val memory: MemoryCommands,
  private val version: String,
  private val languages: Languages,
  private val chooseLanguage: () -> Unit,
) : Menu(player, 36, lang.text("menus.settings.title")) {
  private val chatAllowed get() = player.hasPermission(Permissions.CHAT)
  private val askAllowed get() = player.hasPermission(Permissions.ASK)

  override fun draw() {
    clear()
    val settings = features.settings.of(player.uniqueId)
    set(HEADER, icon(Material.BOOK, lang.text("menus.settings.header", "version" to version), lines("header_ask", "header_askall", "header_more")))

    toggle(CHAT, STATE_CHAT, Material.WRITABLE_BOOK, "chat", settings.chat_mode, lines("chat_what", "chat_prefixes"))
    toggle(TIP, STATE_TIP, Material.LANTERN, "tip", settings.hints, lines("tip_what"))

    val zone = features.zones.of(player.uniqueId)
    val time = runCatching { ZonedDateTime.now(ZoneId.of(zone)).format(DateTimeFormatter.ofPattern("HH:mm")) }.getOrDefault("?")
    val language = features.locales.explain(player)
    val how = lang.text(if (language.source == "chosen") "menus.settings.language_chosen" else "menus.settings.language_auto")
    set(LANGUAGE, icon(Material.GLOBE_BANNER_PATTERN, lang.text("menus.settings.language"), listOf(lang.text("menus.settings.language_now", "language" to languages.name(language.code), "how" to how), lang.text("menus.settings.language_click"))))
    set(ZONE, icon(Material.CLOCK, lang.text("menus.settings.zone"), listOf(lang.text("menus.settings.zone_now", "zone" to zone, "time" to time), lang.text("menus.settings.zone_change"))))

    if (askAllowed) {
      set(MEMORY, icon(Material.KNOWLEDGE_BOOK, lang.text("menus.settings.memory"), lines("memory_click")))
      set(NEW, icon(Material.FEATHER, lang.text("menus.settings.new"), lines("new_what", "new_confirm")))
      set(RECIPE, icon(Material.CRAFTING_TABLE, lang.text("menus.settings.recipe"), lines("in_hand")))
      set(ENCHANTS, icon(Material.ENCHANTING_TABLE, lang.text("menus.settings.enchants"), lines("in_hand")))
      set(VILLAGERS, icon(Material.EMERALD, lang.text("menus.settings.villagers"), lines("villagers_what")))
    }
    set(CLOSE, icon(Material.BARRIER, lang.text("menus.close")))
  }

  /** A setting and, under it, a pane that's green when it's on: the way the game's own toggles read at a glance. */
  private fun toggle(slot: Int, state: Int, material: Material, key: String, on: Boolean, about: List<String>) {
    val status = lang.text(if (on) "menus.settings.state_on" else "menus.settings.state_off")
    val action = lang.text(if (chatAllowed) "menus.settings.toggle" else "menus.settings.unavailable")
    set(slot, icon(material, lang.text("menus.settings.$key"), about + "" + status + action).shine(on))
    set(state, icon(if (on) Material.LIME_STAINED_GLASS_PANE else Material.RED_STAINED_GLASS_PANE, status))
  }

  private fun lines(vararg keys: String) = keys.map { lang.text("menus.settings.$it") }

  private fun ItemStack.shine(on: Boolean) = apply { itemMeta = itemMeta?.apply { setEnchantmentGlintOverride(on) } }

  override fun click(slot: Int, shift: Boolean) {
    val settings = features.settings.of(player.uniqueId)
    when (slot) {
      CHAT, STATE_CHAT -> if (chatAllowed) {
        features.chat.set(player, !settings.chat_mode)
        changed()
      }
      TIP, STATE_TIP -> if (chatAllowed) {
        features.chat.hints(player, !settings.hints)
        changed()
      }
      MEMORY -> if (askAllowed) {
        player.closeInventory()
        memory.list(player)
      }
      // A fresh start can't be undone, so a plain click only explains how.
      NEW -> if (askAllowed && shift) {
        player.closeInventory()
        memory.newSession(player)
      }
      // Closed first: when there's nothing to show, the reason goes to chat, which an open window hides.
      RECIPE -> if (askAllowed) {
        player.closeInventory()
        val item = player.inventory.itemInMainHand.takeUnless { it.type.isAir }
        if (item == null) lang.send(player, "menus.settings.hold_item") else clicks.recipe(player, item.type.key.key)
      }
      ENCHANTS -> if (askAllowed) {
        player.closeInventory()
        clicks.enchants(player)
      }
      VILLAGERS -> if (askAllowed) {
        player.closeInventory()
        clicks.villagers(player, null)
      }
      LANGUAGE -> chooseLanguage()
      CLOSE -> player.closeInventory()
    }
  }

  override fun click(slot: Int) = click(slot, shift = false)

  private fun changed() {
    player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.2f)
    draw()
  }

  private companion object {
    const val HEADER = 4
    const val CHAT = 10
    const val TIP = 11
    const val STATE_CHAT = 19
    const val STATE_TIP = 20
    const val ZONE = 13
    const val LANGUAGE = 22
    const val MEMORY = 15
    const val NEW = 16
    const val RECIPE = 23
    const val ENCHANTS = 24
    const val VILLAGERS = 25
    const val CLOSE = 31
  }
}
