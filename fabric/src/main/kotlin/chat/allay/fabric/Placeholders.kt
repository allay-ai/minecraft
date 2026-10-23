package chat.allay.fabric

import net.minecraft.server.level.ServerPlayer

/** Text Placeholder API, when it's there: what fills `{papi:…}` in a text for the player it's about. */
object Placeholders {
  fun about(platform: FabricPlatform, player: ServerPlayer?): ((String) -> String?)? = null
}
