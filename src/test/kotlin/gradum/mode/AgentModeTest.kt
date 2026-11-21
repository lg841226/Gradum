package gradum.mode

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentModeTest {

  private val openMode = AgentMode(id = "open", label = "Open", description = "")

  @Test
  fun defaultSurfaceAllowsEverything() {
    assertTrue(openMode.allowsTool("read_file", ToolOrigin.BUILTIN))
    assertTrue(openMode.allowsTool("anything", ToolOrigin.EXTERNAL))
    assertTrue(openMode.allowsTool("anything", ToolOrigin.MCP))
  }

  @Test
  fun denyWinsOverAllow() {
    val mode = openMode.copy(allow = listOf("*"), deny = listOf("write_file"))
    assertFalse(mode.allowsTool("write_file"))
    assertTrue(mode.allowsTool("read_file"))
  }

  @Test
  fun allowListIsExclusive() {
    val mode = openMode.copy(allow = listOf("read_file", "grep"), deny = emptyList())
    assertTrue(mode.allowsTool("read_file"))
    assertTrue(mode.allowsTool("grep"))
    assertFalse(mode.allowsTool("run_cmd"))
  }

  @Test
  fun globMatchesSkillNamePatterns() {
    val mode = openMode.copy(allow = listOf("search_*"), deny = emptyList())
    assertTrue(mode.allowsTool("search_web"))
    assertFalse(mode.allowsTool("read_file"))
  }

  @Test
  fun mcpSelectorMatchesOnlyMcpOrigin() {
    val mode = openMode.copy(allow = listOf(AgentMode.SELECTOR_MCP), deny = emptyList())
    assertTrue(mode.allowsTool("whatever", ToolOrigin.MCP))
    assertFalse(mode.allowsTool("read_file", ToolOrigin.BUILTIN))
    assertFalse(mode.allowsTool("hello", ToolOrigin.EXTERNAL))
  }

  @Test
  fun externalSelectorMatchesOnlyExternalOrigin() {
    val mode = openMode.copy(allow = listOf(AgentMode.SELECTOR_ALL), deny = listOf(AgentMode.SELECTOR_EXTERNAL))
    assertFalse(mode.allowsTool("hello", ToolOrigin.EXTERNAL))
    assertTrue(mode.allowsTool("read_file", ToolOrigin.BUILTIN))
    assertTrue(mode.allowsTool("whatever", ToolOrigin.MCP))
  }
}
