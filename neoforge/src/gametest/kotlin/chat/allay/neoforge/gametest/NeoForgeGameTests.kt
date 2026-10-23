package chat.allay.neoforge.gametest

import chat.allay.core.Permissions
import chat.allay.modcore.Perms
import chat.allay.modcore.gametest.GameTest
import chat.allay.modcore.gametest.Players.survivor

import net.minecraft.gametest.framework.GameTestHelper
import net.neoforged.neoforge.server.permission.PermissionAPI

/** What only NeoForge does its own way: its PermissionAPI, which answers only for nodes named before the server starts. */
class NeoForgeGameTests {
  @GameTest
  fun everyPermissionIsANodeOfNeoForgesPermissionApi(helper: GameTestHelper) {
    val registered = PermissionAPI.getRegisteredNodes().map { it.nodeName }.toSet()
    val missing = Permissions.all(null).filter { it !in registered }
    helper.assertTrue(missing.isEmpty(), "nodes PermissionAPI doesn't know: $missing")
    helper.succeed()
  }

  @GameTest
  fun aPlayerWithoutRightsGetsPluginYmlsDefaults(helper: GameTestHelper) {
    val player = survivor(helper)
    helper.assertTrue(Perms.check(player, Permissions.ASK), "anyone may ask")
    helper.assertTrue(Perms.check(player, Permissions.automation(chat.allay.core.tasks.Kind.CHESTS)), "anyone may sort chests")
    helper.assertTrue(!Perms.check(player, Permissions.ADMIN), "only operators link a license on a dedicated server")
    helper.assertTrue(!Perms.check(player, Permissions.ASKX), "only operators turn requests into commands")
    player.discard()
    helper.succeed()
  }
}
