package chat.allay.spigot.tools

import chat.allay.core.tools.CommandCatalog
import chat.allay.core.tools.CommandCatalog.Check
import chat.allay.core.tools.CommandCatalog.Verdict
import chat.allay.core.tools.ToolOutput

import kotlinx.serialization.json.JsonObject
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.PluginIdentifiableCommand
import org.bukkit.command.SimpleCommandMap
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * The server's commands on Bukkit (`search_commands`), from its command map: plugins' commands with their owner from
 * [PluginIdentifiableCommand], the game's and the server's own by the namespace they're registered under
 * (`minecraft:tp`, `bukkit:version`), and on Paper the commands plugins register through Brigadier, which Paper wraps
 * as commands of their plugin. Built on the server thread and kept until commands change: a plugin comes or goes, the
 * server loads or Paper reloads, the map's size moves, and 5 minutes at most, since a plugin may register one whenever.
 */
object CommandTools {
  private const val MAX_AGE_MILLIS = 5 * 60_000L
  private val cache = CommandCatalog.Cache<Command>(MAX_AGE_MILLIS)

  /** What the game's own commands say about themselves on Bukkit: nothing. */
  private const val MOJANG = "A Mojang provided command."

  fun clear() = cache.clear()

  /** The server's command map: Paper's API names it, Spigot's server has the same method without the API. */
  private val map: SimpleCommandMap? get() = runCatching { Bukkit.getServer().javaClass.getMethod("getCommandMap").invoke(Bukkit.getServer()) as SimpleCommandMap }.getOrNull()

  /** Every name the map knows with its command; Paper builds these from its Brigadier tree on each read. */
  @Suppress("UNCHECKED_CAST")
  private fun known(map: SimpleCommandMap): Map<String, Command> = runCatching { map.javaClass.getMethod("getKnownCommands").invoke(map) as Map<String, Command> }.getOrElse {
    SimpleCommandMap::class.java.getDeclaredField("knownCommands").also { it.isAccessible = true }.get(map) as Map<String, Command>
  }

  /**
   * A game command's Brigadier node: CraftBukkit's `VanillaCommandWrapper` holds it, and so do Paper's wrappers of
   * plugins' Brigadier commands. Paper hands the game's own nodes out as a `ShadowBrigNode`, whose requirement says no
   * to everyone; the game's node is its handle.
   */
  private fun node(command: Command): Any? {
    var type: Class<*>? = command.javaClass
    while (type != null && type != Command::class.java) {
      if (type.simpleName == "VanillaCommandWrapper") {
        wrapper = type
        return runCatching { field(type).get(command) }.getOrNull()?.let(::handle)?.let(::target)
      }
      type = type.superclass
    }
    return null
  }

  private fun handle(node: Any): Any =
    if (node.javaClass.simpleName == "ShadowBrigNode") runCatching { node.javaClass.getMethod("getHandle").invoke(node) }.getOrNull() ?: node else node

  /**
   * What makes two names one command: the same object, or for a Brigadier node the same name without its namespace and
   * the same code. Paper copies a node for `minecraft:give` and wraps each anew, and gives every game command the same
   * code under its wrapper, so neither the node nor the code alone tells.
   */
  private class Same(val name: String, val ref: Any) {
    override fun equals(other: Any?) = other is Same && other.name == name && other.ref === ref
    override fun hashCode() = name.hashCode() * 31 + System.identityHashCode(ref)
  }

  private fun same(command: Command): Same {
    val node = node(command) ?: return Same("", command)
    fun call(name: String) = runCatching { node.javaClass.getMethod(name).invoke(node) }.getOrNull()
    val code = call("getCommand") ?: (call("getChildren") as? Collection<*>)?.firstOrNull() ?: node
    return Same((call("getName") as? String ?: command.name).lowercase().substringAfter(':'), code)
  }

  private val fields = HashMap<Class<*>, Field>()
  private fun field(type: Class<*>): Field = fields.getOrPut(type) { type.getDeclaredField("vanillaCommand").also { it.isAccessible = true } }

