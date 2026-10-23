package chat.allay.fabric

import chat.allay.modcore.Loader
import chat.allay.modcore.ModGame

import net.fabricmc.fabric.api.recipe.v1.ingredient.FabricIngredient
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.crafting.Ingredient
import java.nio.file.Path

/** Fabric as the shared mod's loader: Fabric Loader's mod list, Fabric API's ingredients, and fabric-permissions-api. */
object OnFabric : Loader {
  override val id = "fabric"
  override val software = "Fabric"
  override val version: String get() = FabricLoader.getInstance().getModContainer("allay").map { it.metadata.version.friendlyString }.orElse("dev")
  override val config: Path get() = FabricLoader.getInstance().configDir
  override fun loaded(mod: String) = FabricLoader.getInstance().isModLoaded(mod)

  /** What Fabric API's own modules and the loader's built-ins are called: the list shows mods a player installed. */
  private val BUILT_IN = setOf("minecraft", "java", "fabricloader", "mixinextras")

  override fun mods(): List<Loader.ModInfo> = FabricLoader.getInstance().allMods
    .filter { mod -> mod.metadata.type != "builtin" && mod.metadata.id !in BUILT_IN && mod.containingMod.isEmpty }
    .map { Loader.ModInfo(it.metadata.id, it.metadata.name, it.metadata.version.friendlyString, it.metadata.description) }

  override fun custom(ingredient: Ingredient) = (ingredient as FabricIngredient).customIngredient != null

  // fabric-permissions-api comes with LuckPerms; its classes are touched only when it's loaded.
  private val permissions by lazy { loaded("fabric-permissions-api-v0") }

  override fun permission(source: CommandSourceStack, permission: String, fallback: Boolean) =
    if (permissions) me.lucko.fabric.api.permissions.v0.Permissions.check(source, permission, fallback) else fallback

  override fun permission(player: ServerPlayer, permission: String, fallback: Boolean) =
    if (permissions) me.lucko.fabric.api.permissions.v0.Permissions.check(player, permission, fallback) else fallback

  override fun integrations(game: ModGame): List<AutoCloseable> {
    if (!Plan.available) return emptyList()
    var plan: AutoCloseable? = null
    val allay = game.allay
    // Plan starts with the server too; by the first tick it's on.
    val later = game.platform.scheduler.later(1) {
      plan = Plan.register(Plan.Sources(allay.settings, game.goals, allay.reminders, allay.locales, allay.license, { allay.config }) { allay.watch?.status })
    }
    return listOf(AutoCloseable {
      later.close()
      plan?.close()
    })
  }
}
