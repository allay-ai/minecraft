package chat.allay.core.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The game's crafting recipes that no grid of fixed ingredients describes: fireworks, copying books, maps, and banners,
 * decorating shields, dyeing leather, tipped arrows, decorated pots, and joining worn items. The server lists them
 * without a usable result (Bukkit's `ComplexRecipe`, the game's `crafting_special_*`), so `get_recipes` would say
 * there's no recipe and the model that the item can't be made. Each is described in words here, from the game's own
 * recipe data, and shown only when the server has that recipe by its key. The same on every platform.
 */
object SpecialRecipes {
  /** One recipe: its key on the server (a key ending in `_` matches any key that starts with it), what it makes, how. */
  class Special(val key: String, val makes: Set<String>, val describe: JsonObject)

  private fun recipe(vararg lines: Pair<String, Any>) = buildJsonObject {
    put("type", "crafting_special")
    lines.forEach { (key, value) ->
      when (value) {
        is String -> put(key, value)
        is Int -> put(key, value)
        is JsonObject -> put(key, value)
        is List<*> -> put(key, JsonArray(value.map { JsonPrimitive(it.toString()) }))
      }
    }
  }

  private val LEATHER = setOf("leather_helmet", "leather_chestplate", "leather_leggings", "leather_boots", "leather_horse_armor", "wolf_armor")
  private val BANNERS = Dyes.COLORS.map { "${it}_banner" }.toSet()

  val ALL = listOf(
    Special("firework_rocket", setOf("firework_rocket"), recipe(
      "ingredients" to "1 paper, 1–3 gunpowder, and 0–7 firework stars, anywhere in the grid (a crafting table for more than 4 items)",
      "makes" to 3,
      "gunpowder" to "flight duration: 1 short, 2 higher, 3 the highest and the longest elytra boost",
      "stars" to "each star is one explosion with its colors and shape; with none it's a plain rocket, for flying with elytra",
    )),
    Special("firework_star", setOf("firework_star"), recipe(
      "ingredients" to "1 gunpowder and 1–8 dyes, plus at most one shape item and optionally a diamond and glowstone dust, anywhere in the grid",
      "dyes" to "the explosion's colors, one per dye; several dyes make a multicolored burst",
      "shapes" to buildJsonObject {
        put("small_ball", "no shape item")
        put("large_ball", "fire_charge")
        put("star", "gold_nugget")
        put("creeper", "any mob head (#skulls)")
        put("burst", "feather")
      },
      "trail" to "diamond",
      "twinkle" to "glowstone_dust: the sparks crackle after the blast",
    )),
    Special("firework_star_fade", setOf("firework_star"), recipe(
      "ingredients" to "1 firework star and 1–8 dyes",
      "does" to "the star's explosion fades from its colors into these",
    )),
    Special("book_cloning", setOf("written_book"), recipe(
      "ingredients" to "1 written book (an original or a copy of the original) and 1–8 books and quills",
      "makes" to "that many copies; the book itself stays. A copy of a copy can't be copied",
    )),
    Special("map_cloning", setOf("filled_map"), recipe(
      "ingredients" to "1 filled map and 1–8 empty maps",
      "makes" to "that many more copies of the map; the original stays",
    )),
    Special("map_extending", setOf("filled_map"), recipe(
      "ingredients" to "1 filled map in the middle of a crafting table and 8 paper around it, or 1 paper at a cartography table",
      "does" to "zooms the map out one level (a level 4 map is as wide as it gets)",
    )),
    Special("_banner_duplicate", BANNERS, recipe(
      "ingredients" to "a banner with patterns and a blank banner of the same color",
      "makes" to "a copy of the patterns on the blank one; the original stays",
    )),
    Special("shield_decoration", setOf("shield"), recipe(
      "ingredients" to "1 shield and 1 banner with patterns",
      "does" to "the shield takes the banner's base color and patterns; the banner is used up, and a decorated shield can't take another",
    )),
    Special("_dyed", LEATHER, recipe(
      "ingredients" to "the leather armor piece (or leather horse armor, wolf armor) and 1–8 dyes",
      "does" to "the dyes mix into one color; dye it again to mix further, or wash it in a water cauldron to start over",
    )),
    Special("tipped_arrow", setOf("tipped_arrow"), recipe(
      "ingredients" to "8 arrows around 1 lingering potion in a crafting table",
      "makes" to 8,
      "does" to "each arrow carries the potion's effect for an eighth of its time",
    )),
    Special("decorated_pot", setOf("decorated_pot"), recipe(
      "ingredients" to "4 bricks or pottery sherds in a diamond in a crafting table: top is the back, sides are left and right, bottom is the front",
      "does" to "each sherd shows its picture on that side; plain bricks leave the side blank",
    )),
    Special("repair_item", emptySet(), recipe(
      "ingredients" to "2 worn items of the same kind",
      "does" to "one item with both durabilities plus 5% more; enchantments are lost (a grindstone keeps the curse and gives experience back, an anvil keeps them)",
    )),
  )

  private fun present(special: Special, keys: Set<String>) =
    if (special.key.startsWith("_")) keys.any { it.endsWith(special.key) } else special.key in keys

  /** The ways [item] is made among the server's recipe [keys] (paths, like `firework_rocket`). */
  fun forItem(item: String, keys: Set<String>): List<JsonObject> = ALL.filter { item in it.makes && present(it, keys) }.map { it.describe }

  /** Every item one of the server's special recipes makes, so the catalog says “crafting” for it too. */
  fun made(keys: Set<String>): Set<String> = ALL.filter { present(it, keys) }.flatMap { it.makes }.toSet()

  /**
   * Firework stars and rockets in one place, for “make me the best firework” or “a red and gold star that crackles”:
   * every color with its dye, every shape and effect with its item, and what makes a show.
   */
  fun fireworks(): JsonObject = buildJsonObject {
    putJsonObject("colors") { Dyes.COLORS.forEach { put(it, "${it}_dye") } }
    putJsonObject("shapes") {
      put("small_ball", "nothing extra")
      put("large_ball", "fire_charge")
      put("star", "gold_nugget")
      put("creeper", "a mob head")
      put("burst", "feather")
    }
    putJsonObject("effects") {
      put("trail", "diamond")
      put("twinkle", "glowstone_dust")
      put("fade", "craft the finished star again with 1–8 dyes")
    }
    put("flight", "1–3 gunpowder in the rocket: the more, the higher it flies before it bursts")
    put("show", buildJsonArray {
      add(JsonPrimitive("each rocket takes up to 7 stars, each a burst of its own: mix shapes and colors in one rocket for a layered show"))
      add(JsonPrimitive("a grand star: gunpowder, 3–8 dyes, fire_charge, diamond, and glowstone_dust, then a fade with 1–3 more dyes"))
      add(JsonPrimitive("3 gunpowder lifts a big show high enough to see from afar; for flying with elytra, rockets without stars"))
    })
  }

  /** What a transmute recipe (dyeing a shulker box or a bundle) does with its input, for `get_recipes`. */
  const val TRANSMUTE = "the input and the material anywhere in the grid; the input keeps what it holds, its name, and its enchantments, and takes the new look"

  /** Items whose answer carries [fireworks]. */
  val FIREWORK_ITEMS = setOf("firework_rocket", "firework_star")
}

/** The game's sixteen dye colors, in its own order: `white_dye`, `white_wool`, `white_banner`. */
object Dyes {
  val COLORS = listOf(
    "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
    "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black",
  )
}
