package chat.allay.fabric

import chat.allay.modcore.Hooks
import chat.allay.modcore.Mod

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityLevelChangeEvents
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.AttackBlockCallback
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult

/**
 * The mod's entry on Fabric: hands Fabric API's events to the shared mod ([Mod]), which runs Allay from the core for
 * each server the game starts. What Fabric API has no event for, `modcore`'s mixins catch on every loader.
 */
class AllayMod : ModInitializer {
  override fun onInitialize() {
    Mod.loader = OnFabric
    ServerLifecycleEvents.SERVER_STARTED.register(Mod::start)
    ServerLifecycleEvents.SERVER_STOPPING.register { Mod.stop() }
    ServerTickEvents.END_SERVER_TICK.register { Mod.tick() }
    CommandRegistrationCallback.EVENT.register { dispatcher, _, _ -> Mod.register(dispatcher) }

    ServerPlayConnectionEvents.JOIN.register { handler, _, _ -> Mod.joined(handler.player) }
    ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> Mod.left(handler.player) }
    // Before every other listener sees it as an ordinary message, as Bukkit's LOWEST.
    ServerMessageEvents.ALLOW_CHAT_MESSAGE.register { message, player, _ -> Mod.chat(player, message.signedContent()) }
    PlayerBlockBreakEvents.AFTER.register { level, player, pos, _, _ ->
      if (player is ServerPlayer && level is ServerLevel) Hooks.brokeBlock.each { it(player, level, pos) }
    }
    ServerPlayerEvents.AFTER_RESPAWN.register { _, player, _ -> Hooks.arrived.each { it(player) } }
    ServerEntityLevelChangeEvents.AFTER_PLAYER_CHANGE_LEVEL.register { player, _, _ -> Hooks.arrived.each { it(player) } }
    AttackBlockCallback.EVENT.register { player, level, _, pos, _ ->
      if (player is ServerPlayer && level is ServerLevel && Hooks.hit(player, level, pos)) InteractionResult.FAIL else InteractionResult.PASS
    }
  }
}
