package chat.allay.spigot

import chat.allay.core.Permissions
import chat.allay.core.YamlConfig
import org.yaml.snakeyaml.Yaml
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PermissionsTest {
  /** plugin.yml's permissions as written: YamlConfig would turn their dotted names into sections. */
  @Suppress("UNCHECKED_CAST")
  private val permissions = Yaml().load<Map<String, Any?>>(javaClass.getResource("/plugin.yml")!!.readText())["permissions"] as Map<String, Map<String, Any?>>

  @Test fun `the mods' defaults are plugin yml's`() {
    assertTrue(permissions.size > 10, "plugin.yml's permissions: ${permissions.keys}")
    permissions.forEach { (name, permission) ->
      assertEquals(permission["default"] == true, Permissions.byDefault(name, operator = false), name)
      assertEquals(true, Permissions.byDefault(name, operator = true), name)
    }
    assertEquals(false, Permissions.byDefault(Permissions.quota("vip"), operator = false))
  }

  @Test fun `every permission the mods name up front is plugin yml's, plus the config's tiers`() {
    assertEquals(permissions.keys, Permissions.all(null).toSet())
    val config = YamlConfig(YamlConfig.parse("quota:\n  tiers:\n    default: 1\n    vip: 3\nmodels:\n  vip: claude-opus-5-5\n  staff: claude-sonnet-5-5\n"))
    assertEquals(permissions.keys + setOf("allay.quota.default", "allay.quota.vip", "allay.model.vip", "allay.model.staff"), Permissions.all(config).toSet())
  }
}
