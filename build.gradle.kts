plugins {
  kotlin("jvm") version "2.4.21" apply false
  kotlin("plugin.serialization") version "2.4.21" apply false
  id("com.gradleup.shadow") version "9.6.1" apply false
  id("com.modrinth.minotaur") version "2.10.0" apply false
  id("io.papermc.hangar-publish-plugin") version "0.1.4" apply false
}

allprojects {
  group = "chat.allay"
  // The release workflow passes the version; every other build is a dev build.
  version = (findProperty("releaseVersion") as String?)?.takeIf { it.isNotBlank() } ?: "dev"

  repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/") {
      name = "spigotmc-repo"
    }
    // paper-api, only for the windows Paper draws its own way (spigot's `paper` source set).
    maven("https://repo.papermc.io/repository/maven-public/") {
      content {
        includeGroup("io.papermc.paper")
        includeModule("com.mojang", "brigadier")
        includeVersion("net.md-5", "bungeecord-chat", "1.21-R0.2-deprecated+build.21")
      }
    }
    maven("https://repo.extendedclip.com/releases/")
    maven("https://repo.playeranalytics.net/releases/")
    // GriefPrevention publishes nowhere else; only its group comes from here.
    maven("https://jitpack.io") {
      content { includeGroup("com.github.GriefPrevention") }
    }
  }
}
