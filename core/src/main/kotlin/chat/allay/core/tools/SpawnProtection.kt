package chat.allay.core.tools

import kotlin.math.abs

/**
 * The game's spawn protection in plain numbers (`DedicatedServer.isUnderSpawnProtection` in 26.3), for a platform
 * that can't ask the game itself: nobody may build near the world spawn but operators, and only once there are any.
 */
object SpawnProtection {
  /**
   * Whether a player may not build [dx] and [dz] blocks from the world spawn, in the world that holds it. [radius] is
   * `spawn-protection` from `server.properties`; 0 turns it off.
   */
  fun covers(operatorsExist: Boolean, operator: Boolean, radius: Int, dx: Int, dz: Int): Boolean =
    operatorsExist && !operator && radius > 0 && maxOf(abs(dx), abs(dz)) <= radius
}
