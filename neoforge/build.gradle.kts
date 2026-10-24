import java.time.Duration

plugins {
  kotlin("jvm")
  kotlin("plugin.serialization")
  id("net.neoforged.moddev")
  id("com.gradleup.shadow")
}

val minecraft = "26.3"
val neoforgeVersion = "26.3.0.95"

base {
  archivesName = "allay-neoforge"
}

val modcore = project(":modcore")
evaluationDependsOn(modcore.path)
val modcoreMain: SourceSet = modcore.sourceSets.main.get()

// What only a player's game runs goes in the client source set, so a dedicated server never loads a client class.
val client: SourceSet = sourceSets.create("client") {
  compileClasspath += sourceSets.main.get().output + modcoreMain.output
  runtimeClasspath += sourceSets.main.get().output + modcoreMain.output
}

// Game tests ask the tools on a real server with a fake survival player (`./gradlew :neoforge:runGameTestServer`). They
// are modcore's, shared with Fabric, and run in a dev-only mod of their own, so the release jar doesn't carry them.
val gametest: SourceSet = sourceSets.create("gametest") {
  compileClasspath += sourceSets.main.get().output + client.output + modcoreMain.output
  runtimeClasspath += sourceSets.main.get().output + client.output + modcoreMain.output
  // The tests' data pack (an item and a brew of its own) is modcore's, shared with Fabric.
  resources.srcDir(modcore.file("src/gametest/resources"))
}
kotlin.sourceSets.named("gametest") {
  kotlin.srcDir(modcore.file("src/gametest/kotlin"))
  kotlin.srcDir(modcore.file("src/gametestPlain/kotlin"))
}

neoForge {
  version = neoforgeVersion
  addModdingDependenciesTo(client)
  addModdingDependenciesTo(gametest)

  // The mod is this module and modcore together, in dev runs as in the jar.
  mods {
    register("allay") {
      sourceSet(sourceSets.main.get())
      sourceSet(client)
      sourceSet(modcoreMain)
    }
    register("allay_gametest") {
      sourceSet(gametest)
    }
  }

  runs {
    register("client") {
      client()
      loadedMods = listOf(mods["allay"])
    }
    register("server") {
      server()
      programArgument("--nogui")
      loadedMods = listOf(mods["allay"])
    }
    // Runs every test and stops: `./gradlew :neoforge:runGameTestServer`.
    register("gameTestServer") {
      type = "gameTestServer"
      sourceSet = gametest
      gameDirectory = file("run/gametest")
      systemProperty("neoforge.enabledGameTestNamespaces", "allay_gametest")
    }
    // A singleplayer world in a game window, checked and closed by the test mod: `./gradlew :neoforge:runClientGameTest`.
    register("clientGameTest") {
      client()
      sourceSet = gametest
      gameDirectory = file("run/clientgametest")
      systemProperty("allay.clientGameTest", "true")
    }
  }
}

// What goes into the jar besides the mod's own classes: modcore, core, and core's libraries, relocated as on Bukkit.
val shade: Configuration by configurations.creating

dependencies {
  implementation(project(":core"))
  // Without its Minecraft: the game comes from NeoForge here.
  compileOnly(project(":modcore")) { isTransitive = false }
  "clientCompileOnly"(project(":modcore")) { isTransitive = false }
  "gametestCompileOnly"(project(":modcore")) { isTransitive = false }
  "clientImplementation"(project(":core"))
  "gametestImplementation"(project(":core"))
  shade(project(":core"))
  shade(project(":modcore")) { isTransitive = false }
  // The game tests run with Text Placeholder API's NeoForge port.
  "gametestCompileOnly"("eu.pb4:placeholder-api:3.2.0+26.3") { isTransitive = false }
  "gametestRuntimeOnly"("maven.modrinth:placeholder-api-neoforge:3.2.0+26.3+neoforge") { isTransitive = false }
}

kotlin {
  jvmToolchain(25)
}

tasks {
  processResources {
    // FML wants a version number: a dev build is 0.0.0-dev, CI's 0.0.0-dev-<sha>.
    val version = project.version.toString().let { if (it.startsWith("dev")) "0.0.0-$it" else it }
    val properties = mapOf("version" to version, "neoforge" to neoforgeVersion, "minecraft" to minecraft)
    inputs.properties(properties)
    filesMatching("META-INF/neoforge.mods.toml") { expand(properties) }
  }

  // Kotlin and the libraries go inside, relocated, so players need no Kotlin mod and nothing clashes with a mod that
  // ships its own copy.
  shadowJar {
    archiveClassifier = ""
    from(client.output)
    configurations = listOf(shade)
    listOf("kotlin", "kotlinx", "okhttp3", "okio", "com.maxmind", "org.yaml.snakeyaml").forEach { relocate(it, "chat.allay.libs.$it") }
    mergeServiceFiles()
    // kotlinx-coroutines' debugger probes sit outside any package and would clash with another copy on NeoForge's module path.
    exclude("META-INF/*.kotlin_module", "META-INF/versions/*/module-info.class", "module-info.class", "_COROUTINE/**", "DebugProbesKt.bin")
    dependencies { exclude(dependency("org.jetbrains:annotations")) }
  }

  // The jar without Kotlin wouldn't load; the shaded one is the mod.
  jar {
    archiveClassifier = "slim"
  }

  assemble {
    dependsOn(shadowJar)
  }

  // A game that hangs is killed, and the run fails: the check closes the game itself within three minutes.
  named("runGameTestServer") {
    timeout = Duration.ofMinutes(10)
  }

  // The singleplayer check writes what it found; anything but `ok` fails the run.
  named("runClientGameTest") {
    timeout = Duration.ofMinutes(6)
    val result = file("run/clientgametest/allay-check.txt")
    doFirst { result.delete() }
    doLast {
      val text = if (result.exists()) result.readText().trim() else "the game closed before the check ran"
      if (text != "ok") throw GradleException("The singleplayer check failed:\n$text")
    }
  }

  // `./gradlew build` runs the game tests, as on Fabric.
  check {
    dependsOn("runGameTestServer")
  }
}
