package chat.allay.modcore.tools

import chat.allay.core.tools.CommandCatalog
import chat.allay.core.tools.CommandCatalog.Check
import chat.allay.core.tools.CommandCatalog.Verdict
import chat.allay.core.tools.ToolOutput
import chat.allay.modcore.Mod
import chat.allay.modcore.ModSender

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.tree.CommandNode
import com.mojang.brigadier.tree.RootCommandNode
import kotlinx.serialization.json.JsonObject
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.server.MinecraftServer

/**
 * The server's commands in the mods (`search_commands`), from the root of its Brigadier tree. Brigadier keeps no
 * owner, so a command's comes from a namespace it's also registered under (`mymod:home`), else from the class its code
 * lives in, which the loader maps to a mod ([chat.allay.modcore.Loader.owner]); the game's own are `minecraft`. A node
 * that only redirects (`tp` to `teleport`) is the other's alias. Kept until the tree changes: a `/reload` builds a new
 * one, and a mod may add a root node any time, which moves the count.
 */
object CommandTools {
  private val cache = CommandCatalog.Cache<CommandNode<CommandSourceStack>>()

  /** How deep to look for a node's code: `home` runs nothing itself, `home <name>` does. */
  private const val DEPTH = 4

  fun catalog(server: MinecraftServer): CommandCatalog<CommandNode<CommandSourceStack>> {
    val dispatcher = server.commands.dispatcher
    return cache.get(listOf(dispatcher, dispatcher.root.children.size)) { build(dispatcher) }
  }

  /** Where a node that only redirects leads; never the root, which `execute run` leads to. */
  private fun target(node: CommandNode<CommandSourceStack>): CommandNode<CommandSourceStack> {
    var at = node
    repeat(8) {
      val next = at.redirect ?: return at
      if (next is RootCommandNode || at.command != null || at.children.isNotEmpty()) return at
      at = next
    }
    return at
  }

  /** The first code under [node], whose class says whose command it is. */
  private fun code(node: CommandNode<CommandSourceStack>, depth: Int = 0): Class<*>? {
    node.command?.let { return it.javaClass }
    if (depth >= DEPTH) return null
    return node.children.firstNotNullOfOrNull { code(it, depth + 1) }
  }

  private fun owner(node: CommandNode<CommandSourceStack>): String? {
    val type = code(node) ?: return null
    if (type.name.startsWith("net.minecraft.")) return CommandCatalog.MINECRAFT
    return runCatching { Mod.loader.owner(type) }.getOrNull()
  }

  private fun build(dispatcher: CommandDispatcher<CommandSourceStack>): CommandCatalog<CommandNode<CommandSourceStack>> {
    val groups = LinkedHashMap<CommandNode<CommandSourceStack>, MutableList<String>>()
    for (child in dispatcher.root.children) groups.getOrPut(target(child)) { mutableListOf() } += child.name.lowercase()
    val entries = groups.map { (node, labels) ->
      val plain = labels.filter { ':' !in it }
      val namespaced = labels.filter { ':' in it }
      val own = node.name.lowercase()
      val name = own.takeIf { it in plain } ?: plain.firstOrNull() ?: own
      val source = namespaced.firstOrNull()?.substringBefore(':') ?: owner(node)
      CommandCatalog.Entry(name, (plain - name).sorted(), source, null, null, node)
    }
    return CommandCatalog(entries)
  }

  /** Each command's usage as [source] may use it, as `/help` shows it: only the branches they can run. */
  private fun usage(dispatcher: CommandDispatcher<CommandSourceStack>, source: CommandSourceStack, entries: List<CommandCatalog.Entry<CommandNode<CommandSourceStack>>>): Map<CommandCatalog.Entry<CommandNode<CommandSourceStack>>, String> {
    // A root of its own, so the usage covers only these; one node per name, or adding would merge them into the real tree.
    val root = RootCommandNode<CommandSourceStack>()
    val shown = entries.distinctBy { it.handle.name }
    shown.forEach { root.addChild(it.handle) }
    val usages = runCatching { dispatcher.getSmartUsage(root, source) }.getOrDefault(emptyMap())
    return shown.mapNotNull { entry -> usages[entry.handle]?.let { entry to "/$it" } }.toMap()
  }

  /**
   * Whether [line] would run for [source] now: a command in the tree, one they may use, and arguments the game parses,
   * as it parses them before it runs anything. A parse runs nothing.
   */
  fun check(server: MinecraftServer, source: CommandSourceStack, line: String): Check {
    val dispatcher = server.commands.dispatcher
    val label = CommandCatalog.label(line)
    val node = dispatcher.root.getChild(label) ?: return Check(Verdict.UNKNOWN)
    val owner = if (':' in label) label.substringBefore(':') else catalog(server).entries.firstOrNull { it.name == label || label in it.aliases }?.source
    if (!node.canUse(source)) return Check(Verdict.FORBIDDEN, owner)
    val usage = runCatching { dispatcher.getSmartUsage(RootCommandNode<CommandSourceStack>().also { it.addChild(node) }, source)[node] }.getOrNull()?.let { "/$it" }
    val parse = dispatcher.parse(line.trim().removePrefix("/"), source)
    Commands.getParseException(parse)?.let { return Check(Verdict.SYNTAX, owner, usage, it.message) }
    if (parse.context.lastChild.command == null) return Check(Verdict.SYNTAX, owner, usage, "the command is incomplete")
    return Check(Verdict.OK, owner, usage)
  }

  private val search = object : Tool {
    override val name = "search_commands"

    override fun run(context: ToolContext, args: JsonObject): ToolOutput {
      val config = context.features.config()
      val source = (context.asker as? ModSender)?.source ?: context.server.createCommandSourceStack()
      // hidden_plugins names mods by id or by name; commands know them by id.
      val hidden = config.tools.hiddenPlugins.let { names -> names + Mod.loader.mods().filter { it.name.lowercase() in names }.map { it.id.lowercase() } }
      val dispatcher = context.server.commands.dispatcher
      return catalog(context.server).run(context.lang, args, hidden, config.blockedCommands, { it.canUse(source) }, { check(context.server, source, it) }) { usage(dispatcher, source, it) }
    }
  }

  val all: List<Tool> = listOf(search)
}
