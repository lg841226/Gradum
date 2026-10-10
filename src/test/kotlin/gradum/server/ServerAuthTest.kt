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
  fun `pairing state unlocks only for its own code, blanks aside`() {
    val pairingState = PairingState(code = "K7X2P")

    assertTrue(pairingState.tryUnlock("K7X2P"))
    assertTrue(pairingState.tryUnlock("k7x2p"))
    assertFalse(pairingState.tryUnlock("ZZZZZ"))
    assertFalse(pairingState.tryUnlock(""))
    assertFalse(pairingState.tryUnlock(null))
  }

  @Test
  fun `generatePairingCode always produces five alphabet characters`() {
    repeat(times = 50) {
      assertTrue(
        PairingState.generatePairingCode()
          .matches(Regex("""[${PairingState.CODE_ALPHABET}]{${PairingState.CODE_LENGTH}}"""))
      )
    }
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

  @Test
  fun `isAllowedHostHeader accepts this machine's names when bound past loopback`() {
    val machineNames = setOf("192.168.1.7", "studio.local", "studio")

    assertTrue(ServerAuth.isAllowedHostHeader("192.168.1.7:8765", "0.0.0.0", machineNames))
    assertTrue(ServerAuth.isAllowedHostHeader("Studio.local", "192.168.1.7", machineNames))
    assertTrue(ServerAuth.isAllowedHostHeader("studio:8765", "0.0.0.0", machineNames))
    assertTrue(ServerAuth.isAllowedHostHeader("localhost:8765", "0.0.0.0", machineNames))
  }

  @Test
  fun `isAllowedHostHeader still rejects foreign hosts when bound past loopback`() {
    val machineNames = setOf("192.168.1.7")

    assertFalse(ServerAuth.isAllowedHostHeader("evil.com:8765", "0.0.0.0", machineNames))
    assertFalse(ServerAuth.isAllowedHostHeader("192.168.1.8", "0.0.0.0", machineNames))
    assertFalse(ServerAuth.isAllowedHostHeader(null, "0.0.0.0", machineNames))
    assertFalse(ServerAuth.isAllowedHostHeader("", "0.0.0.0", machineNames))
  }

  @Test
  fun `isAllowedHostHeader ignores machine names on a loopback bind`() {
    val machineNames = setOf("192.168.1.7")

    assertFalse(ServerAuth.isAllowedHostHeader("192.168.1.7", "localhost", machineNames))
    assertFalse(ServerAuth.isAllowedHostHeader("192.168.1.7", "127.0.0.1", machineNames))
  }

  @Test
  fun `localAddresses and localMachineHostNames never expose loopback or link-local`() {
    for (address in ServerAuth.localAddresses()) {
      assertFalse(address.startsWith("127."), "loopback must not be listed: $address")
      assertFalse(address.startsWith("fe80:"), "link-local must not be listed: $address")
      assertFalse(address.contains('%'), "IPv6 zone ids must be stripped: $address")
    }
    assertTrue(
      ServerAuth.localMachineHostNames().containsAll(ServerAuth.localAddresses()),
      "the Host-name set must cover every listed address"
    )
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
