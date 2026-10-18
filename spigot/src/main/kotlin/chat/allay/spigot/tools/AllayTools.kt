package chat.allay.spigot.tools

import chat.allay.core.*
import chat.allay.core.tools.*

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.bukkit.plugin.java.JavaPlugin
import chat.allay.core.Settings

/** Allay about itself: what this player can use here, and their settings. Texts are English, for the model. */
object AllayTools {
  /** What the server has switched on, by the tool behind each feature. */
  private val FEATURES = mapOf(
    "notes" to "add_note", "goals" to "goals", "reminders" to "reminders", "books" to "write_book", "buttons" to "add_buttons",
    "compass" to "point_to", "marks" to "show_marks", "drafts" to "show_draft", "redstone" to "inspect_redstone", "sorting" to "sort_inventory", "ping" to "ping_me", "timezone" to "set_timezone",
  )

  private val guide = object : Tool {
    override val name = "get_allay_guide"

    override fun run(context: ToolContext, args: JsonObject): ToolOutput {
      val sender = context.sender
      val config = context.features.config()
      val commands = Guide.commands(sender::hasPermission)
      val result = buildJsonObject {
        put("plugin_version", JavaPlugin.getProvidingPlugin(AllayTools::class.java).description.version)
        put("commands", buildJsonArray {
          commands.forEach { (usage, does) -> add(buildJsonObject { put("usage", usage); put("does", does) }) }
        })
        val prefixes = Guide.prefixes(sender::hasPermission)
        if (prefixes.isNotEmpty()) putJsonObject("chat_mode") {
          putJsonObject("prefixes") { prefixes.forEach { (prefix, does) -> put(prefix, does) } }
          put("note", "spaces after a prefix don't matter; commands starting with / work as usual")
        }
        context.player?.let { player ->
          val settings = context.features.settings.of(player.uniqueId)
          putJsonObject("settings") {
            put("chat_mode", settings.chat_mode)
            put("hints", settings.hints)
            put("timezone", context.features.zones.of(player.uniqueId))
          }
        }
        put("features", buildJsonArray {
          FEATURES.filterValues(config.tools::enabled).keys.forEach { add(it) }
          add("windows")
          if (sender.isOp && OperatorTools.NAMES.any(config.tools::enabled)) add("operator_tools")
          // Automation the player may start here: their permissions and the server's switches together.
          (sender as? org.bukkit.entity.Player)?.let { player ->
            chat.allay.core.tasks.Kind.entries.filter { context.features.automation.allowed(player, it) == null && config.tools.enabled("start_task") }
              .forEach { add("automation_${it.wire}") }
          }
        })
        putJsonObject("limits") {
          put("notes", config.tools.maxNotes)
          put("reminders", config.tools.maxReminders)
          put("goals", config.tools.maxGoals)
          put("askall_cooldown_seconds", config.askall.cooldownSeconds)
          put("automation_agents_at_once", config.automation.maxTasks)
          put("automation_radius", config.automation.radius)
        }
      }
      return ToolOutput(result, context.lang.text("summary.guide", "count" to commands.size))
    }
  }

  private val settings = object : Tool {
    override val name = "allay_settings"

    override fun run(context: ToolContext, args: JsonObject): ToolOutput {
      val player = context.requirePlayer()
      val chatMode = args.bool("chat_mode")
      val hints = args.bool("hints")
      if (chatMode == null && hints == null) throw ToolError("pass chat_mode or hints")
      if (!player.hasPermission(Permissions.CHAT)) throw ToolError("chat mode is not allowed for this player on this server")
      // The answer confirms the change, so the plugin only speaks up to explain chat mode's prefixes.
      var now: Settings = context.features.settings.of(player.uniqueId)
      chatMode?.let { now = context.features.chat.set(player, it, announce = false) }
      hints?.let { now = context.features.chat.hints(player, it, announce = false) }
      val result = buildJsonObject {
        put("chat_mode", now.chat_mode)
        put("hints", now.hints)
      }
      val lang = context.lang
      val word = { on: Boolean -> lang.text(if (on) "summary.switched_on" else "summary.switched_off") }
      return ToolOutput(result, lang.text("summary.settings", "chat" to word(now.chat_mode), "hints" to word(now.hints)))
    }
  }

  val all = listOf(guide, settings)
}
