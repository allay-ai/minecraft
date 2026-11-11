package chat.allay.modcore.menus

import chat.allay.core.Lang
import chat.allay.core.building.EstimateSheet
import chat.allay.core.building.Materials

import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * What a previewed build takes, as a chest: the top row sums it up, and each material below shows as its item, what's
 * short first, with how many it takes and how many the player has in its tooltip ([EstimateSheet]).
 */
class EstimateMenu(
  player: ServerPlayer,
  private val lang: Lang,
  title: String,
  rows: List<EstimateSheet.Row>,
  private val estimate: Materials.Estimate,
  private val unobtainable: Map<String, Int>,
  private val creative: Boolean,
  private val name: (String) -> String,
) : Menu(player, 54, lang.text("previews.estimate.title", "title" to title)) {
  private val rows = EstimateSheet.sorted(rows.filter { item(it.item) != null }, creative)
  private val pages = EstimateSheet.pages(this.rows)
  private var page = 0

  override fun draw() {
    clear()
    val (summary, lore) = EstimateSheet.summary(lang, rows, creative)
    set(EstimateSheet.SUMMARY, icon(Items.WRITABLE_BOOK, summary, lore + if (pages > 1) listOf(EstimateSheet.page(lang, page, pages)) else emptyList()))
    estimate.tools["hoe"]?.let { set(EstimateSheet.HOE, icon(Items.WOODEN_HOE, EstimateSheet.tool(lang, "hoe", it))) }
    estimate.tools["shovel"]?.let { set(EstimateSheet.SHOVEL, icon(Items.WOODEN_SHOVEL, EstimateSheet.tool(lang, "shovel", it))) }
    if (estimate.water) set(EstimateSheet.WATER, icon(Items.WATER_BUCKET, EstimateSheet.water(lang)))
    if (unobtainable.isNotEmpty()) set(EstimateSheet.UNOBTAINABLE, icon(Items.BARRIER, EstimateSheet.unobtainable(lang, unobtainable, name)))
    if (estimate.free.isNotEmpty()) set(EstimateSheet.FREE, icon(Items.PLAYER_HEAD, EstimateSheet.free(lang, estimate.free.values.sum())))
    if (page > 0) set(EstimateSheet.PREVIOUS, icon(Items.ARROW, lang.text("menus.previous")))
    if (page < pages - 1) set(EstimateSheet.NEXT, icon(Items.ARROW, lang.text("menus.next")))
    rows.drop(page * EstimateSheet.PAGE).take(EstimateSheet.PAGE).forEachIndexed { i, row ->
      val item = item(row.item) ?: return@forEachIndexed
      set(EstimateSheet.FIRST + i, icon(item, null, EstimateSheet.lore(lang, row, creative), EstimateSheet.amount(row, creative)).enough(!creative && row.short == 0))
    }
  }

  /** What's all there shines. */
  private fun ItemStack.enough(on: Boolean) = apply { set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, on) }

  private fun item(id: String): Item? = Identifier.tryParse("minecraft:$id")?.takeIf(BuiltInRegistries.ITEM::containsKey)?.let(BuiltInRegistries.ITEM::getValue)

  override fun click(slot: Int) {
    when (slot) {
      EstimateSheet.PREVIOUS -> if (page > 0) { page--; press(); draw() }
      EstimateSheet.NEXT -> if (page < pages - 1) { page++; press(); draw() }
    }
  }
}
