package chat.allay.fabric

import chat.allay.core.*
import chat.allay.core.tools.Dimensions
import chat.allay.fabric.tools.Worlds
import chat.allay.fabric.tools.id

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.commands.CommandSourceStack
import net.minecraft.core.Holder
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.SimpleParticleType
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.ChatType
import net.minecraft.network.chat.PlayerChatMessage
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.permissions.Permissions
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.world.level.storage.LevelResource
import org.slf4j.LoggerFactory
import java.io.File
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/** A command's sender as the core sees it: the console, a command block, or a player through [FabricPlayer]. */
open class FabricSender(open val source: CommandSourceStack) : Audience {
  override val name: String get() = source.textName
  override val uuid: UUID? get() = null
  override val operator: Boolean get() = source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)
  override fun can(permission: String) = Perms.check(source, permission)
  override fun send(text: String) = source.sendSystemMessage(Texts.legacy(text))
  override fun send(line: Line) {
    if (!line.empty) source.sendSystemMessage(Texts.line(line))
  }

  override fun equals(other: Any?) = other is FabricSender && other !is FabricPlayer && other.source.textName == source.textName
  override fun hashCode() = source.textName.hashCode()
}

/**
 * A player as the core sees them. The game makes a new [ServerPlayer] on every respawn, so this follows the player by
 * their id and finds the one that plays now, falling back to the last one seen once they left.
 */
class FabricPlayer(private val last: ServerPlayer) : FabricSender(last.createCommandSourceStack()), PlayerAudience {
  override val uuid: UUID = last.uuid

  /** The player as the game has them now. */
  val player: ServerPlayer get() = last.level().server.playerList.getPlayer(uuid) ?: last

  override val source: CommandSourceStack get() = player.createCommandSourceStack()
  override val name: String get() = player.gameProfile.name
  override val operator: Boolean get() = player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)
  override fun can(permission: String) = Perms.check(player, permission)
  override fun send(text: String) = player.sendSystemMessage(Texts.legacy(text))
  override fun send(line: Line) {
    if (!line.empty) player.sendSystemMessage(Texts.line(line))
  }

  override val online: Boolean get() = last.level().server.playerList.getPlayer(uuid)?.hasDisconnected() == false
  override val locale: String get() = player.clientInformation().language()
  override val address get() = (player.connection?.remoteAddress as? InetSocketAddress)?.address
  override val position: Protocol.Position get() = player.let { Protocol.Position(it.blockX, it.blockY, it.blockZ, Dimensions.id(it.level()), Worlds.name(it.level())) }
  override fun actionBar(text: String) = player.sendSystemMessage(Texts.legacy(text), true)
  override fun show(window: Window) = Windows.show(player, window)
  override fun closeWindow() = Windows.close(player)
  override fun chat(text: String) {
    val player = player
    player.level().server.playerList.broadcastChatMessage(PlayerChatMessage.unsigned(uuid, text), player, ChatType.bind(ChatType.CHAT, player))
  }

  override fun equals(other: Any?) = other is FabricPlayer && other.uuid == uuid
  override fun hashCode() = uuid.hashCode()
}

/** Whoever sent a command, for the core. */
val CommandSourceStack.audience: Audience get() = player?.let(::FabricPlayer) ?: FabricSender(this)
val ServerPlayer.audience: FabricPlayer get() = FabricPlayer(this)

/** The game's player behind an audience the core hands back. */
val PlayerAudience.player: ServerPlayer get() = (this as FabricPlayer).player

/** The core's languages for the game's players. */
fun Locales.of(player: ServerPlayer): Lang = of(player.audience)

/**
 * The server thread's clock for the core: work runs at the end of a server tick, so it never races the game. Work
 * off the server thread goes to a thread of its own.
 */
class FabricScheduler(private val server: MinecraftServer, private val enabled: () -> Boolean) : Scheduler {
  private class Task(@Volatile var due: Long, val period: Long, val block: () -> Unit) : AutoCloseable {
    @Volatile var cancelled = false
    override fun close() {
      cancelled = true
    }
  }

  private val tasks = CopyOnWriteArrayList<Task>()
  @Volatile private var tick = 0L
  private val async = Executors.newSingleThreadScheduledExecutor { Thread(it, "Allay timer").apply { isDaemon = true } }
  private val log = LoggerFactory.getLogger("Allay")

