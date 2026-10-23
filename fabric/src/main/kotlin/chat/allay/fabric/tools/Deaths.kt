package chat.allay.fabric.tools

import chat.allay.core.PlayerData
import chat.allay.core.tools.Death

import java.util.UUID

/** Stands in until deaths are ported: nothing is remembered. */
class Deaths(private val data: PlayerData) {
  fun latest(uuid: UUID): Death? = null
  fun start() {}
  fun forget(uuid: UUID) {}
}
