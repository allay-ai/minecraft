package chat.allay.spigot.tools

import chat.allay.core.tools.Fireworks

import org.bukkit.DyeColor
import org.bukkit.FireworkEffect
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.FireworkEffectMeta
import org.bukkit.inventory.meta.FireworkMeta

/** Fireworks as [Fireworks] describes them, built as the game's recipe would make them. */
object FireworkItems {
  private val TYPES = mapOf(
    "small_ball" to FireworkEffect.Type.BALL, "large_ball" to FireworkEffect.Type.BALL_LARGE, "star" to FireworkEffect.Type.STAR,
    "creeper" to FireworkEffect.Type.CREEPER, "burst" to FireworkEffect.Type.BURST,
  )

  private fun color(name: String) = DyeColor.valueOf(name.uppercase()).fireworkColor

  private fun effect(star: Fireworks.Star): FireworkEffect = FireworkEffect.builder()
    .with(TYPES.getValue(star.shape))
    .withColor(star.colors.map(::color))
    .apply { if (star.fade.isNotEmpty()) withFade(star.fade.map(::color)) }
    .trail(star.trail)
    .flicker(star.twinkle)
    .build()

  fun rocket(spec: Fireworks.Spec, amount: Int): ItemStack = ItemStack(Material.FIREWORK_ROCKET, amount).apply {
    itemMeta = (itemMeta as FireworkMeta).apply {
      power = spec.flight
      addEffects(spec.stars.map(::effect))
    }
  }

  fun star(star: Fireworks.Star, amount: Int): ItemStack = ItemStack(Material.FIREWORK_STAR, amount).apply {
    itemMeta = (itemMeta as FireworkEffectMeta).apply { effect = effect(star) }
  }

  /** [amount] of the firework [id] (`firework_rocket` or `firework_star`) to [spec]. */
  fun of(id: String, spec: Fireworks.Spec, amount: Int): ItemStack =
    if (id == "firework_star") star(spec.stars.single(), amount) else rocket(spec, amount)
}
