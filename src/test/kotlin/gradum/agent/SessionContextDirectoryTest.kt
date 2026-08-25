/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SessionContextDirectoryTest.kt  2026-08-25 22:32:29 Changed by gwy
 */

package gradum.agent

import gradum.AgentConfiguration
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks the `.gradum/sessions/<sessionId>/` directory rule so the server
 * and the plugin can never drift apart (both sides must agree on where a
 * session's `context.json` / `conversation.md` live).
 */
class SessionContextDirectoryTest {

  private fun config(sessionId: String?): AgentConfiguration =
    AgentConfiguration(projectRoot = "/work/MyProject", sessionId = sessionId)

  @Test
  fun `session id scopes context into its own directory`() {
    val dir: Path = contextOutputDirectory(configuration = config(sessionId = "20260812-131500-a1b2"))
    assertEquals(
      Path.of("/work/MyProject/.gradum/sessions/20260812-131500-a1b2"),
      dir
    )
  }

  @Test
  fun `blank session id falls back to legacy context directory`() {
    val dir: Path = contextOutputDirectory(configuration = config(sessionId = ""))
    assertEquals(
      Path.of("/work/MyProject/.gradum"),
      dir
    )
  }

  @Test
  fun `whitespace session id falls back to legacy context directory`() {
    val dir: Path = contextOutputDirectory(configuration = config(sessionId = "   "))
    assertEquals(
      Path.of("/work/MyProject/.gradum"),
      dir
    )
  }

  @Test
  fun `null session id falls back to legacy context directory`() {
    val dir: Path = contextOutputDirectory(configuration = config(sessionId = null))
    assertEquals(
      Path.of("/work/MyProject/.gradum"),
      dir
    )
  }
}
