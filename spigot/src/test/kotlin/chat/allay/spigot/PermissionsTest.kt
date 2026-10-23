package chat.allay.spigot

import chat.allay.core.Permissions
import chat.allay.core.YamlConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class PermissionsTest {
  @Test fun `the mods' defaults are plugin yml's`() {
    val yaml = YamlConfig.parse(javaClass.getResource("/plugin.yml")!!.readText())
    @Suppress("UNCHECKED_CAST") val permissions = yaml["permissions"] as Map<String, Map<String, Any?>>
    permissions.forEach { (name, permission) ->
      assertEquals(permission["default"] == true, Permissions.byDefault(name, operator = false), name)
      assertEquals(true, Permissions.byDefault(name, operator = true), name)
    }
    assertEquals(false, Permissions.byDefault(Permissions.quota("vip"), operator = false))
  }
}
