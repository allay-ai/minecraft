package chat.allay.fabric

import chat.allay.core.Asker
import chat.allay.core.Audience
import chat.allay.core.Protocol
import chat.allay.core.Run
import chat.allay.core.tools.Dimensions
import chat.allay.fabric.tools.Worlds
import chat.allay.fabric.tools.id

import java.util.UUID

/** Whoever asked, as the core sees them; [language] is the one Allay speaks to them in, which beats the game's. */
class SourceAsker(val audience: Audience, private val language: String? = null) : Asker {
  override val name: String get() = audience.name
  override val uuid: UUID? get() = audience.uuid
  override val operator: Boolean get() = audience.operator
  override val locale: String? get() = language ?: (audience as? FabricPlayer)?.locale
  // Read once here, on the server thread: the request is built off it.
  override val dimension: String? = (audience as? FabricPlayer)?.player?.level()?.let(Dimensions::id)
  override val position: Protocol.Position? = (audience as? FabricPlayer)?.player?.let { Protocol.Position(it.blockX, it.blockY, it.blockZ, Dimensions.id(it.level()), Worlds.name(it.level())) }
  override fun can(permission: String) = audience.can(permission)
}

/** Who asked this run. */
val Run.audience: Audience get() = (asker as SourceAsker).audience