  /** Where a node that only redirects (`tp` to `teleport`) leads; never the root, which `execute run` leads to. */
  private fun target(node: Any): Any {
    var at = node
    repeat(8) {
      val next = runCatching { at.javaClass.getMethod("getRedirect").invoke(at) }.getOrNull() ?: return at
      if (next.javaClass.simpleName == "RootCommandNode") return at
      at = next
    }
    return at
  }

  /** CraftBukkit's `VanillaCommandWrapper`, found on the first game command: Spigot keeps it under a versioned package. */
  @Volatile private var wrapper: Class<*>? = null
  private var getListener: Method? = null
  private var canUse: Method? = null

  /** The game's source of a sender, which a node's requirement reads: `VanillaCommandWrapper.getListener`. */
  private fun listener(sender: CommandSender): Any? = runCatching {
    val type = wrapper ?: return null
    (getListener ?: type.getMethod("getListener", CommandSender::class.java).also { getListener = it }).invoke(null, sender)
  }.getOrNull()

  /**
   * Whether [sender] may run [command]: its permission, and for a Brigadier command (the game's, a Paper plugin's)
   * its requirement too, as the game asks before it sends a player the command tree. A plugin's Brigadier command has
   * no permission of its own on Bukkit, so the requirement is all there is.
   */
  fun mayRun(sender: CommandSender, command: Command): Boolean {
    if (!command.testPermissionSilent(sender)) return false
    val node = node(command) ?: return true
    return runCatching {
      val source = listener(sender) ?: return true
      (canUse ?: node.javaClass.getMethod("canUse", Any::class.java).also { canUse = it }).invoke(node, source) as Boolean
    }.getOrDefault(true)
  }

  fun catalog(): CommandCatalog<Command> {
    val map = map ?: return CommandCatalog(emptyList())
    val known = known(map)
    return cache.get(known.size) { build(known) }
  }

  /**
   * One entry per command: Paper hands out a new wrapper for a Brigadier node on each read, so commands group by
   * [same]. Its name is the one typed without a namespace, unless another plugin
   * took it; the namespace says whose it is where the command doesn't.
   */
  private fun build(known: Map<String, Command>): CommandCatalog<Command> {
    val groups = LinkedHashMap<Any, MutableList<Pair<String, Command>>>()
    for ((label, command) in known) groups.getOrPut(same(command)) { mutableListOf() } += label.lowercase() to command
    val entries = groups.values.mapNotNull { labels ->
      val command = labels.first().second
      val plain = labels.map { it.first }.filter { ':' !in it }.distinct()
      val namespaced = labels.map { it.first }.filter { ':' in it }
      val own = command.name.lowercase()
      val name = own.takeIf { it in plain } ?: plain.firstOrNull() ?: namespaced.firstOrNull() ?: return@mapNotNull null
      val source = (command as? PluginIdentifiableCommand)?.plugin?.name ?: namespaced.firstOrNull()?.substringBefore(':')
      val description = command.description?.takeUnless { it == MOJANG }?.let(CommandCatalog.Companion::text)
      // A Brigadier command's usage comes per asker ([usages]); Paper's wrapper only repeats a name.
      val usage = if (node(command) != null) null else command.usage?.replace("<command>", name)?.let(CommandCatalog.Companion::text)?.takeUnless { it == "/$name" || it == name }
      CommandCatalog.Entry(name, (plain - name).sorted(), source, description, usage, command)
    }
    return CommandCatalog(merged(entries))
  }

