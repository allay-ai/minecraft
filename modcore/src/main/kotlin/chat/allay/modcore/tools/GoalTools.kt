package chat.allay.modcore.tools

import chat.allay.core.*
import chat.allay.core.tools.*

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerPlayer

/** Goals with a boss bar: “collect 16 diamonds”, “reach level 30”, “find a cherry grove”. */
object GoalTools {
  private fun tool(name: String, body: (ToolContext, ServerPlayer, JsonObject) -> ToolOutput) = object : Tool {
    override val name = name
    override fun run(context: ToolContext, args: JsonObject) = body(context, context.requirePlayer(), args)
  }

  /** Goals in one tool: `action` set, list, or cancel. */
  private val goals = tool("goals") { context, player, args ->
    when (val action = args.string("action")?.lowercase() ?: "list") {
      "set" -> set(context, player, args)
      "list" -> {
        val goals = context.features.goals.of(player.uuid).all()
        ToolOutput(buildJsonObject { put("goals", buildJsonArray { goals.forEach { add(describe(it)) } }) }, if (goals.isEmpty()) context.lang.text("summary.goals_none") else context.lang.text("summary.goals", "count" to goals.size))
      }
      "cancel" -> {
        val all = args.bool("all") == true
        val id = if (all) null else args.string("id") ?: throw ToolError("id is required to cancel a goal, or all: true")
        val removed = context.features.goals.remove(player, id)
        if (removed == 0 && !all) throw ToolError("there is no goal with this id")
        ToolOutput(buildJsonObject { put("cancelled", removed) }, context.lang.text("summary.goals_cancelled", "count" to removed))
      }
      else -> throw ToolError("action must be set, list, or cancel, not $action")
    }
  }

  private fun set(context: ToolContext, player: ServerPlayer, args: JsonObject): ToolOutput {
    val kind = args.string("kind")?.lowercase() ?: throw ToolError("kind is required: ${Goal.KINDS.joinToString()}")
    if (kind !in Goal.KINDS) throw ToolError("unknown kind $kind; use ${Goal.KINDS.joinToString()}")
    val title = args.string("title")?.take(40) ?: throw ToolError("title is required: what the bar says, in the player's language")
    val color = args.string("color")?.lowercase() ?: "green"
    if (color !in Goals.COLORS) throw ToolError("color must be one of ${Goals.COLORS.joinToString()}")
    val style = args.string("style")?.lowercase() ?: "solid"
    if (style !in Goals.STYLES) throw ToolError("style must be one of ${Goals.STYLES.joinToString()}")
    val amount = args.int("target")
    fun target() = (amount ?: throw ToolError("target is required for $kind")).also { if (it !in 1..1_000_000) throw ToolError("target must be 1–1000000") }.toLong()
    val base = Goal(id = "", kind = kind, title = title, target = 1, color = color, style = style, createdAt = 0)
    val spec = when (kind) {
      "stat" -> {
        val name = args.string("statistic")?.lowercase() ?: throw ToolError("statistic is required, such as mine_block, kill_entity, craft_item, fish_caught, walk_one_cm")
        val statistic = Statistics.kind(name) ?: throw ToolError("no statistic called $name")
        var material: String? = null
        var entity: String? = null
        when (statistic) {
          Statistics.Kind.BLOCK -> material = InteractTools.item(args.string("item") ?: throw ToolError("$name needs item: which block")).let {
            if (Statistics.block(it) == null) throw ToolError("${Recipes.id(it)} is not a block")
            Recipes.id(it)
          }
          Statistics.Kind.ITEM -> material = Recipes.id(InteractTools.item(args.string("item") ?: throw ToolError("$name needs item")))
          Statistics.Kind.ENTITY -> entity = (Goals.entityType(args.string("entity") ?: throw ToolError("$name needs entity, such as zombie"))
            ?: throw ToolError("no mob called ${args.string("entity")}")).let { net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(it).path }
          else -> {}
        }
        // Distances are counted in centimeters; the player thinks in blocks.
        val scale = if (name.endsWith("_one_cm")) 100 else 1
        base.copy(statistic = name, material = material, entity = entity, target = target() * scale, scale = scale)
      }
      "pickup" -> base.copy(material = Recipes.id(InteractTools.item(args.string("item") ?: throw ToolError("pickup needs item"))), target = target())
      "level" -> base.copy(target = target().also { if (it > 1000) throw ToolError("level must be 1–1000") })
      "advancement" -> {
        val key = args.string("advancement")?.lowercase() ?: throw ToolError("advancement is required, such as story/mine_diamond")
        val advancement = Identifier.tryParse(if (':' in key) key else "minecraft:$key")?.let { context.server.advancements.get(it) }
          ?: throw ToolError("no advancement called $key")
        base.copy(advancement = advancement.id().toString())
      }
      "reach" -> {
        val x = args.int("x") ?: throw ToolError("reach needs x and z")
        val z = args.int("z") ?: throw ToolError("reach needs x and z")
        val dimension = args.string("dimension")?.let(Dimensions::parse) ?: Dimensions.id(player.level())
        base.copy(x = x, z = z, radius = (args.int("radius") ?: 10).coerceIn(2, 200), dimension = dimension)
      }
      "biome" -> {
        val name = args.string("biome")?.lowercase()?.removePrefix("minecraft:") ?: throw ToolError("biome is required, such as cherry_grove")
        Identifier.tryBuild(Identifier.DEFAULT_NAMESPACE, name)?.takeIf { context.server.registryAccess().lookupOrThrow(Registries.BIOME).containsKey(it) } ?: throw ToolError("no biome called $name")
        base.copy(biome = name)
      }
      else -> {
        val dimension = Dimensions.parse(args.string("dimension") ?: throw ToolError("dimension is required: the_nether or the_end"))
        base.copy(dimension = dimension)
      }
    }
    val goal = context.features.goals.add(player, spec)
    return ToolOutput(describe(goal), context.lang.text("summary.goal_set", "title" to goal.title, "progress" to goal.progress / goal.scale, "target" to goal.target / goal.scale))
  }

  private fun describe(goal: Goal) = buildJsonObject {
    put("id", goal.id)
    put("kind", goal.kind)
    put("title", goal.title)
    put("progress", goal.progress / goal.scale)
    put("target", goal.target / goal.scale)
    if (goal.kind == "reach") {
      put("x", goal.x)
      put("z", goal.z)
      put("radius", goal.radius)
      put("dimension", goal.dimension)
      // Set from another dimension: the bar shows only the title until the player gets there.
      if (goal.baseline == Goal.UNSET) put("counts_from_arrival_in", goal.dimension)
    }
    if (goal.done) put("done", true)
  }

  val all = listOf(goals)
}
