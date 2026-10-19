package chat.allay.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AdminSetupTest {
  @Test fun `a setup link, a recovery link, an existing admin, a hosted backend, and an old one read apart`() {
    assertEquals(
      AdminInit.Link("https://mc.example/allay/admin/setup?code=abc", "2026-10-18T16:00:00Z"),
      AdminSetup.parse(200, """{"url":"https://mc.example/allay/admin/setup?code=abc","expires_at":"2026-10-18T16:00:00Z"}"""),
    )
    assertEquals(
      AdminInit.Exists("https://mc.example/allay/admin"),
      AdminSetup.parse(409, """{"error":{"code":"ADMIN_EXISTS","message":"x"},"url":"https://mc.example/allay/admin"}"""),
    )
    assertEquals(
      AdminInit.Link("https://mc.example/allay/admin/setup?code=abc", null),
      AdminSetup.parse(200, """{"url":"https://mc.example/allay/admin/setup?code=abc","mode":"setup"}"""),
    )
    assertEquals(
      AdminInit.Recovery("https://mc.example/allay/admin/recover?code=abc", "2026-10-18T16:00:00Z"),
      AdminSetup.parse(200, """{"url":"https://mc.example/allay/admin/recover?code=abc","expires_at":"2026-10-18T16:00:00Z","mode":"recover"}"""),
    )
    assertEquals(AdminInit.NotSelfHosted, AdminSetup.parse(404, """{"error":{"code":"NOT_SELFHOSTED","message":"x"}}"""))
    assertEquals(AdminInit.Unsupported, AdminSetup.parse(404, ""))
  }

  @Test fun `anything but a web address is no link`() {
    assertFailsWith<ApiErrorException> { AdminSetup.parse(200, """{"url":"javascript:alert(1)"}""") }
    assertEquals(AdminInit.Exists(null), AdminSetup.parse(409, """{"error":{"code":"ADMIN_EXISTS"},"url":"file:///etc"}"""))
  }

  @Test fun `other failures are errors`() {
    assertFailsWith<ApiErrorException> { AdminSetup.parse(500, """{"error":{"code":"ADMIN_ERROR"}}""") }
  }
}
