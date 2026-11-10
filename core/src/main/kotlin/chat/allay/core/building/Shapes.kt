package chat.allay.core.building

/**
 * The states the game works out from a block's neighbours when a player places it: fences, panes, bars, and walls
 * joining what stands beside them, stairs turning into corners, and a door's two halves agreeing. A schematic may
 * hold them otherwise: an old one (before 1.13) has no joins at all, and its door halves each know only half. The
 * builder places as a player would, so it takes the game's answer for the finished build. Pure: the game only says
 * which faces are sturdy.
 */
object Shapes {
  /** The horizontal sides, with their steps on x and z. */
  private val SIDES = listOf("north" to (0 to -1), "east" to (1 to 0), "south" to (0 to 1), "west" to (-1 to 0))

  private fun opposite(side: String) = when (side) {
    "north" -> "south"
    "south" -> "north"
    "east" -> "west"
    "west" -> "east"
    "up" -> "down"
    else -> "up"
  }

  private fun axis(side: String) = if (side == "north" || side == "south") 'z' else 'x'

  private fun counterClockwise(side: String) = when (side) {
    "north" -> "west"
    "west" -> "south"
    "south" -> "east"
    else -> "north"
  }

  private fun name(state: String) = Blueprint.block(state).removePrefix("minecraft:")

  private fun fence(name: String) = name.endsWith("_fence")
  private fun gate(name: String) = name.endsWith("_fence_gate")
  private fun bars(name: String) = name.endsWith("_pane") || name.endsWith("_bars")
  private fun stairs(name: String) = name.endsWith("_stairs")
  private fun door(name: String) = name.endsWith("_door") && !name.endsWith("trapdoor")
  private fun wall(state: String) = name(state).endsWith("_wall") && Blueprint.states(state)["up"] != null

  /** What never joins a fence, a pane, or a wall by its sturdy face alone. */
  private fun exception(name: String) = name.endsWith("_leaves") || name.endsWith("shulker_box") ||
    name in setOf("barrier", "carved_pumpkin", "jack_o_lantern", "melon", "pumpkin")

  /** What stands on a wall and keeps its post up (the game's `wall_post_override`). */
  private fun postOverride(name: String) = (name.endsWith("torch") && !name.endsWith("wall_torch")) || name.endsWith("_pressure_plate") ||
    (name.endsWith("_sign") && !name.endsWith("wall_sign") && !name.endsWith("hanging_sign")) || (name.endsWith("_banner") && !name.endsWith("wall_banner"))

  /** Whether a gate at [state] joins something on its [side]: its door swings across that side. */
  private fun gateJoins(state: String, side: String) = Blueprint.states(state)["facing"]?.let { axis(it) != axis(side) } == true

  /** [state] with [changes] to its properties, in the order it had them; one it lacked comes last. */
  fun with(state: String, changes: Map<String, String>): String {
    val properties = LinkedHashMap(Blueprint.states(state))
    properties.putAll(changes)
    return Blueprint.block(state) + if (properties.isEmpty()) "" else properties.entries.joinToString(",", "[", "]") { "${it.key}=${it.value}" }
  }

  /**
   * The cells of [blueprint] whose state the game would settle otherwise, with the state it settles on, as cells of
   * [Shell.pack]. [states] is the palette as built (swapped); [sturdy] says whether a state's [face] (`north`, `up`, …)
   * holds a full face, as the game reads it. Out of the box counts as air.
   */
  fun settle(blueprint: Blueprint, states: List<String>, sturdy: (state: String, face: String) -> Boolean): Map<Int, String> {
    val shaped = BooleanArray(states.size) { index ->
      val name = name(states[index])
      fence(name) || bars(name) || stairs(name) || door(name) || wall(states[index])
    }
    if (shaped.none { it }) return emptyMap()
    fun at(x: Int, y: Int, z: Int): String? {
      val index = blueprint.at(x, y, z)
      return if (blueprint.empty[index]) null else states[index]
    }
    val out = HashMap<Int, String>()
    blueprint.forEach { x, y, z, index ->
      if (!shaped[index]) return@forEach
      val state = states[index]
      val name = name(state)
      val settled = when {
        door(name) -> door(state, at(x, y + 1, z), at(x, y - 1, z))
        stairs(name) -> stairs(state) { side -> SIDES.first { it.first == side }.second.let { (dx, dz) -> at(x + dx, y, z + dz) } }
        wall(state) -> wall(state, at(x, y + 1, z), sturdy) { dx, dz -> at(x + dx, y, z + dz) }
        else -> joins(state, sturdy) { dx, dz -> at(x + dx, y, z + dz) }
      }
      if (settled != state) out[Shell.pack(x, y, z)] = settled
    }
    return out
  }

