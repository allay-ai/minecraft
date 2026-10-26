plugins {
  kotlin("jvm")
  kotlin("plugin.serialization")
}

// Everything that doesn't touch a game platform: no Bukkit here, so another platform is only an adapter.
dependencies {
  // The expansion API ships inside every jar as it is, never relocated: other plugins and mods compile against it.
  api(project(":expansion"))
  api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
  api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
  api("com.squareup.okhttp3:okhttp:5.5.1")
  implementation("com.maxmind.db:maxmind-db:4.2.0")
  implementation("org.yaml:snakeyaml:2.7")

  testImplementation(kotlin("test"))
  testImplementation(platform("org.junit:junit-bom:6.1.3"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
  jvmToolchain(25)
}

tasks.test {
  useJUnitPlatform()
}
