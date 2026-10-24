package chat.allay.modcore.tools

import chat.allay.core.*
import chat.allay.core.tools.*
import chat.allay.modcore.menus.RecipeMenu

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Tools that shape how the answer reaches the player: a book for long ones and their clock; and the buttons in it. */
object InteractTools {
  private fun tool(name: String, body: (ToolContext, ServerPlayer, JsonObject) -> ToolOutput) = object : Tool {
    override val name = name
    override fun run(context: ToolContext, args: JsonObject) = body(context, context.requirePlayer(), args)
  }

  /** Buttons under one answer: a book's two. */
  const val MAX_BUTTONS = 6

  /**
   * The [Button] behind a tag the model wrote into its answer (`<button action="…">Label</button>`), checked as the old
   * `add_buttons` tool checked it. A [ToolError] means the tag can't work, and the label shows as plain text.
   */
  fun fromTag(context: ToolContext, player: ServerPlayer, tag: InlineButtons.Part.Tag): Button {
    val args = buildJsonObject {
      tag.attributes.forEach { (key, value) -> if (key != "slots") put(key, value) }
      put("type", tag.attributes["action"] ?: "")
      put("label", tag.label)
      tag.attributes["slots"]?.let { slots -> put("slots", JsonArray(slots.split(',', ' ').mapNotNull { it.trim().toIntOrNull() }.map(::JsonPrimitive))) }
    }
    return button(context, player, args)
  }

  private fun button(context: ToolContext, player: ServerPlayer, args: JsonObject): Button {
    val type = args.string("type") ?: throw ToolError("each button needs a type")
    val label = args.string("label")?.take(24) ?: throw ToolError("each button needs a short label")
    fun coordinates(): Triple<Int, Int, Int> {
      val x = args.int("x") ?: throw ToolError("$type needs x and z")
      val z = args.int("z") ?: throw ToolError("$type needs x and z")
      return Triple(x, args.int("y") ?: player.blockY, z)
    }
    return when (type) {
      "ask" -> Button.Ask(label, args.string("question")?.take(200) ?: throw ToolError("ask needs the question it asks"))
      "command" -> Button.Command(label, args.string("command")?.take(256)?.takeIf { it.startsWith("/") && it.length > 1 } ?: throw ToolError("command needs a command starting with /"))
      // Never a command: the client would run a line starting with / as one.
      "say" -> Button.Say(label, args.string("text")?.take(256)?.takeUnless { it.startsWith("/") } ?: throw ToolError("say needs the text to send"))
      "point" -> args.string("player")?.trim()?.ifEmpty { null }?.let { name ->
        val target = context.playerNamed(name) ?: throw ToolError("nobody called $name is online; don't offer this button")
        if (!Locator.shows(player, target)) throw ToolError("${context.nameOf(target)} isn't on the asker's locator bar right now; don't offer the way to them")
        Button.Follow(label, target.gameProfile.name)
      } ?: coordinates().let { (x, y, z) -> Button.Point(label, x, y, z, args.string("dimension")?.let(Dimensions::parse)) }
      "note" -> coordinates().let { (x, y, z) ->
        Button.Note(label, args.string("text")?.take(200) ?: throw ToolError("note needs the text to save"), x, y, z, args.string("dimension")?.let(Dimensions::parse))
      }
      "recipe" -> {
        val item = recipeTarget(Catalogs.build(context), args.string("item") ?: throw ToolError("recipe needs the item"))
        if (RecipeMenu.recipesFor(player, item).isEmpty()) throw ToolError("${item.id} has no recipe on this server; don't offer a recipe button")
        Button.Recipe(label, item.id)
      }
      "copy" -> Button.Copy(label, args.string("text")?.take(200)?.ifBlank { null } ?: throw ToolError("copy needs the text to copy"))
      "enchants" -> Button.Enchants(label)
      "brewing" -> {
        val (brews, target) = BrewingRecipes.target(args.string("potion") ?: throw ToolError("brewing needs the potion"), args.string("form"))
        if (Brewing.chain(brews, target) == null) throw ToolError("the brewing stand can't make ${target.item} of ${target.potion}; don't offer this button")
        Button.Brewing(label, target.potion, target.item)
      }
      "anvil" -> {
        val slots = (args["slots"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }
        val target = args.int("slot")?.takeIf { it in AnvilTargets.ALL }
        Anvils.plan(player, slots, target ?: player.inventory.selectedSlot) ?: throw ToolError("nothing in the inventory improves that item; don't offer an anvil button")
        Button.Anvil(label, slots, target)
      }
      "villagers" -> Button.Villagers(label, args.string("item")?.let { Recipes.id(item(it)) })
      else -> throw ToolError("unknown button type $type")
    }
  }

  /**
   * The item a recipe button names: a data pack's or plugin's own item by the id tools give it (`mypack:ruby`, which
   * the registry doesn't hold), else the game's item [name] means.
   */
  fun recipeTarget(catalog: Catalog, name: String): RecipeMenu.Target {
    val custom = catalog.find(name.trim().lowercase(), Catalog.Kind.CUSTOM)
    custom?.base?.let(::known)?.let { return RecipeMenu.Target(custom.id, it) }
    return RecipeMenu.Target.of(item(name))
  }

  /** The item [name] means, forgiving plurals, spaces, `minecraft:`, and `#`; the error names close ids. */
  fun item(name: String): Item = known(Names.resolve(name, { known(it) != null }, ::itemIds))!!

  /** An item by its id, never air. */
  fun known(id: String): Item? = Identifier.tryParse(id.lowercase())?.let { BuiltInRegistries.ITEM.getOptional(it).orElse(null) }?.takeIf { it != Items.AIR }

  /** Every item id of the game, for hints. */
  val itemIds: List<String> by lazy { BuiltInRegistries.ITEM.filter { it != Items.AIR }.map(Recipes::id) }

  private val book = tool("write_book") { context, player, args ->
    val title = args.string("title")?.take(32) ?: throw ToolError("title is required")
    val text = args.string("text")?.take(8_000) ?: throw ToolError("text is required")
    if (context.buttons.size + 2 > MAX_BUTTONS) throw ToolError("no room for the book's buttons; add fewer buttons")
    val written = context.features.books.add(player.uuid, title, text)
    // Labels come from the config when the handler draws them.
    context.buttons += Button.ReadBook("", written.id)
    context.buttons += Button.TakeBook("", written.id)
    ToolOutput(buildJsonObject { put("pages", written.pages.size) }, context.lang.text("summary.book", "pages" to written.pages.size))
  }

  private val timezone = tool("set_timezone") { context, player, args ->
    val zone = context.features.zones.set(player.uuid, args.string("zone") ?: throw ToolError("zone is required, such as Asia/Yekaterinburg"))
    val now = ZonedDateTime.now(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
    ToolOutput(buildJsonObject {
      put("zone", zone.id)
      put("local_time", now)
    }, context.lang.text("summary.timezone", "zone" to zone.id, "time" to now))
  }

  val all = listOf(book, timezone)
}
