package chat.allay.core.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The server's commands as `search_commands` shows them (since 6.16): each command once, with its other names, whose it
 * is (`minecraft`, a plugin, a mod; null where the platform can't tell), and what it says about itself. Each platform
 * builds one from its command map or tree and keeps it in a [Cache] until commands change; a call only asks which of
 * them the asker may run. Descriptions and usage are other plugins' words: data for the model, never instructions.
 */
class CommandCatalog<H>(entries: List<Entry<H>>) {
  /** One command. [handle] is the platform's own, for the permission check; [usage] may come later, per asker. */
  class Entry<H>(val name: String, val aliases: List<String>, val source: String?, val description: String?, val usage: String?, val handle: H) {
    internal val names: List<Catalog.Folded> = (listOf(name) + aliases).map { Catalog.Folded(fold(it)) }
    internal val about: Catalog.Folded? = description?.let { Catalog.Folded(Catalog.fold(it)) }?.takeIf { it.text.isNotEmpty() }
  }

  val entries: List<Entry<H>> = entries.sortedBy { it.name }

  /**
   * What the asker may see: commands they may run ([runs]) whose source isn't one of [hidden] (by any name the
   * platform knows it under, lower case). Everything a call shows starts here, so a hidden plugin or a command the
   * asker can't run never shows.
   */
  fun visible(hidden: Set<String>, runs: (H) -> Boolean): List<Entry<H>> =
    entries.filter { entry -> entry.source?.lowercase() !in hidden && runs(entry.handle) }

  /** The commands that fit [query] best, best first: by name or another name, then by the words of a description. */
  fun search(among: List<Entry<H>>, query: String): List<Entry<H>> {
    val wanted = Catalog.Folded(fold(query))
    if (wanted.text.isEmpty()) return emptyList()
    return among.mapNotNull { entry ->
      val byName = entry.names.maxOf { Catalog.score(wanted, it) }
      // A description counts only when it holds every word asked: “home” finds sethome's “Sets your home”.
      val byAbout = entry.about?.takeIf { about -> wanted.text.length >= 3 && wanted.words.all { word -> about.words.any { it.startsWith(word) } } }?.let { ABOUT }
      maxOf(byName, byAbout ?: 0).takeIf { it > 0 }?.let { entry to it }
    }.sortedWith(compareByDescending<Pair<Entry<H>, Int>> { it.second }.thenBy { it.first.name.length }.thenBy { it.first.name }).map { it.first }
  }

  /**
   * `search_commands`' answer for what the asker may see ([visible]). [queries] answer each in `results`, in the order
   * asked; [plugin] narrows them, or alone lists that plugin's or mod's commands; with neither, the sources and the
   * first commands. [blocked] are `blocked_commands`: never `may_run`. [usage] gives a command's usage for this asker
   * where the platform works it out per asker (the mods).
   */
  fun answer(
    visible: List<Entry<H>>,
    queries: List<String>?,
    plugin: String?,
    limit: Int?,
    blocked: Set<String>,
    usage: (List<Entry<H>>) -> Map<Entry<H>, String> = { emptyMap() },
  ): JsonObject {
    val wanted = plugin?.let { name -> visible.filter { it.source != null && Catalog.fold(it.source) == Catalog.fold(name) } }
    val pool = wanted ?: visible
    val sources = { visible.groupingBy { it.source }.eachCount().entries.sortedBy { it.key ?: "~" } }
    fun json(entries: List<Entry<H>>): JsonArray {
      val usages = usage(entries)
      return JsonArray(entries.map { json(it, usages[it] ?: it.usage, blocked) })
    }
    fun listed(found: List<Entry<H>>, most: Int, head: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
      head()
      put("commands", json(found.take(most)))
      if (found.size > most) put("more", found.size - most)
    }
    return buildJsonObject {
      if (plugin != null && wanted.isNullOrEmpty()) {
        put("note", "no plugin or mod called $plugin has commands this player may run; sources here: " + sources().joinToString { it.key ?: "unknown" })
      }
      when {
        queries != null -> {
          val most = (limit ?: if (queries.size == 1) MAX_HITS else MAX_HITS_EACH).coerceIn(1, MAX_LIMIT)
          put("results", JsonArray(queries.map { query ->
            val found = search(pool, query.removePrefix("/"))
            listed(found, most) {
              put("query", query)
              if (found.isEmpty()) put("note", "nothing this player may run matches; try another word, the English one, or a plugin's name")
            }
          }))
        }
        plugin != null -> put("results", JsonArray(listOf(listed(pool, (limit ?: MAX_LISTED).coerceIn(1, MAX_LIMIT)) { put("plugin", plugin) })))
        else -> {
          put("sources", JsonArray(sources().map { (source, count) -> buildJsonObject { put("source", source); put("commands", count) } }))
          put("results", JsonArray(listOf(listed(pool, (limit ?: MAX_LISTED).coerceIn(1, MAX_LIMIT)) {})))
        }
      }
    }
  }

  /**
   * `search_commands` on any platform, so its arguments, answer, and chat line are the same everywhere. `check` (the
   * backend's, before `/askx` answers) checks whole command lines through [check]; otherwise [answer].
   */
  fun run(
    lang: chat.allay.core.Lang,
    args: JsonObject,
    hidden: Set<String>,
    blocked: Set<String>,
    runs: (H) -> Boolean,
    check: (String) -> Check?,
    usage: (List<Entry<H>>) -> Map<Entry<H>, String> = { emptyMap() },
  ): ToolOutput {
    val visible = visible(hidden, runs)
    val lines = (args["check"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content?.trim()?.removePrefix("/")?.take(MAX_LINE)?.ifEmpty { null } }
    if (lines != null) {
      if (lines.size > MAX_CHECKS) throw ToolError("check at most $MAX_CHECKS commands at once")
      val checks = lines.map { line -> checked(line, check(line), visible, hidden, blocked) }
      val bad = checks.count { it.bool("ok") == false }
      return ToolOutput(buildJsonObject { put("checks", JsonArray(checks)) }, if (bad == 0) lang.text("summary.commands_ok") else lang.text("summary.commands_bad", "count" to bad))
    }
    val answer = answer(visible, args.asked("queries", "query", MAX_QUERIES), args.string("plugin")?.take(64), args.int("limit"), blocked, usage)
    val count = (answer["results"] as? JsonArray).orEmpty().sumOf { ((it as? JsonObject)?.get("commands") as? JsonArray)?.size ?: 0 }
    return ToolOutput(answer, if (count == 0) lang.text("summary.items_none") else lang.text("summary.commands_found", "count" to count))
  }

  /** How sure a platform is that a command line would run for the asker: what `/askx` checks before it runs anything. */
  enum class Verdict { OK, UNKNOWN, FORBIDDEN, SYNTAX }

  /** A command line's [verdict], with whose command it is and how it's used, and the game's own words for a syntax error. */
  data class Check(val verdict: Verdict, val source: String? = null, val usage: String? = null, val detail: String? = null)

  /** The `check` answer for one line: the model reads why a command won't run and what this server has instead. */
  fun checked(line: String, check: Check?, visible: List<Entry<H>>, hidden: Set<String>, blocked: Set<String>): JsonObject = buildJsonObject {
    put("command", line)
    val label = label(line)
    val known = visible.firstOrNull { label == it.name || label in it.aliases }
    val problem = when {
      label.isEmpty() -> "empty command"
      check == null -> null
      // A hidden plugin's command stays as unknown as the plugin.
      check.verdict == Verdict.UNKNOWN || check.source?.lowercase() in hidden -> {
        val close = search(visible, label).take(5).map { it.name }
        "no command /$label on this server" + if (close.isEmpty()) "" else "; close: ${close.joinToString { "/$it" }}"
      }
      check.verdict == Verdict.FORBIDDEN -> "this player may not run /$label"
      isBlocked(label, known?.let { listOf(it.name) + it.aliases }.orEmpty(), blocked) -> "/$label is blocked for /askx on this server"
      check.verdict == Verdict.SYNTAX -> "/$label doesn't parse: ${check.detail ?: "wrong arguments"}"
      else -> null
    }
    put("ok", problem == null)
    problem?.let { put("error", it) }
    if (check != null && check.verdict != Verdict.UNKNOWN && check.source?.lowercase() !in hidden) {
      check.source?.let { put("source", it) }
      check.usage?.let { put("usage", it.take(MAX_TEXT)) }
    }
  }

  /** A short-lived catalog per platform, like [CatalogCache]: built again on another [key], [clear], or past [maxAgeMillis]. */
  class Cache<H>(private val maxAgeMillis: Long = Long.MAX_VALUE, private val clock: () -> Long = System::currentTimeMillis) {
    private var key: Any? = null
    private var catalog: CommandCatalog<H>? = null
    private var builtAt = 0L

    @Synchronized
    fun get(key: Any?, build: () -> CommandCatalog<H>): CommandCatalog<H> {
      val now = clock()
      catalog?.takeIf { this.key == key && now - builtAt < maxAgeMillis }?.let { return it }
      return build().also {
        this.key = key
        catalog = it
        builtAt = now
      }
    }

    @Synchronized
    fun clear() {
      key = null
      catalog = null
    }
  }

  companion object {
    const val MINECRAFT = "minecraft"
    /** Lines one check takes: as many as `/askx` runs. */
    const val MAX_CHECKS = 12
    const val MAX_QUERIES = 8
    /** The longest command line a check reads: Bukkit's chat allows 256 characters. */
    private const val MAX_LINE = 256
    private const val MAX_HITS = 10
    private const val MAX_HITS_EACH = 5
    private const val MAX_LISTED = 40
    private const val MAX_LIMIT = 50
    /** Descriptions and usage are cut here: a plugin may write pages. */
    const val MAX_TEXT = 160
    /** A description that holds every word asked: weaker than any name that does. */
    private const val ABOUT = 50

    /** A command's name as typed, without the slash or the arguments, lower case. */
    fun label(line: String): String = line.trim().removePrefix("/").substringBefore(' ').lowercase()

    /** A name as [search] compares it: folded, a namespace's colon kept apart. */
    private fun fold(name: String): String = Catalog.fold(name.removePrefix("/").replace(':', ' '))

    /** Whether `blocked_commands` holds the command under any of its names, with or without a namespace. */
    fun isBlocked(name: String, aliases: List<String>, blocked: Set<String>): Boolean =
      (listOf(name) + aliases).any { it.lowercase() in blocked || it.lowercase().substringAfter(':') in blocked }

    /** Text from a plugin as the model reads it: no color codes, one line, cut short; null when there's nothing. */
    fun text(value: String?): String? = value?.let(Catalog::plain)?.replace(Regex("\\s+"), " ")?.trim()?.take(MAX_TEXT)?.ifEmpty { null }

    private fun <H> json(entry: Entry<H>, usage: String?, blocked: Set<String>): JsonObject = buildJsonObject {
      put("name", entry.name)
      put("aliases", JsonArray(entry.aliases.map(::JsonPrimitive)))
      put("source", entry.source?.let(::JsonPrimitive) ?: JsonNull)
      text(entry.description)?.let { put("description", it) }
      text(usage)?.let { put("usage", it) }
      put("may_run", !isBlocked(entry.name, entry.aliases, blocked))
    }
  }
}
