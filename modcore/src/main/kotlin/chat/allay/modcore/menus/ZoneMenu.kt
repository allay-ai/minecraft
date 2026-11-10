package chat.allay.modcore.menus

import chat.allay.core.Locales
import chat.allay.core.tools.TimeZones
import chat.allay.modcore.audience
import chat.allay.modcore.of

import net.minecraft.core.component.DataComponents
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * The player's time zone, picked as the language is: the zones of a country with the time there now, the country they
 * play from first, Automatic for their country's main zone, and every other country a page away. The JDK names the
 * countries and the zones in the player's language.
 */
class ZoneMenu(
  player: ServerPlayer,
  private val locales: Locales,
  private val zones: TimeZones,
  private val back: () -> Unit,
) : Menu(player, 54, locales.of(player).text("menus.zone.title")) {
  /** The country whose zones show, or null while the countries do. */
  private var country: String? = zones.country(player.uuid)?.uppercase()?.takeIf { TimeZones.zonesOf(it).isNotEmpty() }
  private var page = 0
  private val locale get() = java.util.Locale.forLanguageTag(locales.code(player.audience).replace('_', '-'))

  private fun entries(): List<String> = country?.let { code ->
    // The main zone first, then east to west as the clock goes.
    TimeZones.zonesOf(code).let { all -> listOf(all.first()) + all.drop(1).sortedBy { java.time.ZoneId.of(it).rules.getOffset(java.time.Instant.now()) }.reversed() }
  } ?: TimeZones.countries(locale)

  private val pages get() = maxOf(1, (entries().size + SLOTS - 1) / SLOTS)

  override fun draw() {
    clear()
    val lang = locales.of(player)
    val chosen = zones.chosen(player.uuid)
    val shown = entries().drop(page * SLOTS).take(SLOTS)
    shown.forEachIndexed { slot, entry ->
      if (country != null) {
        val label = TimeZones.label(entry, locale)
        val lore = listOf("&f" + label.name, lang.text("menus.zone.zone_lore", "offset" to label.offset, "time" to label.time), "&8$entry", "",
          lang.text(if (entry == chosen) "menus.zone.current" else "menus.zone.pick"))
        set(slot, icon(Items.CLOCK, "&e" + label.city, lore).shine(entry == chosen))
      } else {
        val count = TimeZones.zonesOf(entry).size
        set(slot, icon(Items.MAP, "&e" + TimeZones.countryName(entry, locale), listOf(lang.text("menus.zone.country_lore", "count" to count), "&8$entry")))
      }
    }
    if (page > 0) set(PREV, icon(Items.ARROW, lang.text("menus.zone.prev"), listOf(lang.text("menus.zone.page", "page" to page + 1, "pages" to pages))))
    if (page < pages - 1) set(NEXT, icon(Items.ARROW, lang.text("menus.zone.next"), listOf(lang.text("menus.zone.page", "page" to page + 1, "pages" to pages))))
    val automatic = zones.country(player.uuid)?.let(TimeZones::zoneOf)
    set(AUTO, icon(Items.COMPASS, lang.text("menus.zone.auto"), listOfNotNull(automatic?.let { lang.text("menus.zone.auto_what", "zone" to it) }, "",
      lang.text(if (chosen == null) "menus.zone.current" else "menus.zone.pick"))).shine(chosen == null))
    if (country != null) {
      set(OTHER, icon(Items.FILLED_MAP, lang.text("menus.zone.other"), listOf(lang.text("menus.zone.other_what", "country" to TimeZones.countryName(country!!, locale)))))
    }
    set(BACK, icon(Items.BARRIER, lang.text("menus.back")))
  }

  private fun ItemStack.shine(on: Boolean) = apply { set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, on) }

  override fun click(slot: Int) {
    when (slot) {
      AUTO -> {
        zones.forget(player.uuid)
        press()
        back()
      }
      BACK -> { press(); back() }
      OTHER -> if (country != null) {
        press()
        country = null
        page = 0
        draw()
      }
      PREV -> if (page > 0) { press(); page--; draw() }
      NEXT -> if (page < pages - 1) { press(); page++; draw() }
      in 0 until SLOTS -> {
        val entry = entries().getOrNull(page * SLOTS + slot) ?: return
        press()
        if (country == null) {
          // A country: its zones next, to pick one of.
          country = entry
          page = 0
          draw()
        } else {
          zones.set(player.uuid, entry)
          back()
        }
      }
    }
  }

  private companion object {
    /** Five rows of zones or countries, then the controls. */
    const val SLOTS = 45
    const val PREV = 45
    const val AUTO = 47
    const val BACK = 49
    const val OTHER = 51
    const val NEXT = 53
  }
}
