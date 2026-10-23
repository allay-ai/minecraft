import io.papermc.hangarpublishplugin.model.Platforms

plugins {
  kotlin("jvm")
  kotlin("plugin.serialization")
  id("com.gradleup.shadow")
  id("com.modrinth.minotaur")
  id("io.papermc.hangar-publish-plugin")
}

// Paper leaves out Spigot's dialog API and has its own: this source set draws windows with it, and loads only on Paper.
val paper: SourceSet by sourceSets.creating

dependencies {
  "paperCompileOnly"(project(":core"))
  "paperCompileOnly"("io.papermc.paper:paper-api:26.3.build.205-stable")
  implementation(paper.output)
  implementation(project(":core"))
  compileOnly("org.spigotmc:spigot-api:26.3-R0.1-SNAPSHOT")
  compileOnly("me.clip:placeholderapi:2.12.3")
  compileOnly("com.djrapitops:plan-api:5.9-R1.3")
  compileOnly("com.github.GriefPrevention:GriefPrevention:16.18.7") { isTransitive = false }

  testImplementation("org.spigotmc:spigot-api:26.3-R0.1-SNAPSHOT")
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

  shadowJar {
    archiveBaseName = "allay-spigot"
    archiveClassifier = ""
    // Relocated so other plugins that ship their own Kotlin, OkHttp, MaxMind reader, or SnakeYAML can't clash with ours.
    listOf("kotlin", "kotlinx", "okhttp3", "okio", "com.maxmind", "org.yaml.snakeyaml").forEach { relocate(it, "chat.allay.libs.$it") }
    mergeServiceFiles()
    exclude("META-INF/*.kotlin_module", "META-INF/versions/*/module-info.class", "module-info.class")
    dependencies { exclude(dependency("org.jetbrains:annotations")) }
  }

  // One jar with everything inside; a jar without Kotlin wouldn't load anyway.
  jar { enabled = false }

  assemble {
    dependsOn(shadowJar)
  }

  processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
      expand(props)
    }
  }
}

// The release workflow writes the notes to a file; a local upload falls back to the GitHub release.
val changelogText = (findProperty("changelogFile") as String?)?.let { rootProject.file(it).readText() }
  ?: "https://github.com/allay-ai/minecraft/releases/tag/v${project.version}"

// `./gradlew modrinth` with MODRINTH_TOKEN set; the release workflow runs it when asked to.
modrinth {
  token = providers.environmentVariable("MODRINTH_TOKEN")
  projectId = "allay-ai"
  // Each platform is its own version of the one project: this one `X.Y.Z+spigot`, the mod's `X.Y.Z+fabric`.
  versionNumber = "${project.version}+spigot"
  versionName = "Allay AI ${project.version} for Spigot and Paper"
  versionType = "release"
  uploadFile.set(tasks.shadowJar)
  gameVersions.addAll("26.3")
  loaders.addAll("spigot", "paper", "purpur")
  changelog = changelogText
  syncBodyFrom = rootProject.file("MODRINTH.md").readText()
}

tasks.modrinth {
  dependsOn(tasks.modrinthSyncBody)
}

// `./gradlew publishPluginPublicationToHangar` with HANGAR_TOKEN set; the release workflow runs it when asked to.
hangarPublish {
  publications.register("plugin") {
    version = project.version.toString()
    id = "AllayAI"
    channel = "Release"
    apiKey = providers.environmentVariable("HANGAR_TOKEN")
    changelog = changelogText
    // The project's main page is the Modrinth text, so both stores say the same.
    pages.resourcePage(providers.fileContents(rootProject.layout.projectDirectory.file("MODRINTH.md")).asText)
    platforms {
      register(Platforms.PAPER) {
        jar = tasks.shadowJar.flatMap { it.archiveFile }
        platformVersions = listOf("26.3")
      }
    }
  }
}

// As Modrinth's body syncs before its upload, Hangar's main page does before each version.
tasks.named("publishPluginPublicationToHangar") {
  dependsOn("syncAllPluginPublicationPagesToHangar")
}
