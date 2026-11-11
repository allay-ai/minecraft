package chat.allay.core.building

/**
 * A preview as blocks only its player sees: the server tells that client a block stands where the world has another,
 * chunk by chunk, and the world stays as it is. What both platforms share: which cells land in which chunk, and when a
 * chunk goes to the client again.
 */
object Showing {
  /** A chunk as one Long: x in the high half, z in the low. */
  fun chunk(cx: Int, cz: Int): Long = (cx.toLong() shl 32) or (cz.toLong() and 0xFFFFFFFFL)

  fun chunkX(key: Long) = (key shr 32).toInt()
  fun chunkZ(key: Long) = key.toInt()

  /** A world spot as one Long, as the game packs a block's place: 26 bits each for x and z, 12 for y. */
  fun spot(x: Int, y: Int, z: Int): Long = ((x.toLong() and 0x3FFFFFF) shl 38) or ((z.toLong() and 0x3FFFFFF) shl 12) or (y.toLong() and 0xFFF)

  fun spotX(spot: Long) = (spot shr 38).toInt()
  fun spotY(spot: Long) = ((spot shl 52) shr 52).toInt()
  fun spotZ(spot: Long) = ((spot shl 26) shr 38).toInt()

  /** [blueprint]'s blocks at [placement] by the world chunk they land in, as [Shell.pack]s. */
  fun chunks(blueprint: Blueprint, placement: Placement): Map<Long, IntArray> {
    val lists = HashMap<Long, IntList>()
    blueprint.forEach { x, y, z, _ ->
      val (wx, _, wz) = placement.world(blueprint, x, y, z)
      lists.getOrPut(chunk(wx shr 4, wz shr 4)) { IntList() }.add(Shell.pack(x, y, z))
    }
    return lists.mapValues { it.value.toArray() }
  }

  /**
   * When a chunk of a preview goes to its client: once the client has the chunk, then again [AGAIN] ticks later, in
   * case the chunk itself reached the client after the blocks (Spigot can't tell when it does), then every [REFRESH]
   * ticks, and at once when something in it changed ([touch]): a block the server sends there takes the preview's
   * place. A chunk the client let go is [forget]ten, and goes from the start when it comes back.
   */
  class Schedule {
    private class Sent(var at: Long, var times: Int)

    private val sent = HashMap<Long, Sent>()
    private val touched = HashSet<Long>()

    fun due(chunk: Long, now: Long): Boolean {
      if (chunk in touched) return true
      val was = sent[chunk] ?: return true
      val wait = AGAIN.getOrNull(was.times - 1) ?: REFRESH
      return now - was.at >= wait
    }

    /** [chunk] went to the client at [now]. */
    fun sent(chunk: Long, now: Long) {
      touched -= chunk
      val was = sent[chunk]
      if (was == null) sent[chunk] = Sent(now, 1) else {
        was.at = now
        was.times++
      }
    }

    fun touch(chunk: Long) {
      if (chunk in sent) touched += chunk
    }

    fun forget(chunk: Long) {
      sent -= chunk
      touched -= chunk
    }

    fun forgetAll() {
      sent.clear()
      touched.clear()
    }

    /** The chunks the client has the preview in. */
    val chunks: Set<Long> get() = sent.keys

    companion object {
      /** Ticks after the first sending to send again, one after another. */
      val AGAIN = longArrayOf(20, 100)
      const val REFRESH = 200L
    }
  }

  private class IntList {
    private var items = IntArray(64)
    private var size = 0

    fun add(value: Int) {
      if (size == items.size) items = items.copyOf(size * 2)
      items[size++] = value
    }

    fun toArray(): IntArray = items.copyOf(size)
  }
}
