package chat.allay.neoforge.gametest

import chat.allay.modcore.gametest.AllayGameTests
import chat.allay.modcore.gametest.AutomationGameTests
import chat.allay.modcore.gametest.FixesGameTests
import chat.allay.modcore.gametest.GameTest
import chat.allay.modcore.gametest.GoalsGameTests
import chat.allay.modcore.gametest.InfoGameTests
import chat.allay.modcore.gametest.MenusGameTests

import net.minecraft.core.Holder
import net.minecraft.core.registries.Registries
import net.minecraft.gametest.framework.FunctionGameTestInstance
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.gametest.framework.TestData
import net.minecraft.gametest.framework.TestEnvironmentDefinition
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Rotation
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.event.RegisterGameTestsEvent
import net.neoforged.neoforge.registries.DeferredRegister
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.function.Consumer

/**
 * modcore's game tests on NeoForge, as Fabric API runs them on Fabric, and NeoForge's own ([NeoForgeGameTests]): every
 * method marked [GameTest] in these classes is a test, named and set up the same way, in an empty 8×8×8 structure. A dev-only mod next to Allay;
 * `./gradlew :neoforge:runGameTestServer` runs them, and `runClientGameTest` the singleplayer check ([ClientCheck]).
 */
@Mod("allay_gametest")
class AllayTests(modBus: IEventBus) {
  private class Test(val id: Identifier, val annotation: GameTest, val run: Consumer<GameTestHelper>)

  private val tests = listOf(AllayGameTests(), AutomationGameTests(), GoalsGameTests(), InfoGameTests(), MenusGameTests(), FixesGameTests(), NeoForgeGameTests())
    .flatMap { instance -> instance.javaClass.methods.filter { it.isAnnotationPresent(GameTest::class.java) }.map { test(instance, it) } }

  init {
    val functions = DeferredRegister.create(Registries.TEST_FUNCTION, NAMESPACE)
    tests.forEach { test -> functions.register(test.id.path) { -> test.run } }
    functions.register(modBus)
    modBus.addListener(::register)
    ClientCheck.start()
  }

  private fun test(instance: Any, method: Method): Test {
    val name = "${instance.javaClass.simpleName}_${method.name}".replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()
    return Test(Identifier.fromNamespaceAndPath(NAMESPACE, name), method.getAnnotation(GameTest::class.java)) { helper ->
      try {
        method.invoke(instance, helper)
      } catch (error: InvocationTargetException) {
        throw error.targetException as? RuntimeException ?: RuntimeException(error.targetException)
      }
    }
  }

  /** The tests' instances; their environments only keep batches apart, so each is empty, as Fabric's JSON ones are. */
  private fun register(event: RegisterGameTestsEvent) {
    val environments = mutableMapOf<String, Holder<TestEnvironmentDefinition<*>>>()
    fun environment(name: String) = environments.getOrPut(name) {
      val id = if (name == "minecraft:default") Identifier.fromNamespaceAndPath(NAMESPACE, "default") else Identifier.parse(name)
      event.registerEnvironment(id, TestEnvironmentDefinition.AllOf())
    }
    tests.forEach { test ->
      val data = TestData(
        environment(test.annotation.environment), Level.OVERWORLD, Identifier.fromNamespaceAndPath(NAMESPACE, "empty"),
        test.annotation.maxTicks, 0, true, Rotation.NONE, false, 1, 1, false, test.annotation.padding,
      )
      event.registerTest(test.id, FunctionGameTestInstance(ResourceKey.create(Registries.TEST_FUNCTION, test.id), data))
    }
  }

  companion object {
    const val NAMESPACE = "allay_gametest"
  }
}
