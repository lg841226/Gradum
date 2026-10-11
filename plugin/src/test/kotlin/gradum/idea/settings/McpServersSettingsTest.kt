package gradum.idea.settings

import gradum.idea.provider.ProviderConfigFile
import gradum.idea.provider.ProviderConfigFile.McpServerEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Behavior tests for the MCP tab panel's pure helpers: registry-import
 * tab naming, the settings.json env splice that keeps IDE-editor env edits
 * alive across panel writes, and the working-copy validation that decides
 * what reaches the file.
 */
class McpServersSettingsTest {

  @Test
  fun `uniqueMcpTabName appends a suffix until the name is free`() {
    assertEquals("filesystem", uniqueMcpTabName(base = "filesystem", existing = emptySet()))
    assertEquals("other", uniqueMcpTabName(base = "other", existing = setOf("filesystem")))
    assertEquals(
      "filesystem-2",
      uniqueMcpTabName(base = "filesystem", existing = setOf("filesystem"))
    )
    assertEquals(
      "filesystem-3",
      uniqueMcpTabName(base = "filesystem", existing = setOf("filesystem", "filesystem-2"))
    )
  }

  @Test
  fun `splice prefers provided env over the file and falls back to disk`() {
    val entry: McpServerEntry =
      McpServerEntry(name = "fs", command = listOf("npx"), workingDir = null)
    val fileEnvs: Map<String, Map<String, String>> =
      mapOf("fs" to mapOf("HOME" to "/tmp"), "other" to mapOf("X" to "1"))
    val providedEnvs: Map<String, Map<String, String>> =
      mapOf("fs" to mapOf("API_KEY" to "secret"))

    assertEquals(
      mapOf("API_KEY" to "secret"),
      spliceMcpEnvs(entry = entry, fileEnvs = fileEnvs, providedEnvs = providedEnvs).env
    )
    assertEquals(
      mapOf("HOME" to "/tmp"),
      spliceMcpEnvs(entry = entry, fileEnvs = fileEnvs, providedEnvs = emptyMap()).env
    )
    assertEquals(
      emptyMap<String, String>(),
      spliceMcpEnvs(entry = entry, fileEnvs = emptyMap(), providedEnvs = emptyMap()).env
    )
  }

  @Test
  fun `toEntryOrNull rejects blank names and empty commands and trims the rest`() {
    assertNull(
      McpServerItem(id = 0, name = "   ", commandText = "npx", workingDir = "").toEntryOrNull()
    )
    assertNull(
      McpServerItem(id = 0, name = "fs", commandText = "  ", workingDir = "").toEntryOrNull()
    )

    val entry: McpServerEntry = McpServerItem(
      id = 0,
      name = "  fs ",
      commandText = "  npx -y pkg  ",
      workingDir = "   "
    ).toEntryOrNull()!!
    assertEquals("fs", entry.name)
    assertEquals(listOf("npx", "-y", "pkg"), entry.command)
    assertNull(entry.workingDir)
    assertEquals(emptyMap<String, String>(), entry.env)
  }

  @Test
  fun `toItem round-trips the command back into the working copy`() {
    val entry: McpServerEntry = McpServerEntry(
      name = "fs",
      command = listOf("node", "-e", "print(\"x\")"),
      workingDir = null
    )

    val item: McpServerItem = entry.toItem(id = 7)

    assertEquals(7, item.id)
    assertEquals("fs", item.name)
    assertEquals("", item.workingDir)
    assertEquals(entry.command, ProviderConfigFile.parseCommandText(item.commandText))
  }
}