  /** A door's half with what the other half knows: the lower one keeps the facing and whether it's open, the upper one the hinge. */
  private fun door(state: String, above: String?, below: String?): String {
    val properties = Blueprint.states(state)
    return when (properties["half"]) {
      "lower" -> above?.takeIf { Blueprint.block(it) == Blueprint.block(state) && Blueprint.states(it)["half"] == "upper" }
        ?.let { upper -> with(state, Blueprint.states(upper).filterKeys { it == "hinge" }) } ?: state
      "upper" -> below?.takeIf { Blueprint.block(it) == Blueprint.block(state) && Blueprint.states(it)["half"] == "lower" }
        ?.let { lower -> with(state, Blueprint.states(lower).filterKeys { it == "facing" || it == "open" || it == "powered" }) } ?: state
      else -> state
    }
  }

  /** A fence's, pane's, or bars' joins to its four sides. */
  private fun joins(state: String, sturdy: (String, String) -> Boolean, beside: (Int, Int) -> String?): String {
    val name = name(state)
    val changes = SIDES.associate { (side, step) ->
      val other = beside(step.first, step.second)
      val joined = other != null && run {
        val otherName = name(other)
        val solid = !exception(otherName) && sturdy(other, opposite(side))
        if (fence(name)) solid || (fence(otherName) && (otherName == "nether_brick_fence") == (name == "nether_brick_fence")) || (gate(otherName) && gateJoins(other, side))
        else solid || bars(otherName) || wall(other)
      }
      side to joined.toString()
    }
    return with(state, changes)
  }

  /** A wall's sides (low, or tall under something that covers them) and whether its post stands up. */
  private fun wall(state: String, above: String?, sturdy: (String, String) -> Boolean, beside: (Int, Int) -> String?): String {
    val covered = above != null && sturdy(above, "down")
    val aboveProperties = above?.let(Blueprint::states).orEmpty()
    val sides = SIDES.associate { (side, step) ->
      val other = beside(step.first, step.second)
      val joined = other != null && run {
        val otherName = name(other)
        wall(other) || (!exception(otherName) && sturdy(other, opposite(side))) || bars(otherName) || (gate(otherName) && gateJoins(other, side))
      }
      val tall = covered || (above != null && wall(above) && aboveProperties[side] != "none")
      side to if (!joined) "none" else if (tall) "tall" else "low"
    }
    val none = sides.mapValues { it.value == "none" }
    val up = when {
      above != null && wall(above) && aboveProperties["up"] == "true" -> true
      none.values.all { it } || none["north"] != none["south"] || none["east"] != none["west"] -> true
      (sides["north"] == "tall" && sides["south"] == "tall") || (sides["east"] == "tall" && sides["west"] == "tall") -> false
      else -> above != null && (postOverride(name(above)) || covered)
    }
    return with(state, sides + ("up" to up.toString()))
  }

  /** Stairs' shape: a corner where stairs across them stand in front or behind, as the game turns them. */
  private fun stairs(state: String, beside: (String) -> String?): String {
    val properties = Blueprint.states(state)
    val facing = properties["facing"] ?: return state
    val half = properties["half"] ?: return state
    fun stairsAt(side: String) = beside(side)?.takeIf { stairs(name(it)) }?.let(Blueprint::states)
    fun mayTake(side: String) = stairsAt(side).let { it == null || it["facing"] != facing || it["half"] != half }
    val shape = run {
      stairsAt(facing)?.takeIf { it["half"] == half }?.get("facing")?.let { front ->
        if (axis(front) != axis(facing) && mayTake(opposite(front))) return@run if (front == counterClockwise(facing)) "outer_left" else "outer_right"
      }
      stairsAt(opposite(facing))?.takeIf { it["half"] == half }?.get("facing")?.let { back ->
        if (axis(back) != axis(facing) && mayTake(back)) return@run if (back == counterClockwise(facing)) "inner_left" else "inner_right"
      }
      "straight"
    }
    return with(state, mapOf("shape" to shape))
  }
}
