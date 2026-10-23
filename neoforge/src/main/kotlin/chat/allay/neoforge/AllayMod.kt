package chat.allay.neoforge

import chat.allay.modcore.Hooks
import chat.allay.modcore.Mod

import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.neoforged.bus.api.EventPriority
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.ServerChatEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent
import net.neoforged.neoforge.event.level.BlockEvent
import net.neoforged.neoforge.event.level.block.BreakBlockEvent
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.server.ServerStoppingEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent

/**
 * The mod's entry on NeoForge: hands NeoForge's events to the shared mod ([Mod]), which runs Allay from the core for
 * each server the game starts. What NeoForge has no event for, `modcore`'s mixins catch on both loaders; where its
 * events come at another moment than Fabric's, the listeners below wait for the same one.
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
    // After the break, as Fabric's AFTER: the event comes before the game removes the block, which may still refuse,
    // so the hook waits a tick and fires only if the block is gone. A break someone cancelled never happened.
    bus.addListener(EventPriority.LOWEST) { event: BreakBlockEvent ->
      val player = event.player
      val level = event.level
      if (event.isCanceled || player !is ServerPlayer || level !is ServerLevel) return@addListener
      val pos = event.pos.immutable()
      val before = event.state
      Mod.platform?.scheduler?.later(1) { if (level.getBlockState(pos) != before) Hooks.brokeBlock.each { it(player, level, pos) } }
    }
    // A claims mod may take a placed block back by cancelling this, so only what stays counts.
    bus.addListener(EventPriority.LOWEST) { event: BlockEvent.EntityPlaceEvent ->
      val player = event.entity
      val level = event.level
      if (!event.isCanceled && player is ServerPlayer && level is ServerLevel) Hooks.placed(player, level, event.pos)
    }
    // The death itself once nobody cancelled it, with the inventory still on; modcore's mixin counts the drops after.
    bus.addListener(EventPriority.LOWEST) { event: LivingDeathEvent ->
      val player = event.entity
      if (!event.isCanceled && player is ServerPlayer) Hooks.dying(player, event.source)
    }
    bus.addListener { event: PlayerEvent.PlayerRespawnEvent -> (event.entity as? ServerPlayer)?.let { player -> Hooks.arrived.each { it(player) } } }
    bus.addListener { event: PlayerEvent.PlayerChangedDimensionEvent -> (event.entity as? ServerPlayer)?.let { player -> Hooks.arrived.each { it(player) } } }
    bus.addListener { event: PlayerInteractEvent.LeftClickBlock ->
      val player = event.entity
      val level = event.level
      if (event.action == PlayerInteractEvent.LeftClickBlock.Action.START && player is ServerPlayer && level is ServerLevel && Hooks.hit(player, level, event.pos)) {
        event.isCanceled = true
        // The client already started breaking it: give it the block back, as Fabric does when a callback takes the click.
        player.connection.send(ClientboundBlockUpdatePacket(level, event.pos))
        level.getBlockEntity(event.pos)?.updatePacket?.let(player.connection::send)
      }
    }
  }
}
