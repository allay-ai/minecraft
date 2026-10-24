package chat.allay.core.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Brewing as the game keeps it since 26.3: the files in `data/minecraft/recipe/brewing/`, each a bottle of one potion plus a
 * reagent giving another bottle. Spigot's `BrewingRecipe` shows only the result, so the platform reads the files and
 * this turns them into the steps from a water bottle to any potion.
 */
object Brewing {
  /**
   * A bottle: its item (`potion`, `splash_potion`, `lingering_potion`) and the potion inside, as the game's ids. A
   * plugin's or expansion's own bottle (Red Bull's crude taurine) is its id twice.
   */
  data class Bottle(val item: String, val potion: String) {
    /** As tools write it: `potion night_vision`, or a plugin's bottle by its id alone. */
    val label: String get() = if (item == potion) item else "$item $potion"
  }

  /** A bottle as [Bottle.label] or an expansion's ingredient writes it: `potion water`, `splash_potion healing`, `redbull:bile`. */
  fun bottleOf(text: String): Bottle {
    val parts = text.trim().removePrefix("minecraft:").split(' ', limit = 2)
    return if (parts.size == 2 && parts[0] in FORMS) Bottle(parts[0], parts[1].removePrefix("minecraft:")) else Bottle(parts[0], parts[0])
  }

  /** One use of the brewing stand. */
  data class Brew(val from: Bottle, val reagent: String, val to: Bottle)

  val WATER = Bottle("potion", "water")
  val FORMS = listOf("potion", "splash_potion", "lingering_potion")

  /** A stand holds this many bottles. */
  const val STAND_BOTTLES = 3

  /** One empty stand's load: the chain step its bottles start at (0 is water) and how many. */
  data class Fill(val step: Int, val bottles: Int)

  /**
   * How to load up to [stands] empty stands for [total] bottles in as few stands as it takes: the player's own bottles of
   * a later step first ([own], step to how many: night vision potions when they want long ones), the furthest along
   * first, three to a stand and one kind per stand, then water bottles for the rest.
   */
  fun fill(total: Int, stands: Int, own: Map<Int, Int>): List<Fill> {
    val loads = mutableListOf<Fill>()
    var left = total
    for ((step, have) in own.filterKeys { it > 0 }.toSortedMap(reverseOrder())) {
      var rest = have
      while (left > 0 && rest > 0 && loads.size < stands) {
        val n = minOf(STAND_BOTTLES, rest, left)
        loads += Fill(step, n)
        rest -= n
        left -= n
      }
    }
    while (left > 0 && loads.size < stands) {
      val n = minOf(STAND_BOTTLES, left)
      loads += Fill(0, n)
      left -= n
    }
    return loads
  }

  /** A stand that holds bottles already: the chain step they start at (0 is water) and how many. */
  data class Held(val step: Int, val bottles: Int)

  /**
   * Which of [stands] brew [total] potions, as indexes into it with the bottles each gets beside its own: the furthest
   * along first (the nearest first within a step, as [stands] comes), only until [total] is reached, so stands kept
   * ready stay ready. Each is filled up to three with what [topUp] gives for its step out of what it asks for.
   */
  fun standing(stands: List<Held>, total: Int, topUp: (step: Int, wanted: Int) -> Int): List<Pair<Int, Int>> {
    val picked = mutableListOf<Pair<Int, Int>>()
    var have = 0
    for (index in stands.indices.sortedByDescending { stands[it].step }) {
      if (have >= total) break
      val (step, bottles) = stands[index]
      val wanted = minOf(STAND_BOTTLES - bottles, total - have - bottles)
      val extra = if (wanted > 0) topUp(step, wanted).coerceIn(0, wanted) else 0
      picked += index to extra
      have += bottles + extra
    }
    return picked
  }

  /**
   * Hands [loads] out to [stands] stands, one round after another: the first [stands] loads start at once, and each
   * later one waits in a stand's queue for the load before it to finish, the queues as even as can be.
   */
  fun <T> rounds(loads: List<T>, stands: Int): List<List<T>> {
    if (stands <= 0) return emptyList()
    val queues = List(minOf(stands, loads.size)) { mutableListOf<T>() }
    loads.forEachIndexed { index, load -> queues[index % queues.size] += load }
    return queues
  }

  /** The brews in one recipe file: one per input potion. Tags and shapes the game doesn't ship give none. */
  fun parse(json: String): List<Brew> = runCatching {
    val root = Json.parseToJsonElement(json).jsonObject
    if (id(root["type"]) != "brewing") return emptyList()
    val input = root["input"]?.jsonObject ?: return emptyList()
    val from = id(input["item"]) ?: return emptyList()
    val potions = ids(input["potion_contents"]?.jsonObject?.get("potions"))
    val reagents = ids(root["reagent"]?.jsonObject?.get("item"))
    val output = root["output"]?.jsonObject ?: return emptyList()
    val to = id(output["id"]) ?: return emptyList()
    val contents = output["components"]?.jsonObject?.get("minecraft:potion_contents")
    val made = id((contents as? JsonObject)?.get("potion") ?: contents) ?: return emptyList()
    potions.flatMap { potion -> reagents.map { reagent -> Brew(Bottle(from, potion), reagent, Bottle(to, made)) } }
  }.getOrDefault(emptyList())

