package chat.allay.fabric

import net.minecraft.advancements.AdvancementHolder
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.ItemStack
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The game's events as Allay's pieces want them, in one place, as Bukkit's listeners had them: Fabric API's events
 * where they exist, the mod's mixins where they don't. A piece adds itself once at start; [clear] drops everyone when
 * the server stops, since a singleplayer game may start another. Everything runs on the server thread.
 */
object Hooks {
  class Event<T> {
    private val listeners = CopyOnWriteArrayList<T>()
    operator fun plusAssign(listener: T) {
      listeners += listener
    }

    fun each(call: (T) -> Unit) = listeners.forEach {
      try {
        call(it)
      } catch (error: Throwable) {
        LoggerFactory.getLogger("Allay").error("A listener of Allay failed", error)
      }
    }

    fun clear() = listeners.clear()
  }

  /** A player joined and plays; [left] when they go, before the game forgets them. */
  val joined = Event<(ServerPlayer) -> Unit>()
  val left = Event<(ServerPlayer) -> Unit>()

  /** A player placed or broke a block. */
  val placedBlock = Event<(ServerPlayer, ServerLevel, BlockPos) -> Unit>()
  val brokeBlock = Event<(ServerPlayer, ServerLevel, BlockPos) -> Unit>()

  /** A player hit a block with a left click; true from a listener means it took the click, and the block takes no damage. */
  val hitBlock = Event<(ServerPlayer, ServerLevel, BlockPos) -> Boolean>()

  /**
   * A player's client starts a new world: they respawned, as a new [ServerPlayer], or went to another dimension. The
   * client forgets every entity it knew then, the ones only it knew about ([chat.allay.fabric.tools.Ghosts]) too.
   */
  val arrived = Event<(ServerPlayer) -> Unit>()

  /** A player dies, with what they carry still on them. */
  val died = Event<(ServerPlayer, DamageSource) -> Unit>()

  /** A player picked up [count] of the stack an item entity held. */
  val pickup = Event<(ServerPlayer, ItemEntity, ItemStack, Int) -> Unit>()

  /** A player met a criterion of an advancement. */
  val advancement = Event<(ServerPlayer, AdvancementHolder) -> Unit>()

  /** The end of every server tick. */
  val tick = Event<() -> Unit>()

  fun placed(player: ServerPlayer, level: ServerLevel, pos: BlockPos) = placedBlock.each { it(player, level, pos) }
  fun dying(player: ServerPlayer, source: DamageSource) = died.each { it(player, source) }
  fun pickedUp(player: ServerPlayer, item: ItemEntity, stack: ItemStack, count: Int) = pickup.each { it(player, item, stack, count) }
  fun advanced(player: ServerPlayer, holder: AdvancementHolder) = advancement.each { it(player, holder) }

  fun hit(player: ServerPlayer, level: ServerLevel, pos: BlockPos): Boolean {
    var taken = false
    hitBlock.each { if (it(player, level, pos)) taken = true }
    return taken
  }

  fun clear() = listOf(joined, left, placedBlock, brokeBlock, hitBlock, arrived, died, pickup, advancement, tick).forEach { it.clear() }
}
