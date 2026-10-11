package gradum.idea.provider

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Unit tests for the `mcpServers` read-modify-write path of
 * [ProviderConfigFile]: command tokenizing/formatting and the lossless
 * JSON round trip (string arrays and env values must never become
 * numbers; unknown keys must survive).
 */
class ProviderConfigFileMcpTest {

  private lateinit var tempDir: File
  private var previousConfigDir: String? = null

  @Before
  fun setUp() {
    tempDir = Files.createTempDirectory("gradum-mcp-test").toFile()
    previousConfigDir = System.getProperty("gradum.provider.configDir")
    System.setProperty("gradum.provider.configDir", tempDir.absolutePath)
  }

  @After
  fun tearDown() {
    if (previousConfigDir == null) System.clearProperty("gradum.provider.configDir")
    else System.setProperty("gradum.provider.configDir", previousConfigDir)
    tempDir.deleteRecursively()
  }

  private fun settingsFile(): File = File(tempDir, "settings.json")

  @Test
  fun `parseCommandText splits on whitespace and honors double quotes`() {
    assertEquals(listOf("npx", "-y", "pkg", "/tmp"), ProviderConfigFile.parseCommandText("npx -y pkg /tmp"))
    assertEquals(listOf("a b", "c"), ProviderConfigFile.parseCommandText("\"a b\" c"))
    assertEquals(listOf("a b c"), ProviderConfigFile.parseCommandText("  \"a b c\"  "))
    assertTrue(ProviderConfigFile.parseCommandText("").isEmpty())
    assertTrue(ProviderConfigFile.parseCommandText("npx \"a b").isEmpty())
  }

  @Test
  fun `formatCommandText round-trips quoted tokens`() {
    val command = listOf("node", "-e", "print(\"x\")")
    val text = ProviderConfigFile.formatCommandText(command)
    assertEquals(command, ProviderConfigFile.parseCommandText(text))
  }

  @Test
  fun `writeMcpServers keeps env values and command args as strings`() {
    ProviderConfigFile.writeMcpServers(
      listOf(
        ProviderConfigFile.McpServerEntry(
          name = "fs",
          command = listOf("node", "server.js", "8080"),
          workingDir = null,
          env = mapOf("PORT" to "8080", "FLAG" to "true")
        )
      )
    )

    val text = settingsFile().readText()
    assertTrue("\"8080\"" in text)
    assertTrue("\"true\"" in text)
    val servers = ProviderConfigFile.readMcpServers()
    assertEquals(listOf("node", "server.js", "8080"), servers.single().command)
    assertEquals("8080", servers.single().env["PORT"])
    assertNull(servers.single().workingDir)
  }

  @Test
  fun `writeMcpServers preserves other top-level keys and unknown entry keys`() {
    settingsFile().writeText(
      """
      {
        "server.port": 8765,
        "llm.model": "local-model",
        "mcpServers": [
          { "name": "fs", "command": ["npx"], "extra": 1 }
        ]
      }
      """.trimIndent()
    )

    ProviderConfigFile.writeMcpServers(
      listOf(
        ProviderConfigFile.McpServerEntry(
          name = "renamed",
          command = listOf("npx", "-y"),
          workingDir = "/tmp",
          env = emptyMap()
        )
      )
    )

    val servers = ProviderConfigFile.readMcpServers()
    assertEquals("renamed", servers.single().name)
    assertEquals(listOf("npx", "-y"), servers.single().command)
    val root = kotlinx.serialization.json.Json.parseToJsonElement(settingsFile().readText())
      .let { it as kotlinx.serialization.json.JsonObject }
    assertEquals(8765, (root["server.port"] as kotlinx.serialization.json.JsonPrimitive).content.toInt())
    assertEquals("local-model", (root["llm.model"] as kotlinx.serialization.json.JsonPrimitive).content)
    val entry = (root["mcpServers"] as kotlinx.serialization.json.JsonArray)
      .single() as kotlinx.serialization.json.JsonObject
    assertEquals(1, (entry["extra"] as kotlinx.serialization.json.JsonPrimitive).content.toInt())
  }

  @Test
  fun `clearAll removes mcpServers and provider keys but keeps the rest`() {
    settingsFile().writeText(
      """
      {
        "server.port": 8765,
        "ollama.baseUrl": "http://localhost:11434",
        "mcpServers": [ { "name": "fs", "command": ["npx"] } ]
      }
      """.trimIndent()
    )

    ProviderConfigFile.clearAll()

    assertTrue(ProviderConfigFile.readMcpServers().isEmpty())
    assertTrue(ProviderConfigFile.loadProperties().isEmpty())
    val root = kotlinx.serialization.json.Json.parseToJsonElement(settingsFile().readText())
      .let { it as kotlinx.serialization.json.JsonObject }
    assertEquals(1, root.size)
    assertEquals("8765", (root["server.port"] as kotlinx.serialization.json.JsonPrimitive).content)
  }

  @Test
  fun `readMcpServers tolerates a hand-written string command`() {
    settingsFile().writeText(
      """{ "mcpServers": [ { "name": "fs", "command": "npx -y pkg" } ] }"""
    )

    val servers = ProviderConfigFile.readMcpServers()
    assertEquals(listOf("npx", "-y", "pkg"), servers.single().command)
  }
}