  /** Runs what's due; the mod calls it at the end of every server tick. */
  fun tick() {
    tick++
    for (task in tasks) {
      if (task.cancelled) {
        tasks.remove(task)
        continue
      }
      if (task.due > tick) continue
      if (task.period > 0) task.due = tick + task.period else tasks.remove(task)
      try {
        task.block()
      } catch (error: Throwable) {
        log.error("A task of Allay failed", error)
      }
    }
  }

  fun stop() {
    tasks.clear()
    async.shutdownNow()
  }

  override fun main(block: () -> Unit) {
    if (enabled()) server.execute { if (enabled()) block() }
  }

  override fun later(ticks: Long, block: () -> Unit): AutoCloseable = Task(tick + ticks.coerceAtLeast(1), 0, block).also(tasks::add)

  override fun every(delay: Long, period: Long, async: Boolean, block: () -> Unit): AutoCloseable {
    if (!async) return Task(tick + delay.coerceAtLeast(1), period.coerceAtLeast(1), block).also(tasks::add)
    val future = this.async.scheduleAtFixedRate({
      try {
        block()
      } catch (error: Throwable) {
        log.error("A task of Allay failed", error)
      }
    }, delay * 50, period.coerceAtLeast(1) * 50, TimeUnit.MILLISECONDS)
    return AutoCloseable { future.cancel(false) }
  }

  override fun <T> call(seconds: Long, block: () -> T): T =
    if (onMain) block() else java.util.concurrent.CompletableFuture.supplyAsync({ block() }, server).get(seconds, TimeUnit.SECONDS)

  override val onMain: Boolean get() = server.isSameThread
}

/**
 * Where Allay keeps its files. `config/allay/` holds the settings, texts, license, and country database for every
 * world; what belongs to a world's players sits beside them on a dedicated server, and in the world's save in
 * singleplayer, so it travels with the world.
 */
object Folders {
  val shared: File get() = FabricLoader.getInstance().configDir.resolve("allay").toFile()

  fun of(server: MinecraftServer): File =
    if (server.isDedicatedServer) shared
    else server.getWorldPath(LevelResource.ROOT).resolve("allay").normalize().toFile()
}

/** Fabric as Allay's platform, for one server: a dedicated one, or the one a singleplayer world runs on. */
class FabricPlatform(val server: MinecraftServer) : Platform {
  @Volatile var on = false
  override val log: Logger = julLogger()
  override val version: String = FabricLoader.getInstance().getModContainer("allay").map { it.metadata.version.friendlyString }.orElse("dev")
  override val folder: File = Folders.of(server)
  override val shared: File = Folders.shared
  override fun resource(path: String) = FabricPlatform::class.java.classLoader.getResourceAsStream(path)
  override val scheduler = FabricScheduler(server) { on }
  override val enabled get() = on
  override val players: Collection<PlayerAudience> get() = server.playerList.players.map(::FabricPlayer)
  override val console: Audience get() = FabricSender(server.createCommandSourceStack())
  override fun player(id: UUID) = server.playerList.getPlayer(id)?.let(::FabricPlayer)
  override fun player(name: String) = server.playerList.getPlayerByName(name)?.let(::FabricPlayer)

