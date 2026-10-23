package chat.allay.modcore.automation

import chat.allay.core.tools.*
import chat.allay.modcore.tools.Tool
import chat.allay.modcore.tools.ToolContext

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.server.level.ServerPlayer

/** `pick_blocks`: an agent asks its player to pick chests, furnaces, or brewing stands, or takes the one they look at. The rest of the agents' tools are core's [TaskTools]. */
object PickTools {
  private fun tool(name: String, body: (ToolContext, ServerPlayer, JsonObject) -> ToolOutput) = object : Tool {
    override val name = name
    override fun run(context: ToolContext, args: JsonObject) = body(context, context.requirePlayer(), args)
  }

  private val pick = tool("pick_blocks") { context, player, args ->
    val task = context.requireTask()
    val automation = context.features.automation
    val kind = Picking.Kind.parse(args.string("kind"))
    automation.require(player, task, when (kind) {
      Picking.Kind.CHESTS -> chat.allay.core.tasks.Kind.CHESTS
      Picking.Kind.FURNACES -> chat.allay.core.tasks.Kind.FURNACES
      Picking.Kind.BREWING_STANDS -> chat.allay.core.tasks.Kind.BREWING
    })
    if (args.bool("looking") == true) {
      val block = Picking.target(player)?.takeIf { kind.accepts(it.state) } ?: throw ToolError("the player doesn't look at ${kind.wire.removeSuffix("s")} within 6 blocks")
      val root = if (kind == Picking.Kind.CHESTS) Blocks.root(block) else block
      val settings = automation.settings()
      val unfit = if (kind == Picking.Kind.CHESTS) Blocks.unsuitable(player, root) else Blocks.unusable(player, root, settings.radius, settings.lineOfSight)
      unfit?.let { throw ToolError("the ${root.key} at ${root.x} ${root.y} ${root.z} can't be used: ${it.model}") }
      return@tool ToolOutput(buildJsonObject {
        put("x", root.x)
        put("y", root.y)
        put("z", root.z)
        put("type", root.key)
      }, context.lang.text("summary.picked", "count" to 1))
    }
    automation.picking.start(player, task, kind, looking = false)
    automation.agent(task).asked = true
    ToolOutput(buildJsonObject { put("picking", true); put("note", "wait now; you wake with what the player picked") }, context.lang.text("summary.picking"))
  }

  val all = listOf(pick)
}
