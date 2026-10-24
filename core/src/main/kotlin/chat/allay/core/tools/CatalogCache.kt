package chat.allay.core.tools

/**
 * One [Catalog] while nothing it's made of changes. Building one walks every recipe and registry entry, and a single
 * question may look things up a dozen times, so each platform keeps it here under a key of what it's made of: the
 * recipes, the expansions' and names' revisions. Another key builds it again, and so does [clear] or, where the
 * platform can't see every change, an age past [maxAgeMillis]. Server thread.
 */
class CatalogCache(private val maxAgeMillis: Long = Long.MAX_VALUE, private val clock: () -> Long = System::currentTimeMillis) {
  private var key: List<Any?>? = null
  private var catalog: Catalog? = null
  private var builtAt = 0L

  @Synchronized
  fun get(key: List<Any?>, build: () -> Catalog): Catalog {
    val now = clock()
    catalog?.takeIf { this.key == key && now - builtAt < maxAgeMillis }?.let { return it }
    return build().also {
      this.key = key
      catalog = it
      builtAt = now
    }
  }

  /** Builds it again on the next call: plugins, data packs, or the server changed. */
  @Synchronized
  fun clear() {
    key = null
    catalog = null
  }
}
