package chat.allay.spigot.menus

import chat.allay.spigot.of
import chat.allay.spigot.code
import chat.allay.spigot.audience

import chat.allay.core.Lang
import chat.allay.core.Permissions
import chat.allay.core.Languages
import chat.allay.core.MemoryCommands
import chat.allay.core.PrivacyCommands
import chat.allay.spigot.audience
import chat.allay.spigot.explain
import chat.allay.spigot.tools.Features
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * `/allay`: the player's Allay settings in one window, and nothing else. Toggles switch chat mode, its tip, and full
 * privacy; memory and “My data” open their own windows (dialogs). Admins get a bottom row: reload, the subscription's
 * status, and the payment link while nothing runs. The recipe, enchanting, and villager windows open from buttons in
 * answers, where Allay knows what they're for. Like every [Menu], it only shows pictures.
 */
class SettingsMenu(
  player: Player,
  private val lang: Lang,
  private val features: Features,
  private val memory: MemoryCommands,
  private val privacy: PrivacyCommands,
  private val version: String,
  private val languages: Languages,
  private val admin: Admin,
  private val chooseLanguage: () -> Unit,
) : Menu(player, 45, lang.text("menus.settings.title")) {
  /** What the admins' row does; [pay] says whether there's anything to pay for. */
  class Admin(val reload: () -> Unit, val status: () -> Unit, val pay: () -> Boolean, val link: () -> Unit)

  private val reloadAllowed get() = player.hasPermission(Permissions.RELOAD)
  private val adminAllowed get() = player.hasPermission(Permissions.ADMIN)
  private val chatAllowed get() = player.hasPermission(Permissions.CHAT)
  private val askAllowed get() = player.hasPermission(Permissions.ASK)

  override fun draw() {
    clear()
    val settings = features.settings.of(player.uniqueId)
    set(HEADER, icon(Material.BOOK, lang.text("menus.settings.header", "version" to version), lines("header_ask", "header_askall", "header_more")))

    toggle(CHAT, STATE_CHAT, Material.WRITABLE_BOOK, "chat", settings.chat_mode, chatAllowed, lines("chat_what", "chat_prefixes"))
    toggle(TIP, STATE_TIP, Material.LANTERN, "tip", settings.hints, chatAllowed, lines("tip_what"))

    val zone = features.zones.of(player.uniqueId)
    val time = runCatching { ZonedDateTime.now(ZoneId.of(zone)).format(DateTimeFormatter.ofPattern("HH:mm")) }.getOrDefault("?")
    val language = features.locales.explain(player)
    val how = lang.text(if (language.source == "chosen") "menus.settings.language_chosen" else "menus.settings.language_auto")
    set(LANGUAGE, icon(Material.GLOBE_BANNER_PATTERN, lang.text("menus.settings.language"), listOf(lang.text("menus.settings.language_now", "language" to languages.name(language.code), "how" to how), lang.text("menus.settings.language_click"))))
    set(ZONE, icon(Material.CLOCK, lang.text("menus.settings.zone"), listOf(lang.text("menus.settings.zone_now", "zone" to zone, "time" to time), lang.text("menus.settings.zone_change"))))

    if (askAllowed) {
      set(MEMORY, icon(Material.KNOWLEDGE_BOOK, lang.text("menus.settings.memory"), lines("memory_click")))
      set(NEW, icon(Material.FEATHER, lang.text("menus.settings.new"), lines("new_what", "new_confirm")))
      toggle(PRIVATE, STATE_PRIVATE, Material.ENDER_EYE, "private", settings.full_privacy, true, lines("private_what", "private_keeps"))
      set(DATA, icon(Material.SHIELD, lang.text("menus.settings.data"), lines("data_what", "data_click")))
    }
    set(CLOSE, icon(Material.BARRIER, lang.text("menus.close")))

    if (reloadAllowed) set(RELOAD, icon(Material.REPEATING_COMMAND_BLOCK, lang.text("menus.settings.reload"), lines("reload_what")))
    if (adminAllowed) {
      set(STATUS, icon(Material.GOLD_INGOT, lang.text("menus.settings.status"), lines("status_what")))
      if (admin.pay()) set(LINK, icon(Material.EMERALD, lang.text("menus.settings.link"), lines("link_what")))
    }
  }

  /** A setting and, under it, a pane that's green when it's on: the way the game's own toggles read at a glance. */
  private fun toggle(slot: Int, state: Int, material: Material, key: String, on: Boolean, allowed: Boolean, about: List<String>) {
    val status = lang.text(if (on) "menus.settings.state_on" else "menus.settings.state_off")
    val action = lang.text(if (allowed) "menus.settings.toggle" else "menus.settings.unavailable")
    set(slot, icon(material, lang.text("menus.settings.$key"), about + "" + status + action).shine(on))
    set(state, icon(if (on) Material.LIME_STAINED_GLASS_PANE else Material.RED_STAINED_GLASS_PANE, status))
  }

  private fun lines(vararg keys: String) = keys.map { lang.text("menus.settings.$it") }

  private fun ItemStack.shine(on: Boolean) = apply { itemMeta = itemMeta?.apply { setEnchantmentGlintOverride(on) } }

  override fun click(slot: Int, shift: Boolean) {
    val settings = features.settings.of(player.uniqueId)
    when (slot) {
      CHAT, STATE_CHAT -> if (chatAllowed) {
        features.chat.set(player.audience, !settings.chat_mode)
        changed()
      }
      TIP, STATE_TIP -> if (chatAllowed) {
        features.chat.hints(player.audience, !settings.hints)
        changed()
      }
      PRIVATE, STATE_PRIVATE -> if (askAllowed) {
        privacy.setFull(player.audience, !settings.full_privacy)
        changed()
      }
      MEMORY -> if (askAllowed) {
        player.closeInventory()
        memory.open(player.audience)
      }
      // A fresh start can't be undone, so a plain click only explains how.
      NEW -> if (askAllowed && shift) {
        player.closeInventory()
        memory.newSession(player.audience)
      }
      DATA -> if (askAllowed) {
        player.closeInventory()
        privacy.info(player.audience)
      }
      LANGUAGE -> chooseLanguage()
      RELOAD -> if (reloadAllowed) {
        admin.reload()
        changed()
      }
      STATUS -> if (adminAllowed) {
        player.closeInventory()
        admin.status()
      }
      // Closed first: the link comes to chat, which an open window hides.
      LINK -> if (adminAllowed && admin.pay()) {
        player.closeInventory()
        admin.link()
      }
      CLOSE -> player.closeInventory()
    }
  }

  override fun click(slot: Int) = click(slot, shift = false)

  private fun changed() {
    player.playSound(player.location, Sound.UI_BUTTON_CLICK, 0.5f, 1.2f)
    draw()
  }

  /** Five rows: switches on the left, where and in which language in the middle, privacy on the right. */
  private companion object {
    const val HEADER = 4
    const val CHAT = 10
    const val TIP = 11
    const val STATE_CHAT = 19
    const val STATE_TIP = 20
    const val ZONE = 13
    const val LANGUAGE = 22
    const val PRIVATE = 16
    const val STATE_PRIVATE = 25
    const val MEMORY = 28
    const val NEW = 29
    const val DATA = 34
    const val CLOSE = 40
    // Admins' row around Close: reload on the left, the subscription and paying for it on the right.
    const val RELOAD = 38
    const val STATUS = 42
    const val LINK = 43
  }
}
