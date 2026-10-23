package chat.allay.modcore

import chat.allay.core.Legacy
import chat.allay.core.Line

import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
import java.net.URI

/** The core's `§` texts and lines as the game's components, styled as Bukkit draws them ([Legacy.runs]). */
object Texts {
  /** [text] with its codes as styles; with [links], addresses in it open in the browser. */
  fun legacy(text: String, links: Boolean = false): MutableComponent {
    val runs = Legacy.runs(text, links)
    if (runs.size == 1) return component(runs[0])
    return Component.empty().apply { runs.forEach { append(component(it)) } }
  }

  /** [legacy] where every address looks like a link too, for a window's text. */
  fun linked(text: String): MutableComponent = Component.empty().apply {
    Legacy.runs(text, links = true).forEach { run -> append(component(if (run.link != null) run.copy(color = 0x55FFFF, underlined = true) else run)) }
  }

  fun line(line: Line): MutableComponent = Component.empty().apply {
    line.parts.forEach { part ->
      when (part) {
        is Line.Text -> append(legacy(part.text, part.links))
        is Line.Button -> {
          val click = when (val click = part.click) {
            is Line.Click.Run -> ClickEvent.RunCommand(click.command.removePrefix("/"))
            is Line.Click.Open -> runCatching { ClickEvent.OpenUrl(URI(click.url)) }.getOrNull()
            is Line.Click.Copy -> ClickEvent.CopyToClipboard(click.text)
          }
          val hover = HoverEvent.ShowText(legacy(part.hover))
          append(legacy(part.label).withStyle { it.withClickEvent(click).withHoverEvent(hover) })
        }
      }
    }
  }

  /** The game's text with its styles as `§` codes, as Bukkit gives an item's name or lore. */
  fun legacyOf(component: Component): String {
    val out = StringBuilder()
    component.visit({ style, text ->
      if (text.isNotEmpty()) {
        out.append(codes(style))
        out.append(text)
      }
      java.util.Optional.empty<Unit>()
    }, Style.EMPTY)
    return out.toString()
  }

  private fun codes(style: Style): String = buildString {
    style.color?.value?.let { rgb ->
      val code = Legacy.code(rgb)
      if (code != null) append('§').append(code) else append("§x").append("%06x".format(rgb).map { "§$it" }.joinToString(""))
    }
    if (style.isBold) append("§l")
    if (style.isItalic) append("§o")
    if (style.isUnderlined) append("§n")
    if (style.isStrikethrough) append("§m")
    if (style.isObfuscated) append("§k")
  }

  private fun component(run: Legacy.Run): MutableComponent {
    var style = Style.EMPTY
    run.color?.let { style = style.withColor(it) }
    if (run.bold) style = style.withBold(true)
    if (run.italic) style = style.withItalic(true)
    if (run.underlined) style = style.withUnderlined(true)
    if (run.strikethrough) style = style.withStrikethrough(true)
    if (run.obfuscated) style = style.withObfuscated(true)
    run.link?.let { link -> runCatching { URI(link) }.getOrNull()?.let { style = style.withClickEvent(ClickEvent.OpenUrl(it)) } }
    // Not italic unless the text says so: item names and lore would otherwise lean.
    if (!run.italic) style = style.withItalic(false)
    return Component.literal(run.text).withStyle(style)
  }
}