  /**
   * The brews from a water bottle to [target], or null when the stand can't make it. The usual way first: brew the
   * drinkable potion, then gunpowder and dragon's breath turn the bottle; otherwise the fewest brews any way.
   */
  fun chain(brews: List<Brew>, target: Bottle): List<Brew>? {
    if (target.item != "potion") usual(brews, target)?.let { return it }
    return shortest(brews, target)
  }

  private fun usual(brews: List<Brew>, target: Bottle): List<Brew>? {
    val path = shortest(brews, Bottle("potion", target.potion))?.toMutableList() ?: return null
    var bottle = Bottle("potion", target.potion)
    while (bottle != target) {
      val next = FORMS.getOrNull(FORMS.indexOf(bottle.item) + 1) ?: return null
      val brew = brews.firstOrNull { it.from == bottle && it.to == Bottle(next, target.potion) } ?: return null
      path += brew
      bottle = brew.to
    }
    return path
  }

  private fun shortest(brews: List<Brew>, target: Bottle): List<Brew>? {
    if (target == WATER) return emptyList()
    val came = HashMap<Bottle, Brew>()
    val queue = ArrayDeque(listOf(WATER))
    while (queue.isNotEmpty()) {
      val bottle = queue.removeFirst()
      for (brew in brews) {
        if (brew.from != bottle || brew.to == WATER || brew.to in came) continue
        came[brew.to] = brew
        if (brew.to == target) return generateSequence(brew) { came[it.from] }.toList().reversed()
        queue += brew.to
      }
    }
    return null
  }

  /** Every potion the stand makes, in the game's ids. */
  fun potions(brews: List<Brew>): Set<String> = brews.map { it.to.potion }.toSortedSet()

  /**
   * The potion the model or a player named: an exact id, or a part of one (“night vision” finds `night_vision`
   * before `long_night_vision`), else what [named] makes of it: the catalog's names in any language. Null when
   * nothing matches.
   */
  fun find(brews: List<Brew>, name: String, named: (String) -> String? = { null }): String? {
    var wanted = split(name).first
    // “healing ii” and “healing 2” are strong_healing, “extended swiftness” long_swiftness.
    Regex("_(ii|2)$").find(wanted)?.let { wanted = "strong_" + wanted.removeRange(it.range) }
    if (wanted.startsWith("extended_")) wanted = "long_" + wanted.removePrefix("extended_")
    val prefix = listOf("strong_", "long_").firstOrNull { wanted.startsWith(it) }.orEmpty()
    wanted = prefix + wanted.removePrefix(prefix).let { WORDS[it] ?: it }
    val known = potions(brews)
    val raw = name.trim().lowercase().removePrefix("minecraft:")
    return known.firstOrNull { it == wanted || it == raw } ?: known.filter { wanted in it }.minByOrNull { it.length }
      ?: named(name)?.takeIf { it in known }
  }

  /** What players call potions whose ids say it otherwise. */
  private val WORDS = mapOf(
    "speed" to "swiftness", "jump" to "leaping", "jump_boost" to "leaping", "instant_health" to "healing", "health" to "healing",
    "heal" to "healing", "instant_damage" to "harming", "damage" to "harming", "regen" to "regeneration", "fire_res" to "fire_resistance",
    "night" to "night_vision", "invisible" to "invisibility", "breathing" to "water_breathing", "slow_fall" to "slow_falling",
    // The names the game shows for the 1.21 potions differ from their ids.
    "infestation" to "infested", "wind_charging" to "wind_charged", "turtle" to "turtle_master",
  )

  /** A form as the model may write it: `splash`, `splash_potion`, `lingering`, `drinkable`; null when it's none of them. */
  fun form(name: String): String? = when (Names.normalize(name).removeSuffix("s")) {
    "potion", "drinkable", "normal", "regular", "plain", "bottle", "drink" -> "potion"
    "splash", "splash_potion", "throwable" -> "splash_potion"
    "lingering", "lingering_potion" -> "lingering_potion"
    else -> null
  }

  /** A potion's name without the words around it, and the form they name: “Splash Potion of Healing” is healing, splash_potion. */
  fun split(name: String): Pair<String, String?> {
    var rest = Names.normalize(name)
    var form: String? = null
    for ((word, found) in listOf("splash_" to "splash_potion", "lingering_" to "lingering_potion")) {
      if (rest.startsWith(word)) { rest = rest.removePrefix(word); form = found }
    }
    rest = rest.removePrefix("potion_of_").removePrefix("potions_of_").removePrefix("bottle_of_").removeSuffix("_potions").removeSuffix("_potion")
    return rest to form
  }

  private fun strip(id: String) = id.removePrefix("minecraft:")

  private fun id(element: JsonElement?): String? = (element as? JsonPrimitive)?.content?.takeUnless { it.startsWith("#") }?.let(::strip)

  private fun ids(element: JsonElement?): List<String> = when (element) {
    is JsonArray -> element.mapNotNull(::id)
    else -> listOfNotNull(id(element))
  }
}