  /** Players with a save in this world: the game keeps their file under `playerdata/`, and its cache knows their name. */
  override fun known(who: String): Pair<UUID, String?>? {
    val cache = server.services().nameToIdCache()
    val saved = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).toFile().listFiles().orEmpty()
      .mapNotNull { runCatching { UUID.fromString(it.name.removeSuffix(".dat")) }.getOrNull() }.toSet()
    runCatching { UUID.fromString(who) }.getOrNull()?.let { id ->
      return (id to cache.get(id).map { it.name() }.orElse(null)).takeIf { id in saved || server.playerList.getPlayer(id) != null }
    }
    server.playerList.getPlayerByName(who)?.let { return it.uuid to it.gameProfile.name }
    return saved.firstNotNullOfOrNull { id -> cache.get(id).orElse(null)?.takeIf { it.name().equals(who, ignoreCase = true) } }?.let { it.id() to it.name() }
  }

  override fun dispatch(who: Audience, command: String): Boolean {
    val source = (who as FabricSender).source
    val parsed = server.commands.dispatcher.parse(command, source)
    if (parsed.reader.canRead() || parsed.context.command == null) return false
    server.commands.performCommand(parsed, command)
    return true
  }

  override fun vanished(player: PlayerAudience) = Vanish.vanished(player.player)
  override fun placeholders(about: Audience?) = Placeholders.about(this, (about as? FabricPlayer)?.player)
  override val motd: String get() = server.motd.orEmpty()
  override fun asker(who: Audience, language: String?): Asker = SourceAsker(who, language)

  /** A java.util.logging logger, as the core logs, that writes to the game's log. */
  private fun julLogger(): Logger {
    val target = LoggerFactory.getLogger("Allay")
    return Logger.getAnonymousLogger().apply {
      useParentHandlers = false
      level = Level.ALL
      addHandler(object : Handler() {
        override fun publish(record: LogRecord) {
          val message = record.message
          when {
            record.level.intValue() >= Level.SEVERE.intValue() -> target.error(message, record.thrown)
            record.level.intValue() >= Level.WARNING.intValue() -> target.warn(message, record.thrown)
            record.level.intValue() >= Level.INFO.intValue() -> target.info(message, record.thrown)
            else -> target.debug(message, record.thrown)
          }
        }

        override fun flush() {}
        override fun close() {}
      })
    }
  }
}

/** The display's sounds and particles as the game's, from `config.yml`; an unknown name falls back to the default with a warning. */
class FabricEffects(private val log: Logger, config: Config) : Effects {
  private val ui = config.ui
  private val particle: ParticleOptions = particle(ui.particleType)
  private val sounds = mapOf(
    Cue.ANSWER to sound(ui.successSound, holder(SoundEvents.ALLAY_ITEM_GIVEN)),
    Cue.ERROR to sound(ui.errorSound, holder(SoundEvents.ALLAY_AMBIENT_WITHOUT_ITEM)),
    Cue.THINKING to sound(ui.thinkingSound, holder(SoundEvents.ALLAY_AMBIENT_WITH_ITEM)),
    Cue.REMINDER to SoundEvents.NOTE_BLOCK_BELL,
  )

  private fun holder(sound: SoundEvent): Holder<SoundEvent> = BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound)

  private fun sound(setting: Config.Sound, default: Holder<SoundEvent>): Holder<SoundEvent> {
    val name = setting.name ?: return default
    return Names.find(BuiltInRegistries.SOUND_EVENT, name)?.let { BuiltInRegistries.SOUND_EVENT.wrapAsHolder(it) } ?: run {
      log.warning("${setting.key}: there is no such value as $name; using the default")
      default
    }
  }

  private fun particle(name: String?): ParticleOptions {
    name ?: return ParticleTypes.ENCHANT
    // Only particles without settings of their own can be named in config.yml.
    return Names.find(BuiltInRegistries.PARTICLE_TYPE, name) as? SimpleParticleType ?: run {
      log.warning("ui.particles.type: there is no such value as $name; using the default")
      ParticleTypes.ENCHANT
    }
  }

  override fun play(player: PlayerAudience, cue: Cue, volume: Float, pitch: Float) {
    val game = player.player
    game.connection.send(ClientboundSoundPacket(sounds.getValue(cue), SoundSource.MASTER, game.x, game.y, game.z, volume, pitch, game.random.nextLong()))
  }

  override fun particles(player: PlayerAudience) {
    val game = player.player
    game.level().sendParticles(particle, game.x, game.y + ui.particleHeightOffset, game.z, ui.particleCount, 0.3, 0.3, 0.3, 0.01)
  }
}

/** Names from `config.yml`: Bukkit's enum names (`ENTITY_ALLAY_ITEM_GIVEN`) and the game's ids (`entity.allay.item_given`) alike. */
object Names {
  fun <T : Any> find(registry: net.minecraft.core.Registry<T>, name: String): T? {
    Identifier.tryParse(name.lowercase())?.let { id -> registry.getOptional(id).orElse(null)?.let { return it } }
    val wanted = name.uppercase()
    return registry.entrySet().firstOrNull { (key, _) -> key.identifier().namespace == "minecraft" && bukkit(key.identifier().path) == wanted }?.value
  }

  /** How Bukkit names a sound or particle: `entity.allay.item_given` is `ENTITY_ALLAY_ITEM_GIVEN`. */
  fun bukkit(path: String) = path.replace('.', '_').replace('/', '_').uppercase()
}
