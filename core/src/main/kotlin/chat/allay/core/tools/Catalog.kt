package chat.allay.core.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Every item and potion this server knows, as the model may name them: the game's registries (mods' items included),
 * items plugins build on the game's own (an item model of their own: Red Bull's cans), and what expansions describe.
 * Names come from [GameNames], in any language the game has. The single source of truth for “is there such a thing”:
 * the model's memory knows neither this server's mods nor what changed after it learned.
 */
class Catalog(val entries: List<Entry>, private val names: GameNames?) {
  enum class Kind {
    ITEM, POTION, CUSTOM;

    val wire get() = name.lowercase()
  }

  /**
   * One thing to find. [id] is the game's id without the game's namespace (`diamond_sword`, `mymod:wrench`), a potion's
   * id for a potion (`infested`), or a plugin's item's own id (`redbull:original`). [keys] are its translation keys,
   * the first one the name; [names] what an expansion or the item itself names it by, per language. [source] is where it
   * comes from: `minecraft`, a mod, a plugin, a data pack, or an expansion.
   */
  data class Entry(
    val id: String,
    val kind: Kind,
    val keys: List<String> = emptyList(),
    val names: Map<String, String> = emptyMap(),
    val source: String = MINECRAFT,
    /** The game's item underneath a plugin's item, like `potion`. */
    val base: String? = null,
    val description: String? = null,
    /** How this server makes it: `crafting`, `smelting`, `stonecutting`, `smithing`, `brewing`. */
    val made: Set<String> = emptySet(),
  )

  /** A hit of [search]: its name in the reader's language and in English, and how well it fits (100 is exact). */
  data class Match(val entry: Entry, val name: String?, val english: String?, val score: Int)

  private val byId: Map<Pair<Kind, String>, Entry> = entries.associateBy { it.kind to it.id }

  fun find(id: String, kind: Kind): Entry? = byId[kind to id]

  /** Its name in [locale], else in English, else null: an id the model can still use. */
  fun name(entry: Entry, locale: String?): String? {
    val code = locale?.let(GameNames::code)
    if (code != null) (entry.names[code] ?: lookup(entry, code))?.let { return it }
    return entry.names[GameNames.ENGLISH] ?: lookup(entry, GameNames.ENGLISH) ?: entry.names.values.firstOrNull()
  }

  private fun lookup(entry: Entry, locale: String): String? {
    val table = names?.table(locale) ?: return null
    return entry.keys.firstNotNullOfOrNull { table[it] }?.let(::plain)
  }

  /** A name as [search] compares it: folded once, with its words and its letters without spaces. */
  class Folded(val text: String) {
    val compact: String = text.replace(" ", "")
    val words: List<String> = text.split(' ')
  }

  /** Every name each entry goes by in [languages], folded once, in the order of [entries]. */
  private class Index(val languages: Set<String>, val forms: List<Array<Folded>>)

  @Volatile private var index: Index? = null

  /**
   * The names to search by: the id, the names an expansion or the item gives, and every translation key in every
   * language Allay has, the reader's included. Built once, and again only when Allay learns a language: a player may
   * name a thing in another language than their game's, and a tool may not know whose game it is.
   */
  private fun index(code: String?): Index {
    val languages = names?.known().orEmpty() + GameNames.ENGLISH + listOfNotNull(code)
    index?.takeIf { it.languages.containsAll(languages) }?.let { return it }
    val tables = languages.mapNotNull { names?.table(it) }
    val forms = entries.map { entry ->
      val all = LinkedHashSet<String>()
      all += fold(entry.id); all += fold(entry.id.substringAfter(':'))
      entry.names.values.forEach { all += fold(it) }
      for (table in tables) entry.keys.forEach { key -> table[key]?.let { all += fold(it) } }
      all.filter { it.isNotEmpty() }.map(::Folded).toTypedArray()
    }
    return Index(languages, forms).also { index = it }
  }

