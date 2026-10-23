package chat.allay.neoforge

import chat.allay.modcore.Hooks
import chat.allay.modcore.Mod

import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.neoforged.bus.api.EventPriority
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.ServerChatEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.neoforged.neoforge.event.level.block.BreakBlockEvent
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent

/**
 * The mod's entry on NeoForge: hands NeoForge's events to the shared mod ([Mod]), which runs Allay from the core for
 * each server the game starts. What NeoForge has no event for, or fires at another moment than Fabric, `modcore`'s
 * mixins catch on both loaders, so both see the same moments.
 */
@net.neoforged.fml.common.Mod("allay")
class AllayMod(@Suppress("UNUSED_PARAMETER") modBus: IEventBus) {
  init {
    Mod.loader = OnNeoForge
    val bus = NeoForge.EVENT_BUS
    bus.addListener { event: ServerStartedEvent -> Mod.start(event.server) }
    bus.addListener { _: ServerStoppingEvent -> Mod.stop() }
    bus.addListener { _: ServerTickEvent.Post -> Mod.tick() }
    bus.addListener { event: RegisterCommandsEvent -> Mod.register(event.dispatcher) }
    bus.addListener { event: PermissionGatherEvent.Nodes -> OnNeoForge.nodes(event) }

    bus.addListener { event: PlayerEvent.PlayerLoggedInEvent -> (event.entity as? ServerPlayer)?.let(Mod::joined) }
    bus.addListener { event: PlayerEvent.PlayerLoggedOutEvent -> (event.entity as? ServerPlayer)?.let(Mod::left) }
    // Before every other listener sees it as an ordinary message, as Bukkit's LOWEST.
    bus.addListener(EventPriority.HIGHEST) { event: ServerChatEvent -> if (!Mod.chat(event.player, event.rawText)) event.isCanceled = true }
    // After every other listener had its say, as Fabric's AFTER: a break someone cancelled never happened.
    bus.addListener(EventPriority.LOWEST) { event: BreakBlockEvent ->
      val player = event.player
      val level = event.level
      if (!event.isCanceled && player is ServerPlayer && level is ServerLevel) Hooks.brokeBlock.each { it(player, level, event.pos) }
    }
    bus.addListener { event: PlayerEvent.PlayerRespawnEvent -> (event.entity as? ServerPlayer)?.let { player -> Hooks.arrived.each { it(player) } } }
    bus.addListener { event: PlayerEvent.PlayerChangedDimensionEvent -> (event.entity as? ServerPlayer)?.let { player -> Hooks.arrived.each { it(player) } } }
    bus.addListener { event: PlayerInteractEvent.LeftClickBlock ->
      val player = event.entity
      val level = event.level
      if (event.action == PlayerInteractEvent.LeftClickBlock.Action.START && player is ServerPlayer && level is ServerLevel && Hooks.hit(player, level, event.pos)) {
        event.isCanceled = true
      }
    }
  }
}
