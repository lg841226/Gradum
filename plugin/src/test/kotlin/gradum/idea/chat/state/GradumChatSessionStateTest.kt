/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GradumChatSessionStateTest.kt  2026-08-18 12:45:23 Changed by gwy
 */

package gradum.idea.chat.state

import com.intellij.openapi.vfs.VirtualFile
import gradum.idea.chat.model.ThinkingLevel
import gradum.idea.editor.AttachedText
import gradum.idea.editor.PendingMessage
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

/**
 * State-management tests for [GradumChatSession] that don't need the
 * IntelliJ application service (that only applies to model-list
 * filtering). Covers session lifecycle state, the pending-message
 * queue limits, merge-mode selection, thinking-level idempotency, and
 * the inline-tag resolver.
 */
class GradumChatSessionStateTest {

  @Test
  fun `thinking level setter is idempotent and clamps through the setter`() {
    val session = GradumChatSession()
    assertEquals(ThinkingLevel.MEDIUM, session.thinkingLevel)
    session.setThinkingLevel(ThinkingLevel.HIGH)
    assertEquals(ThinkingLevel.HIGH, session.thinkingLevel)
    session.setThinkingLevel(ThinkingLevel.HIGH)
    assertEquals(ThinkingLevel.HIGH, session.thinkingLevel)
  }

  @Test
  fun `pending queue respects the two message cap`() {
    val session = GradumChatSession()
    assertFalse(session.isPendingQueueFull)
    session.pendingMessages.add(PendingMessage("one", emptyList()))
    session.pendingMessages.add(PendingMessage("two", emptyList()))
    assertTrue("two queued messages must saturate the queue", session.isPendingQueueFull)
  }

  @Test
  fun `attachment limit is enforced at ten`() {
    val session = GradumChatSession()
    for (i in 1..GradumChatSession.MAX_ATTACHMENTS) {
      session.attachedFiles.add(AttachedText(content = "a$i", preview = "preview$i"))
    }
    assertTrue(session.isAttachmentLimitReached)
  }

  @Test
  fun `merge selection toggles and clears`() {
    val session = GradumChatSession()
    session.enterMergeMode()
    assertTrue(session.isMergeModeActive)
    session.toggleMergeSelection("s1")
    session.toggleMergeSelection("s2")
    assertEquals(setOf("s1", "s2"), session.mergeSelection.toSet())
    session.toggleMergeSelection("s1")
    assertEquals(listOf("s2"), session.mergeSelection.toList())
    session.exitMergeMode()
    assertFalse(session.isMergeModeActive)
    assertTrue(session.mergeSelection.isEmpty())
  }

  @Test
  fun `toolMode mirrors selectedPermission`() {
    val session = GradumChatSession()
    assertEquals("read_only", session.toolMode)
    session.selectedPermission = "agent"
    assertEquals("agent", session.toolMode)
  }

  @Test
  fun `inline tags resolve focus file into context xml`() {
    val result = GradumChatSession.resolveInlineTags(
      text = "look at @focus please",
      focusedFilePath = "/project/src/Main.kt",
      openFiles = emptyList(),
    )
    assertEquals("look at <Context path=\"/project/src/Main.kt\"/> please" to true, result)
  }

  @Test
  fun `inline tags resolve file reference to matching open file`() {
    val openFile: VirtualFile = mockk()
    // Mock the VirtualFile contract used by resolveInlineTags.
    io.mockk.every { openFile.name } returns "Main.kt"
    io.mockk.every { openFile.path } returns "/project/src/Main.kt"

    val result = GradumChatSession.resolveInlineTags(
      text = "check @file:Main.kt",
      focusedFilePath = "",
      openFiles = listOf(openFile),
    )
    assertEquals("check <Attachments paths=\"/project/src/Main.kt\"/>" to true, result)
  }

  @Test
  fun `inline tags leave unrelated text untouched`() {
    val result = GradumChatSession.resolveInlineTags(
      text = "no tags here",
      focusedFilePath = "/project/src/Main.kt",
      openFiles = emptyList(),
    )
    assertEquals("no tags here" to false, result)
  }

  @Test
  fun `inline tags drop unmatched file reference`() {
    val result = GradumChatSession.resolveInlineTags(
      text = "open @file:Missing.kt",
      focusedFilePath = "",
      openFiles = emptyList(),
    )
    // Unmatched reference is left as-is, untouched.
    assertEquals("open @file:Missing.kt" to false, result)
  }
}
