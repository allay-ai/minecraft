package chat.allay.core

import chat.allay.expansion.v2.Expansion
import java.lang.reflect.Modifier

/**
 * API 2 expansions named in `META-INF/services/chat.allay.expansion.v2.Expansion`. Java's ServiceLoader builds a class
 * through its constructor only, and a Kotlin `object` has none it may call, so Allay reads the files itself: an
 * `object` (or a Java class with a static `INSTANCE`) through that instance, a class through a public constructor
 * without parameters.
 */
object ExpansionServices {
  const val FILE = "META-INF/services/chat.allay.expansion.v2.Expansion"

  /** One class a services file names: its name, and the expansion or why it couldn't be made. */
  class Found(val name: String, val expansion: Result<Expansion>)

  /** Every expansion [loader]'s services files name, of the classes [own] accepts. */
  fun find(loader: ClassLoader, own: (Class<*>) -> Boolean = { true }): List<Found> =
    loader.getResources(FILE).toList()
      .flatMap { url -> url.openStream().bufferedReader().use { it.readLines() } }
      .map { it.substringBefore('#').trim() }
      .filter { it.isNotEmpty() }
      .distinct()
      .mapNotNull { name ->
        val type = try {
          Class.forName(name, false, loader)
        } catch (error: Throwable) {
          return@mapNotNull Found(name, Result.failure(error))
        }
        if (!own(type)) null else Found(name, runCatching { instance(type) })
      }

  private fun instance(type: Class<*>): Expansion {
    require(Expansion::class.java.isAssignableFrom(type)) { "${type.name} doesn't extend Expansion" }
    val single = type.declaredFields.firstOrNull { it.name == "INSTANCE" && Modifier.isStatic(it.modifiers) && it.type == type }
    val made = single?.apply { isAccessible = true }?.get(null) ?: type.getConstructor().newInstance()
    return made as Expansion
  }
}
