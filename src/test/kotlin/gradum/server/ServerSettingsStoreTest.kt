/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ServerSettingsStoreTest.kt  2026-09-11 16:24:36 Changed by gwy
 */
package gradum.server

import java.io.File
import java.nio.file.Files
import kotlin.test.*

class ServerSettingsStoreTest {

  private lateinit var tempDir: File

  @BeforeTest
  fun setUp() {
    tempDir = Files.createTempDirectory("gradum-server-settings").toFile()
    System.setProperty("gradum.server.configDir", tempDir.absolutePath)
  }

  @AfterTest
  fun tearDown() {
    System.clearProperty("gradum.server.configDir")
    tempDir.deleteRecursively()
  }

  @Test
  fun `releaseDefaultsIfMissing writes both files`() {
    ServerSettingsStore.releaseDefaultsIfMissing()

    assertTrue(File(tempDir, "settings.json").isFile)
    assertTrue(File(tempDir, "settings.schema.json").isFile)
    assertTrue(File(tempDir, "settings.json").length() > 0)
    assertTrue(File(tempDir, "settings.schema.json").length() > 0)
  }

  @Test
  fun `releaseDefaultsIfMissing does not overwrite existing settings`() {
    File(tempDir, "settings.json").writeText("""{"server":{"port":9000}}""")

    ServerSettingsStore.releaseDefaultsIfMissing()

    assertEquals("""{"server":{"port":9000}}""", File(tempDir, "settings.json").readText())
  }

  @Test
  fun `load uses defaults when file is missing`() {
    val settings: ServerSettings = ServerSettingsStore.load()

    assertEquals(ServerConfiguration.DEFAULT_HOST_ADDRESS, settings.host)
    assertEquals(ServerConfiguration.DEFAULT_PORT_NUMBER, settings.port)
    assertFalse(settings.autoDetectPort)
    assertNull(settings.apiKeyFile)
    assertEquals(ServerConfiguration.DEFAULT_BASE_URL, settings.defaultBaseUrl)
    assertEquals("", settings.defaultModelName)
    assertFalse(settings.defaultThinkEnabled)
    assertEquals(5, settings.defaultKeepAliveMinutes)
    assertTrue(settings.mcpServers.isEmpty())
  }

  @Test
  fun `load parses mcpServers and skips malformed entries`() {
    File(tempDir, "settings.json").writeText(
      """
        {
          "mcpServers": [
            {
              "name": "fs",
              "command": ["npx", "-y", "server-filesystem", "/tmp"],
              "workingDir": "/tmp",
              "env": {"FOO": "bar"}
            },
            {"name": "bad", "command": "npx"}
          ]
        }
      """.trimIndent()
    )

    val settings: ServerSettings = ServerSettingsStore.load()

    assertEquals(1, settings.mcpServers.size)
    val server = settings.mcpServers.single()
    assertEquals("fs", server.name)
    assertEquals(listOf("npx", "-y", "server-filesystem", "/tmp"), server.command)
    assertEquals("/tmp", server.workingDir)
    assertEquals(mapOf("FOO" to "bar"), server.env)
  }

  @Test
  fun `load parses hand-written settings`() {
    File(tempDir, "settings.json").writeText(
      """
        {
          "server": {
            "host": "0.0.0.0",
            "port": 9123,
            "autoDetectPort": true,
            "apiKeyFile": "/tmp/secret.key"
          },
          "llm": {
            "baseUrl": "https://api.deepseek.com/v1",
            "model": "deepseek-chat",
            "think": true,
            "keepAliveMinutes": 120
          }
        }
      """.trimIndent()
    )

    val settings: ServerSettings = ServerSettingsStore.load()

    assertEquals("0.0.0.0", settings.host)
    assertEquals(9123, settings.port)
    assertTrue(settings.autoDetectPort)
    assertEquals("/tmp/secret.key", settings.apiKeyFile)
    assertEquals("https://api.deepseek.com/v1", settings.defaultBaseUrl)
    assertEquals("deepseek-chat", settings.defaultModelName)
    assertTrue(settings.defaultThinkEnabled)
    assertEquals(120, settings.defaultKeepAliveMinutes)
  }

  @Test
  fun `load falls back to defaults on malformed json`() {
    File(tempDir, "settings.json").writeText("not { valid json")

    val settings: ServerSettings = ServerSettingsStore.load()

    assertEquals(ServerConfiguration.DEFAULT_PORT_NUMBER, settings.port)
    assertEquals(ServerConfiguration.DEFAULT_HOST_ADDRESS, settings.host)
  }

  @Test
  fun `resolveApiKeyFromFile reads first non-blank line`() {
    val keyFile = File(tempDir, "api.key")
    keyFile.writeText("\nsk-super-secret  \nignored")

    assertEquals("sk-super-secret", ServerSettingsStore.resolveApiKeyFromFile(keyFile.absolutePath))
  }

  @Test
  fun `resolveApiKeyFromFile returns null for blank or missing path`() {
    assertNull(ServerSettingsStore.resolveApiKeyFromFile(null))
    assertNull(ServerSettingsStore.resolveApiKeyFromFile(""))
    assertNull(ServerSettingsStore.resolveApiKeyFromFile("  "))
    assertNull(ServerSettingsStore.resolveApiKeyFromFile(File(tempDir, "absent.key").absolutePath))
  }
}