  /**
   * What fits [query], an id or a name in any language the game has, best first: the exact id or name, then names that
   * start with it or hold its words, then near spellings (“infestation” finds `infested`, the Potion of Infestation).
   */
  fun search(query: String, locale: String?, limit: Int = 10, kinds: Set<Kind> = Kind.entries.toSet()): List<Match> {
    val wanted = Folded(fold(query))
    if (wanted.text.isEmpty()) return emptyList()
    val code = locale?.let(GameNames::code)
    val forms = index(code).forms
    val hits = ArrayList<Pair<Entry, Int>>()
    entries.forEachIndexed { at, entry ->
      if (entry.kind !in kinds) return@forEachIndexed
      var best = 0
      for (form in forms[at]) {
        best = maxOf(best, score(wanted, form))
        if (best == EXACT) break
      }
      if (best > 0) hits += entry to best
    }
    val local = code?.let { names?.table(it) }.orEmpty()
    val english = names?.table(GameNames.ENGLISH).orEmpty()
    return hits.sortedWith(compareByDescending<Pair<Entry, Int>> { it.second }.thenBy { if (it.first.source == MINECRAFT) 0 else 1 }.thenBy { it.first.id.length }.thenBy { it.first.id })
      .take(limit).map { (entry, score) ->
        // Names only for what's shown: the reader's language, else English.
        val localName = code?.let { entry.names[it] ?: entry.keys.firstNotNullOfOrNull(local::get) }
        val englishName = entry.names[GameNames.ENGLISH] ?: entry.keys.firstNotNullOfOrNull(english::get)
        Match(entry, localName?.let(::plain) ?: englishName?.let(::plain), englishName?.let(::plain), score)
      }
  }

  /**
   * The one thing [query] means: an exact id or name, or the single best hit that holds every word of it. Null when
   * nothing fits that well, or two things fit equally.
   */
  fun resolve(query: String, locale: String?, kinds: Set<Kind> = Kind.entries.toSet()): Entry? {
    val hits = search(query, locale, 2, kinds)
    val best = hits.firstOrNull() ?: return null
    if (best.score < STRONG) return null
    val second = hits.getOrNull(1)
    return best.entry.takeIf { second == null || second.score < best.score }
  }

  /**
   * `search_items`' answer: what fits [query], best first, with names, where each comes from, and how it's made. Never
   * “doesn't exist”: what this server doesn't have may still be a mod's or a later version's.
   */
  fun answer(query: String, locale: String?, limit: Int): JsonObject {
    val hits = search(query, locale, limit.coerceIn(1, 20))
    return buildJsonObject {
      put("query", query)
      put("matches", buildJsonArray { hits.forEach { add(json(it)) } })
      if (hits.isEmpty()) put("note", "nothing on this server matches; that doesn't prove it doesn't exist elsewhere: try another name, the English name, or an id")
    }
  }

  /** `search_items` for several names at once (since 6.13): each one's [answer], in the order asked. */
  fun answers(queries: List<String>, locale: String?, limit: Int): JsonObject =
    buildJsonObject { put("results", JsonArray(queries.map { answer(it, locale, limit) })) }

  private fun json(hit: Match): JsonObject = buildJsonObject {
    put("id", hit.entry.id)
    put("kind", hit.entry.kind.wire)
    hit.name?.let { put("name", it) }
    if (hit.english != null && hit.english != hit.name) put("name_en", hit.english)
    put("source", hit.entry.source)
    hit.entry.base?.let { put("base", it) }
    hit.entry.description?.let { put("description", it.take(300)) }
    if (hit.entry.made.isNotEmpty()) put("made", JsonArray(hit.entry.made.sorted().map(::JsonPrimitive)))
    if (hit.score >= EXACT) put("exact", true)
  }

  /** “no item called X”, with what this server has under close names, for a tool that looked for one thing. */
  fun missing(query: String, locale: String?, kinds: Set<Kind> = Kind.entries.toSet()): String {
    val close = search(query, locale, 5, kinds).map { hit -> hit.name?.let { "$it (${hit.entry.id})" } ?: hit.entry.id }
    return "no item called $query on this server" + if (close.isEmpty()) "; call search_items with another name or the English one" else "; close: ${close.joinToString()}"
  }

