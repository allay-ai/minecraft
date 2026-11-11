package chat.allay.spigot.menus

import chat.allay.core.Lang
import chat.allay.core.building.EstimateSheet
import chat.allay.core.building.Materials

import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * What a previewed build takes, as a chest: the top row sums it up, and each material below shows as its item, what's
 * short first, with how many it takes and how many the player has in its tooltip ([EstimateSheet]).
 */
class EstimateMenu(
  player: Player,
  private val lang: Lang,
  title: String,
  rows: List<EstimateSheet.Row>,
  private val estimate: Materials.Estimate,
  private val unobtainable: Map<String, Int>,
  private val creative: Boolean,
  private val name: (String) -> String,
) : Menu(player, 54, lang.text("previews.estimate.title", "title" to title)) {
  private val rows = EstimateSheet.sorted(rows.filter { Material.matchMaterial(it.item)?.isItem == true }, creative)
  private val pages = EstimateSheet.pages(this.rows)
  private var page = 0

  override fun draw() {
    clear()
    val (summary, lore) = EstimateSheet.summary(lang, rows, creative)
    set(EstimateSheet.SUMMARY, icon(Material.WRITABLE_BOOK, summary, lore + if (pages > 1) listOf(EstimateSheet.page(lang, page, pages)) else emptyList()))
    estimate.tools["hoe"]?.let { set(EstimateSheet.HOE, icon(Material.WOODEN_HOE, EstimateSheet.tool(lang, "hoe", it))) }
    estimate.tools["shovel"]?.let { set(EstimateSheet.SHOVEL, icon(Material.WOODEN_SHOVEL, EstimateSheet.tool(lang, "shovel", it))) }
    if (estimate.water) set(EstimateSheet.WATER, icon(Material.WATER_BUCKET, EstimateSheet.water(lang)))
    if (unobtainable.isNotEmpty()) set(EstimateSheet.UNOBTAINABLE, icon(Material.BARRIER, EstimateSheet.unobtainable(lang, unobtainable, name)))
    if (estimate.free.isNotEmpty()) set(EstimateSheet.FREE, icon(Material.PLAYER_HEAD, EstimateSheet.free(lang, estimate.free.values.sum())))
    if (page > 0) set(EstimateSheet.PREVIOUS, icon(Material.ARROW, lang.text("menus.previous")))
    if (page < pages - 1) set(EstimateSheet.NEXT, icon(Material.ARROW, lang.text("menus.next")))
    rows.drop(page * EstimateSheet.PAGE).take(EstimateSheet.PAGE).forEachIndexed { i, row ->
      val material = Material.matchMaterial(row.item) ?: return@forEachIndexed
      set(EstimateSheet.FIRST + i, icon(material, null, EstimateSheet.lore(lang, row, creative), EstimateSheet.amount(row, creative)).enough(!creative && row.short == 0))
    }
  }

  /** What's all there shines. */
  private fun ItemStack.enough(on: Boolean) = apply { itemMeta = itemMeta?.apply { setEnchantmentGlintOverride(on) } }

  override fun click(slot: Int) {
    when (slot) {
      EstimateSheet.PREVIOUS -> if (page > 0) { page--; press(); draw() }
      EstimateSheet.NEXT -> if (page < pages - 1) { page++; press(); draw() }
    }
  }
}
