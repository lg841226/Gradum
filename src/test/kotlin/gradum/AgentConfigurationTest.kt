package gradum

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks the wire format that the plugin sends for [ToolMode]. The plugin
 * emits `"read_only"` / `"edit"` / `"agent"` / `"yolo"` (lowercase),
 * see [gradum.idea.chat.ui.input.PermissionMode], and the server's
 * [ToolMode.fromStringOrDefault] must translate those to the actual
 * enum constants. If this translation ever regresses to be case-sensitive
 * or to reject lowercase values, the read-only mode is silently treated
 * as agent and the security gate in `Agent.executeSingleTool` becomes a
 * no-op. The default fallback (unknown -> AGENT) is also pinned because
 * it is the silent-success path that previously masked the bug.
 */
class AgentConfigurationTest {

  @Test
  fun `read_only wire value resolves to READ_ONLY`() {
    assertEquals(
      ToolMode.READ_ONLY,
      ToolMode.fromStringOrDefault(rawValue = "read_only")
    )
  }

  @Test
  fun `edit wire value resolves to EDIT`() {
    assertEquals(
      ToolMode.EDIT,
      ToolMode.fromStringOrDefault(rawValue = "edit")
    )
  }

  @Test
  fun `agent wire value resolves to AGENT`() {
    assertEquals(
      ToolMode.AGENT,
      ToolMode.fromStringOrDefault(rawValue = "agent")
    )
  }

  @Test
  fun `yolo wire value resolves to YOLO`() {
    // Parsed by enum name, same as the other three: the plugin sends
    // PermissionMode.YOLO = "yolo" and it must round-trip unchanged. A
    // fallback to AGENT here would silently re-enable the permission gates.
    assertEquals(
      ToolMode.YOLO,
      ToolMode.fromStringOrDefault(rawValue = "yolo")
    )
    assertEquals(
      ToolMode.YOLO,
      ToolMode.fromStringOrDefault(rawValue = "YOLO")
    )
  }

  @Test
  fun `uppercase wire value also resolves (case insensitive)`() {
    assertEquals(
      ToolMode.READ_ONLY,
      ToolMode.fromStringOrDefault(rawValue = "READ_ONLY")
    )
    assertEquals(
      ToolMode.EDIT,
      ToolMode.fromStringOrDefault(rawValue = "EDIT")
    )
  }

  @Test
  fun `old wire values still resolve via aliases`() {
    assertEquals(
      ToolMode.AGENT,
      ToolMode.fromStringOrDefault(rawValue = "write")
    )
    assertEquals(
      ToolMode.EDIT,
      ToolMode.fromStringOrDefault(rawValue = "single_step")
    )
  }

  @Test
  fun `unknown wire value falls back to AGENT`() {
    assertEquals(
      ToolMode.AGENT,
      ToolMode.fromStringOrDefault(rawValue = "garbage")
    )
    assertEquals(
      ToolMode.AGENT,
      ToolMode.fromStringOrDefault(rawValue = "Read-only Permissions")
    )
    assertEquals(
      ToolMode.AGENT,
      ToolMode.fromStringOrDefault(rawValue = "只读权限")
    )
  }

  @Test
  fun `null and blank wire value falls back to AGENT`() {
    assertEquals(
      ToolMode.AGENT,
      ToolMode.fromStringOrDefault(rawValue = null)
    )
    assertEquals(
      ToolMode.AGENT,
      ToolMode.fromStringOrDefault(rawValue = "")
    )
    assertEquals(
      ToolMode.AGENT,
      ToolMode.fromStringOrDefault(rawValue = "   ")
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
