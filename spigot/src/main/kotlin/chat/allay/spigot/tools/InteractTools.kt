package chat.allay.spigot.tools

import chat.allay.core.*
import chat.allay.core.tools.*

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.bukkit.Material
import org.bukkit.entity.Player
import chat.allay.spigot.menus.RecipeMenu
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Tools that shape how the answer reaches the player: buttons under it, a book for long ones, and their clock. */
object InteractTools {
  private fun tool(name: String, body: (ToolContext, Player, JsonObject) -> ToolOutput) = object : Tool {
    override val name = name
    override fun run(context: ToolContext, args: JsonObject) = body(context, context.requirePlayer(), args)
  }

  /** Buttons under one answer, books included; more would crowd the chat line. Six fits a way button for each player around. */
  const val MAX_BUTTONS = 6

  private val buttons = tool("add_buttons") { context, player, args ->
    val list = (args["buttons"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: throw ToolError("buttons is required: an array of objects")
    if (list.isEmpty()) throw ToolError("buttons is empty")
    if (context.buttons.size + list.size > MAX_BUTTONS) throw ToolError("at most $MAX_BUTTONS buttons under one answer; ${context.buttons.size} already added")
    // Every button is checked before any is added, so a bad one doesn't leave half a row.
    val made = list.map { button(context, player, it) }
    context.buttons += made
    ToolOutput(buildJsonObject { put("added", made.size); put("total", context.buttons.size) }, context.lang.text("summary.buttons", "count" to made.size))
  }

  private fun button(context: ToolContext, player: Player, args: JsonObject): Button {
    val type = args.string("type") ?: throw ToolError("each button needs a type")
    val label = args.string("label")?.take(24) ?: throw ToolError("each button needs a short label")
    fun coordinates(): Triple<Int, Int, Int> {
      val x = args.int("x") ?: throw ToolError("$type needs x and z")
      val z = args.int("z") ?: throw ToolError("$type needs x and z")
      return Triple(x, args.int("y") ?: player.location.blockY, z)
    }
    return when (type) {
      "ask" -> Button.Ask(label, args.string("question")?.take(200) ?: throw ToolError("ask needs the question it asks"))
      "point" -> args.string("player")?.trim()?.ifEmpty { null }?.let { name ->
        val target = context.playerNamed(name) ?: throw ToolError("nobody called $name is online; don't offer this button")
        if (!Locator.shows(player, target)) throw ToolError("${context.nameOf(target)} isn't on the asker's locator bar right now; don't offer the way to them")
        Button.Follow(label, target.name)
      } ?: coordinates().let { (x, y, z) -> Button.Point(label, x, y, z, args.string("dimension")?.let(Dimensions::parse)) }
      "note" -> coordinates().let { (x, y, z) ->
        Button.Note(label, args.string("text")?.take(200) ?: throw ToolError("note needs the text to save"), x, y, z, args.string("dimension")?.let(Dimensions::parse))
      }
      "recipe" -> {
        val item = material(args.string("item") ?: throw ToolError("recipe needs the item"))
        if (RecipeMenu.recipesFor(item).isEmpty()) throw ToolError("${item.key.key} has no recipe on this server; don't offer a recipe button")
        Button.Recipe(label, item.key.key)
      }
      "copy" -> Button.Copy(label, args.string("text")?.take(200)?.ifBlank { null } ?: throw ToolError("copy needs the text to copy"))
      "enchants" -> Button.Enchants(label)
      "brewing" -> {
        val (brews, target) = BrewingRecipes.target(player.server, args.string("potion") ?: throw ToolError("brewing needs the potion"), args.string("form"))
        if (Brewing.chain(brews, target) == null) throw ToolError("the brewing stand can't make ${target.item} of ${target.potion}; don't offer this button")
        Button.Brewing(label, target.potion, target.item)
      }
      "anvil" -> {
        val slots = (args["slots"] as? JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }
        Anvils.plan(player, slots) ?: throw ToolError("nothing in the inventory improves the item in hand; don't offer an anvil button")
        Button.Anvil(label, slots)
      }
      "villagers" -> Button.Villagers(label, args.string("item")?.let { material(it).key.key })
      else -> throw ToolError("unknown button type $type; use ask, point, note, copy, recipe, enchants, brewing, anvil, or villagers")
    }
  }

  /** The item [name] means, forgiving plurals, spaces, `minecraft:`, and `#`; the error names close ids. Never a legacy material. */
  fun material(name: String): Material = Material.matchMaterial(Names.resolve(name, { item(it) != null }, ::itemIds))!!

  private fun item(id: String): Material? = Material.matchMaterial(id)?.takeIf { !it.name.startsWith("LEGACY_") && it.isItem && !it.isAir }

  /** Every item id of the game, for hints. */
  val itemIds: List<String> by lazy { Material.entries.filter { !it.name.startsWith("LEGACY_") && it.isItem && !it.isAir }.map { it.key.key } }

  private val book = tool("write_book") { context, player, args ->
    val title = args.string("title")?.take(32) ?: throw ToolError("title is required")
    val text = args.string("text")?.take(8_000) ?: throw ToolError("text is required")
    if (context.buttons.size + 2 > MAX_BUTTONS) throw ToolError("no room for the book's buttons; add fewer buttons")
    val written = context.features.books.add(player.uniqueId, title, text)
    // Labels come from the config when the handler draws them.
    context.buttons += Button.ReadBook("", written.id)
    context.buttons += Button.TakeBook("", written.id)
    ToolOutput(buildJsonObject { put("pages", written.pages.size) }, context.lang.text("summary.book", "pages" to written.pages.size))
  }

  private val timezone = tool("set_timezone") { context, player, args ->
    val zone = context.features.zones.set(player.uniqueId, args.string("zone") ?: throw ToolError("zone is required, such as Asia/Yekaterinburg"))
    val now = ZonedDateTime.now(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
    ToolOutput(buildJsonObject { put("zone", zone.id); put("local_time", now) }, context.lang.text("summary.timezone", "zone" to zone.id, "time" to now))
  }

  val all = listOf(buttons, book, timezone)
}
