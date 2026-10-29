package chat.allay.modcore.tools

import chat.allay.core.tools.Fireworks

import it.unimi.dsi.fastutil.ints.IntArrayList
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.FireworkExplosion

/** Fireworks as [Fireworks] describes them, built as the game's recipe would make them; Bukkit's `FireworkMeta`. */
object FireworkItems {
  private val SHAPES = mapOf(
    "small_ball" to FireworkExplosion.Shape.SMALL_BALL, "large_ball" to FireworkExplosion.Shape.LARGE_BALL, "star" to FireworkExplosion.Shape.STAR,
    "creeper" to FireworkExplosion.Shape.CREEPER, "burst" to FireworkExplosion.Shape.BURST,
  )

  private fun colors(names: List<String>) = IntArrayList(names.map { DyeColor.byName(it, DyeColor.WHITE)!!.fireworkColor })

  private fun explosion(star: Fireworks.Star) = FireworkExplosion(SHAPES.getValue(star.shape), colors(star.colors), colors(star.fade), star.trail, star.twinkle)

  fun rocket(spec: Fireworks.Spec, amount: Int): ItemStack = ItemStack(Items.FIREWORK_ROCKET, amount).apply {
    set(DataComponents.FIREWORKS, net.minecraft.world.item.component.Fireworks(spec.flight, spec.stars.map(::explosion)))
  }

  fun star(star: Fireworks.Star, amount: Int): ItemStack = ItemStack(Items.FIREWORK_STAR, amount).apply {
    set(DataComponents.FIREWORK_EXPLOSION, explosion(star))
  }

  /** [amount] of the firework [id] (`firework_rocket` or `firework_star`) to [spec]. */
  fun of(id: String, spec: Fireworks.Spec, amount: Int): ItemStack =
    if (id == "firework_star") star(spec.stars.single(), amount) else rocket(spec, amount)
}
