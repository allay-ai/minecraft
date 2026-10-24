pluginManagement {
  repositories {
    gradlePluginPortal()
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
  }
}

rootProject.name = "allay"

// core holds everything that doesn't touch a game platform; spigot is the plugin for Spigot, Paper, and their forks;
// modcore is what the mods share, the game without a loader; fabric and neoforge are the mods, on a dedicated server
// and in a singleplayer world. expansion is the API other plugins and mods add tools and items through.
include("expansion", "core", "spigot", "modcore", "fabric", "neoforge")
