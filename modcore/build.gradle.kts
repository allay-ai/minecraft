plugins {
  kotlin("jvm")
  kotlin("plugin.serialization")
  id("net.neoforged.moddev")
}

// The game tests both mods run (`src/gametest`): each mod compiles them with its loader's `GameTest` annotation; here
// they compile once with `src/gametestPlain` (the annotation and a claims mod for a loader without its own), so
// they're checked against the game alone.
val gametest: SourceSet = sourceSets.create("gametest") {
  compileClasspath += sourceSets.main.get().output
}
kotlin.sourceSets.named("gametest") {
  kotlin.srcDir("src/gametestPlain/kotlin")
}

// The game without a loader, as both mods see it: NeoForm's Minecraft, whose names are the game's own since 26.1.
// Whatever reads or changes the world the same way on Fabric and NeoForge lives here; `fabric` and `neoforge` only add
// what their loader does differently (see `Loader`).
neoForge {
  neoFormVersion = "26.3-1"
  addModdingDependenciesTo(gametest)
}

dependencies {
  api(project(":core"))
  // Mixins: Fabric Loader and NeoForge both bring Mixin and MixinExtras at runtime.
  compileOnly("net.fabricmc:sponge-mixin:0.17.5+mixin.0.8.7")
  compileOnly("io.github.llamalad7:mixinextras-common:0.5.5")
  // Optional mods: Allay asks them only when they're loaded. Their APIs read the same on both loaders.
  compileOnly("eu.pb4:placeholder-api:3.2.0+26.3") { isTransitive = false }
  compileOnly("eu.pb4:common-protection-api:2.0.0") { isTransitive = false }
  compileOnly("maven.modrinth:flan:26.3-1.12.8.b-fabric") { isTransitive = false }
  compileOnly("maven.modrinth:open-parties-and-claims:neoforge-26.3-0.33.0") { isTransitive = false }
}

dependencies {
  "gametestImplementation"(project(":core"))
  "gametestCompileOnly"("eu.pb4:placeholder-api:3.2.0+26.3") { isTransitive = false }
}

kotlin {
  jvmToolchain(25)
}
