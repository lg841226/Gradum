package gradum

import gradum.mode.ModeRegistry
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks the wire format that the plugin sends for the tool mode. The plugin
 * emits `"read_only"` / `"edit"` / `"agent"` / `"yolo"` (lowercase), see
 * PermissionMode, and [ModeRegistry.resolveId]
 * must translate those to the matching configured mode ids. If this
 * translation ever regresses to be case-sensitive or to reject lowercase
 * values, the read-only mode is silently treated as agent and the security
 * gate in `Agent.executeSingleTool` becomes a no-op. The default fallback
 * (unknown -> agent) is also pinned because it is the silent-success path
 * that previously masked the bug.
 */
class AgentConfigurationTest {
  private var previousConfigDir: String? = null

  @BeforeTest
  fun isolateConfigDir() {
    previousConfigDir = System.getProperty("gradum.server.configDir")
    System.setProperty(
      "gradum.server.configDir",
      Files.createTempDirectory("gradum-agent-config-").toAbsolutePath().toString()
    )
    ModeRegistry.reload()
  }

  @AfterTest
  fun restoreConfigDir() {
    if (previousConfigDir == null) System.clearProperty("gradum.server.configDir")
    else System.setProperty("gradum.server.configDir", previousConfigDir!!)
    ModeRegistry.reload()
  }

  @Test
  fun `read_only wire value resolves to read_only`() {
    assertEquals(
      "read_only",
      ModeRegistry.resolveId(rawValue = "read_only")
    )
  }

  @Test
  fun `edit wire value resolves to edit`() {
    assertEquals(
      "edit",
      ModeRegistry.resolveId(rawValue = "edit")
    )
  }

  @Test
  fun `agent wire value resolves to agent`() {
    assertEquals(
      "agent",
      ModeRegistry.resolveId(rawValue = "agent")
    )
  }

  @Test
  fun `yolo wire value resolves to yolo`() {
    assertEquals(
      "yolo",
      ModeRegistry.resolveId(rawValue = "yolo")
    )
    assertEquals(
      "yolo",
      ModeRegistry.resolveId(rawValue = "YOLO")
    )
  }

  @Test
  fun `uppercase wire value also resolves (case insensitive)`() {
    assertEquals(
      "read_only",
      ModeRegistry.resolveId(rawValue = "READ_ONLY")
    )
    assertEquals(
      "edit",
      ModeRegistry.resolveId(rawValue = "EDIT")
    )
  }

  @Test
  fun `old wire values still resolve via aliases`() {
    assertEquals(
      "agent",
      ModeRegistry.resolveId(rawValue = "write")
    )
    assertEquals(
      "edit",
      ModeRegistry.resolveId(rawValue = "single_step")
    )
  }

  @Test
  fun `unknown wire value falls back to agent`() {
    assertEquals(
      "agent",
      ModeRegistry.resolveId(rawValue = "garbage")
    )
    assertEquals(
      "agent",
      ModeRegistry.resolveId(rawValue = "Read-only Permissions")
    )
    assertEquals(
      "agent",
      ModeRegistry.resolveId(rawValue = "只读权限")
    )
  }

  @Test
  fun `null and blank wire value falls back to agent`() {
    assertEquals(
      "agent",
      ModeRegistry.resolveId(rawValue = null)
    )
    assertEquals(
      "agent",
      ModeRegistry.resolveId(rawValue = "")
    )
    assertEquals(
      "agent",
      ModeRegistry.resolveId(rawValue = "   ")
    )
  }

  @Test
  fun `lmstudio is a first-class provider on the OpenAI-compatible path`() {
    assertEquals(
      Provider.LMSTUDIO,
      Provider.fromStringOrDefault(rawValue = "lmstudio")
    )
    assertEquals(
      Provider.LMSTUDIO,
      Provider.fromStringOrDefault(rawValue = "LMSTUDIO")
    )
    assertEquals(
      "lmstudio",
      Provider.LMSTUDIO.wireType
    )
    assertEquals(
      PromptVariant.LOCAL,
      PromptVariant.resolveAuto(provider = Provider.LMSTUDIO),
      "LM Studio runs locally: it must use the LOCAL prompt variant"
    )
  }
}
