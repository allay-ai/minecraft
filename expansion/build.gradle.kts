plugins {
  `java-library`
  `maven-publish`
  signing
}

// The expansion API: plain Java with no dependencies, so a plugin or mod in any language can use it and Allay's own
// Kotlin stays private. Allay's jars carry these classes as they are; expansions compile against the published jar.
// It has its own version, apart from Allay's.
version = (findProperty("expansionVersion") as String?)?.takeIf { it.isNotBlank() } ?: "1.0.0"

java {
  toolchain { languageVersion = JavaLanguageVersion.of(25) }
  withSourcesJar()
  withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
  // Java 21 bytecode: a library shouldn't ask for more than the oldest server it could meet.
  options.release = 21
  options.encoding = "UTF-8"
}

tasks.withType<Javadoc>().configureEach {
  (options as StandardJavadocDocletOptions).apply {
    encoding = "UTF-8"
    addStringOption("Xdoclint:none", "-quiet")
  }
}

dependencies {
  testImplementation(platform("org.junit:junit-bom:6.1.3"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
  useJUnitPlatform()
}

publishing {
  publications {
    create<MavenPublication>("maven") {
      artifactId = "allay-expansion"
      from(components["java"])
      pom {
        name = "Allay Expansion API"
        description = "Add tools, items, recipes, and prompt lines to Allay, the AI helper in Minecraft chat, from a Paper, Spigot, Fabric, or NeoForge plugin or mod."
        url = "https://github.com/allay-ai/minecraft/wiki/Expansions"
        licenses {
          license {
            name = "MIT License"
            url = "https://opensource.org/license/mit"
          }
        }
        developers {
          developer {
            id = "mairwunnx"
            name = "MairwunNx"
            url = "https://github.com/MairwunNx"
          }
        }
        scm {
          url = "https://github.com/allay-ai/minecraft"
          connection = "scm:git:https://github.com/allay-ai/minecraft.git"
          developerConnection = "scm:git:ssh://git@github.com/allay-ai/minecraft.git"
        }
      }
    }
  }
  repositories {
    // The release bundles this folder for Maven Central's Portal API (tools/central.py).
    maven {
      name = "staging"
      url = uri(layout.buildDirectory.dir("staging"))
    }
  }
}

signing {
  // The release hands in the armored key and its passphrase (the MAVEN_SIGNING_* secrets); local builds and
  // publishToMavenLocal go unsigned.
  val key = providers.environmentVariable("MAVEN_SIGNING_KEY").orNull
  val password = providers.environmentVariable("MAVEN_SIGNING_PASSWORD").orNull
  isRequired = key != null
  if (key != null) {
    useInMemoryPgpKeys(key, password)
    sign(publishing.publications["maven"])
  }
}
