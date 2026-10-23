plugins {
  kotlin("jvm")
  kotlin("plugin.serialization")
  id("net.fabricmc.fabric-loom")
  id("com.gradleup.shadow")
}

val minecraft = "26.3"
val loader = "0.20.1"
val fabricApi = "0.165.0+26.3"

base {
  archivesName = "allay-fabric"
}

loom {
  mods.register("allay") {
    sourceSet(sourceSets.main.get())
  }
}

// Game tests ask the tools on a real server with a fake survival player (`./gradlew :fabric:runGameTest`);
// `./gradlew :fabric:runClientGameTest` opens a singleplayer world in a game window. They live in `src/gametest` as a
// mod of their own, so the release jar doesn't carry them.
fabricApi {
  configureTests {
    createSourceSet = true
    modId = "allay-gametest"
    enableGameTests = true
    enableClientGameTests = true
    eula = true
  }
}

// What goes into the jar besides the mod's own classes: core and its libraries, relocated as on Bukkit.
val shade: Configuration by configurations.creating

dependencies {
  minecraft("com.mojang:minecraft:$minecraft")
  implementation("net.fabricmc:fabric-loader:$loader")
  implementation("net.fabricmc.fabric-api:fabric-api:$fabricApi")
  implementation(project(":core"))
  shade(project(":core"))
  // Optional mods: Allay asks them only when they're loaded.
  compileOnly("me.lucko:fabric-permissions-api:0.7.0")
  compileOnly("eu.pb4:placeholder-api:3.2.0+26.3") { isTransitive = false }
  compileOnly("eu.pb4:common-protection-api:2.0.0") { isTransitive = false }
  compileOnly("com.djrapitops:plan-api:5.9-R1.3")

  testImplementation(kotlin("test"))
  testImplementation(platform("org.junit:junit-bom:6.1.3"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
  jvmToolchain(25)
}

tasks {
  test {
    useJUnitPlatform()
  }

  processResources {
    val versions = mapOf("version" to project.version.toString(), "minecraft" to minecraft, "loader" to loader)
    inputs.properties(versions)
    filesMatching("fabric.mod.json") {
      expand(versions)
    }
  }

  // Kotlin and the libraries go inside, relocated, so players need no Kotlin mod and nothing clashes with a mod that
  // ships its own copy.
  shadowJar {
    archiveClassifier = ""
    configurations = listOf(shade)
    listOf("kotlin", "kotlinx", "okhttp3", "okio", "com.maxmind", "org.yaml.snakeyaml").forEach { relocate(it, "chat.allay.libs.$it") }
    mergeServiceFiles()
    exclude("META-INF/*.kotlin_module", "META-INF/versions/*/module-info.class", "module-info.class")
    dependencies { exclude(dependency("org.jetbrains:annotations")) }
  }

  // The jar without Kotlin wouldn't load; the shaded one is the mod.
  jar {
    archiveClassifier = "slim"
  }

  assemble {
    dependsOn(shadowJar)
  }
}