  companion object {
    /**
     * [base] with [extra] laid over it: an expansion's word on a thing the registries or recipes already show (a mod's
     * own item, a plugin's can) joins that entry, its names, description, and ways to make it added; the rest is new.
     */
    fun merge(base: List<Entry>, extra: List<Entry>): List<Entry> {
      val byId = LinkedHashMap<String, Entry>()
      base.forEach { byId.putIfAbsent(it.id + "|" + it.kind.takeIf { kind -> kind == Kind.POTION }, it) }
      for (entry in extra) {
        val key = entry.id + "|null"
        val known = byId[key]
        byId[key] = known?.copy(
          names = known.names + entry.names,
          description = entry.description ?: known.description,
          base = known.base ?: entry.base,
          made = known.made + entry.made,
        ) ?: entry
      }
      return byId.values.toList()
    }

    const val MINECRAFT = "minecraft"
    const val EXACT = 100
    /** A hit that holds every word of the query; resolve takes nothing weaker. */
    const val STRONG = 70

    /** Lower case, `ё` as `е`, no color codes, no game namespace, words split by anything that isn't a letter or digit. */
    fun fold(text: String): String = plain(text).lowercase().replace('ё', 'е').replace("minecraft:", "")
      .replace(Regex("[^\\p{L}\\p{N}:]+"), " ").trim()

    /** A name without its `§` codes. */
    fun plain(text: String): String = text.replace(Regex("§."), "").trim()

    /** How well a folded [candidate] fits a folded [wanted]: 0 for not at all. */
    fun score(wanted: String, candidate: String): Int = score(Folded(wanted), Folded(candidate))

    /** [score] on names folded beforehand: [search] runs it for every name of every entry. */
    fun score(wanted: Folded, candidate: Folded): Int {
      val text = candidate.text
      if (text.isEmpty()) return 0
      if (text == wanted.text) return EXACT
      if (candidate.compact == wanted.compact) return 95
      if (text.startsWith(wanted.text)) return 85
      val words = candidate.words
      val asked = wanted.words
      if (asked.all { word -> words.any { it.startsWith(word) } }) return 75
      if (wanted.text.length >= 3 && wanted.text in text) return 65
      // Near spellings, and words that share a long start (infestation, infested), one word of the query at a time.
      var best = 0
      for (word in asked) {
        if (word.length < 4) continue
        val most = maxOf(1, word.length / 4)
        for (other in words) {
          if (best < 55 && kotlin.math.abs(word.length - other.length) <= minOf(most, 3)) {
            val distance = distance(word, other, most)
            if (distance <= most) best = maxOf(best, 55 - distance * 5)
          }
          if (best < 45 && word.length >= 5 && other.length >= 5) {
            val shared = shared(word, other)
            if (shared >= 5 && shared * 3 >= maxOf(word.length, other.length) * 2) best = 45
          }
        }
      }
      return if (asked.size > 1 && best > 0) best - 10 else best
    }

    private fun shared(a: String, b: String): Int {
      val most = minOf(a.length, b.length)
      var at = 0
      while (at < most && a[at] == b[at]) at++
      return at
    }

    /** The edit distance between [a] and [b], or anything above [most] once it can't come back under it. */
    private fun distance(a: String, b: String, most: Int): Int {
      var previous = IntArray(b.length + 1) { it }
      var row = IntArray(b.length + 1)
      for (i in 1..a.length) {
        row[0] = i
        var least = i
        for (j in 1..b.length) {
          row[j] = minOf(previous[j] + 1, row[j - 1] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
          if (row[j] < least) least = row[j]
        }
        if (least > most) return most + 1
        val swap = previous; previous = row; row = swap
      }
      return previous[b.length]
    }

    /** An expansion's recipe as `get_recipes` writes the game's; null for a brew, which goes into the brewing chains. */
    fun describe(recipe: chat.allay.expansion.Recipe): JsonObject? {
      val type = recipe.type()
      if (type == chat.allay.expansion.Recipe.Type.BREWING) return null
      return buildJsonObject {
        if (recipe.count() > 1) put("makes", recipe.count())
        put("type", type.wire())
        when (type) {
          chat.allay.expansion.Recipe.Type.CRAFTING_SHAPED -> {
            put("shape", buildJsonArray { recipe.shape().forEach { add(JsonPrimitive(it)) } })
            putJsonObject("key") { recipe.key().forEach { (symbol, ingredient) -> put(symbol.toString(), ingredient.text()) } }
          }
          chat.allay.expansion.Recipe.Type.CRAFTING_SHAPELESS -> put("ingredients", JsonArray(recipe.ingredients().map { JsonPrimitive(it.text()) }))
          chat.allay.expansion.Recipe.Type.STONECUTTING -> put("input", recipe.ingredients().first().text())
          else -> {
            put("input", recipe.ingredients().first().text())
            put("seconds", recipe.seconds())
            put("experience", recipe.experience())
          }
        }
      }
    }

    /** An expansion's brew that makes [id], from its bottle and reagent. */
    fun brew(id: String, recipe: chat.allay.expansion.Recipe): Brewing.Brew? {
      if (recipe.type() != chat.allay.expansion.Recipe.Type.BREWING) return null
      val (bottle, reagent) = recipe.ingredients().map { it.text() }
      return Brewing.Brew(Brewing.bottleOf(bottle), reagent, Brewing.Bottle(id, id))
    }
  }
}
