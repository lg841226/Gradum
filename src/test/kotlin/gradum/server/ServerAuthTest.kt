package gradum.server

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.*

class ServerAuthTest {

  @Test
  fun `generateToken returns distinct unpadded base64url values`() {
    val firstToken: String = ServerAuth.generateToken()
    val secondToken: String = ServerAuth.generateToken()

    assertNotEquals(firstToken, secondToken)
    assertFalse(firstToken.contains('='), "base64url output must not be padded")
    assertFalse(firstToken.contains('+'), "base64url output must not contain '+'")
    assertFalse(firstToken.contains('/'), "base64url output must not contain '/'")
  }

  @Test
  fun `resolveToken generates then reuses the persisted token`() = withTempConfigDir { configDir ->
    val firstToken: String = ServerAuth.resolveToken()
    assertTrue(firstToken.isNotBlank())

    val tokenFile = File(configDir, ServerAuth.TOKEN_FILE_NAME)
    assertTrue(tokenFile.isFile, "resolveToken should persist the token")
    assertEquals(firstToken, tokenFile.readText(Charsets.UTF_8).trim())

    assertEquals(firstToken, ServerAuth.resolveToken(), "a second resolve should reuse the file")
  }

  @Test
  fun `resolveToken writes an owner-only file on POSIX filesystems`() = withTempConfigDir { configDir ->
    ServerAuth.resolveToken()
    val tokenFile = File(configDir, ServerAuth.TOKEN_FILE_NAME)

    val permissions: Set<PosixFilePermission>? =
      runCatching { Files.getPosixFilePermissions(tokenFile.toPath()) }.getOrNull()
    if (permissions != null) {
      assertEquals(
        PosixFilePermissions.fromString("rw-------"),
        permissions,
        "token file must be readable only by its owner"
      )
    }
  }

  @Test
  fun `tokenMatches compares exactly and rejects blanks`() {
    val token = "correct-token"

    assertTrue(ServerAuth.tokenMatches(token, token))
    assertFalse(ServerAuth.tokenMatches("wrong-token", token))
    assertFalse(ServerAuth.tokenMatches("$token-extra", token))
    assertFalse(ServerAuth.tokenMatches("", token))
    assertFalse(ServerAuth.tokenMatches(null, token))
  }

  @Test
  fun `isAllowedHostHeader accepts loopback names with ports`() {
    assertTrue(ServerAuth.isAllowedHostHeader("localhost"))
    assertTrue(ServerAuth.isAllowedHostHeader("localhost:8765"))
    assertTrue(ServerAuth.isAllowedHostHeader("127.0.0.1:8765"))
    assertTrue(ServerAuth.isAllowedHostHeader("[::1]:8765"))
    assertTrue(ServerAuth.isAllowedHostHeader("::ffff:127.0.0.1"))
  }

  @Test
  fun `isAllowedHostHeader rejects non-loopback and blank values`() {
    assertFalse(ServerAuth.isAllowedHostHeader("evil.com"))
    assertFalse(ServerAuth.isAllowedHostHeader("evil.com:8765"))
    assertFalse(ServerAuth.isAllowedHostHeader("192.168.1.10:8765"))
    assertFalse(ServerAuth.isAllowedHostHeader(null))
    assertFalse(ServerAuth.isAllowedHostHeader(""))
  }

  @Test
  fun `isLoopbackBindHost accepts loopback and rejects wildcard addresses`() {
    assertTrue(ServerAuth.isLoopbackBindHost("localhost"))
    assertTrue(ServerAuth.isLoopbackBindHost("127.0.0.1"))
    assertTrue(ServerAuth.isLoopbackBindHost("::1"))
    assertFalse(ServerAuth.isLoopbackBindHost("0.0.0.0"))
    assertFalse(ServerAuth.isLoopbackBindHost("192.168.1.10"))
  }

  /** Points `gradum.server.configDir` at a temp dir for the duration of [block]. */
  private fun withTempConfigDir(block: (File) -> Unit) {
    val tempDir: File = Files.createTempDirectory("gradum-auth-test").toFile()
    val previousValue: String? = System.getProperty("gradum.server.configDir")
    System.setProperty("gradum.server.configDir", tempDir.absolutePath)
    try {
      block(tempDir)
    } finally {
      if (previousValue == null) {
        System.clearProperty("gradum.server.configDir")
      } else {
        System.setProperty("gradum.server.configDir", previousValue)
      }
      tempDir.deleteRecursively()
    }
  }
}