  /**
   * Paper registers each alias of a plugin's Brigadier command (`pbrig` for `probebrig`) as a node of its own; the
   * command still names its aliases, so they join it as on Bukkit.
   */
  private fun merged(entries: List<CommandCatalog.Entry<Command>>): List<CommandCatalog.Entry<Command>> {
    val byName = entries.associateBy { it.name }
    val folded = HashMap<CommandCatalog.Entry<Command>, MutableList<String>>()
    val gone = HashSet<CommandCatalog.Entry<Command>>()
    for (entry in entries) {
      if (entry in gone || node(entry.handle) == null) continue
      for (alias in entry.handle.aliases.orEmpty().map(String::lowercase)) {
        val other = byName[alias]?.takeIf { it !== entry && it !in gone && it.source == entry.source && node(it.handle) != null } ?: continue
        gone += other
        folded.getOrPut(entry) { mutableListOf() } += listOf(other.name) + other.aliases
      }
    }
    return entries.filter { it !in gone }.map { entry ->
      val more = folded[entry] ?: return@map entry
      CommandCatalog.Entry(entry.name, (entry.aliases + more).distinct().sorted(), entry.source, entry.description, entry.usage, entry.handle)
    }
  }

  /** Whether [line] would run for [sender] now: a command the map knows, and theirs to run. Bukkit parses arguments only as it runs. */
  fun check(sender: CommandSender, line: String): Check {
    val label = CommandCatalog.label(line)
    val command = map?.getCommand(label) ?: return Check(Verdict.UNKNOWN)
    val source = (command as? PluginIdentifiableCommand)?.plugin?.name ?: label.substringBefore(':', "").ifEmpty { null }
      ?: catalog().entries.firstOrNull { it.handle === command || it.name == label || label in it.aliases }?.source
    if (!mayRun(sender, command)) return Check(Verdict.FORBIDDEN, source)
    val usage = node(command)?.let { node -> usages(sender, listOf(node))[node]?.let { "/$label" + it.substringAfter(' ', "").let { rest -> if (rest.isEmpty()) "" else " $rest" } } } ?: command.usage?.replace("<command>", label)?.let(CommandCatalog.Companion::text)?.takeUnless { it == "/$label" }
    return Check(Verdict.OK, source, usage)
  }

  /**
   * Brigadier commands' usage as [sender] may use them, as the game's `/help` shows it: only the branches they can run.
   * Worked out on a dispatcher of its own, with one node per name, or adding them would merge them into the real tree.
   */
  @Suppress("UNCHECKED_CAST")
  private fun usages(sender: CommandSender, nodes: List<Any>): Map<Any, String> = runCatching {
    val shown = nodes.distinctBy { it.javaClass.getMethod("getName").invoke(it) }
    if (shown.isEmpty()) return emptyMap()
    val loader = shown.first().javaClass.classLoader
    val dispatcherType = Class.forName("com.mojang.brigadier.CommandDispatcher", false, loader)
    val nodeType = Class.forName("com.mojang.brigadier.tree.CommandNode", false, loader)
    val dispatcher = dispatcherType.getConstructor().newInstance()
    val root = dispatcherType.getMethod("getRoot").invoke(dispatcher)
    val add = nodeType.getMethod("addChild", nodeType)
    shown.forEach { add.invoke(root, it) }
    val source = listener(sender) ?: return emptyMap()
    val smart = dispatcherType.getMethod("getSmartUsage", nodeType, Any::class.java).invoke(dispatcher, root, source) as Map<Any, String>
    shown.mapNotNull { node -> smart[node]?.let { node to "/$it" } }.toMap()
  }.getOrDefault(emptyMap())

  private val search = object : Tool {
    override val name = "search_commands"

    override fun run(context: ToolContext, args: JsonObject): ToolOutput {
      val config = context.features.config()
      val sender = context.sender
      return catalog().run(context.lang, args, config.tools.hiddenPlugins, config.blockedCommands, { mayRun(sender, it) }, { check(sender, it) }) { entries ->
        val nodes = entries.mapNotNull { entry -> node(entry.handle)?.let { entry to it } }
        val usage = usages(sender, nodes.map { it.second })
        // The usage starts with the node's own name, which may be namespaced (`allayprobe:probebrig`); the player types the entry's.
        nodes.mapNotNull { (entry, node) -> usage[node]?.let { entry to "/${entry.name}" + it.substringAfter(' ', "").let { rest -> if (rest.isEmpty()) "" else " $rest" } } }.toMap()
      }
    }
  }

  val all: List<Tool> = listOf(search)
}
