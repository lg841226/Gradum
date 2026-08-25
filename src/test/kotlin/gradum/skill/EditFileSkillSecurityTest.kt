/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * EditFileSkillSecurityTest.kt  2026-08-25 14:13:18 Changed by gwy
 */
package gradum.skill

import gradum.ErrorCode
import gradum.Provider
import gradum.SkillResult
import gradum.ToolMode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.junit5.JUnit5Asserter.assertTrue

/**
 * Security boundary tests for [EditFileSkill].
 *
 * Mirrors [ReadFileSkillSecurityTest] — the same attack surface
 * (absolute system paths, parent traversal, prefix-collision
 * siblings) applies to edit_file with strictly worse blast radius
 * (the LLM can corrupt or replace the targeted file). The check
 * is shared via [resolveProjectPath] and applied at the same
 * point in the execute path, so a regression in one skill would
 * surface in the other's test as well.
 */
class EditFileSkillSecurityTest {

  private lateinit var projectRoot: File
  private lateinit var skill: EditFileSkill

  @BeforeEach
  fun setUp() {
    projectRoot = Files.createTempDirectory("gradum-edit-security-test").toFile()
    File(projectRoot, "editable.txt").writeText("old content\n")
    skill = EditFileSkill()
  }

  @AfterEach
  fun tearDown() {
    projectRoot.deleteRecursively()
  }

  private fun context(): SkillContext = SkillContext(
    toolMode = ToolMode.EDIT,
    projectRoot = projectRoot.absolutePath,
    provider = Provider.OLLAMA,
    modelName = "qwen2.5:7b"
  )

  @Test
  fun `edit_file rejects absolute system path on the local schema`() {
    val result = skill.execute(
      mapOf(
        "path" to "/etc/hosts",
        "oldString" to "127.0.0.1",
        "newString" to "evil"
      ),
      context()
    )
    assertTrue(result is SkillResult.Failure)
    result as SkillResult.Failure
    assertEquals(
      ErrorCode.PERMISSION_DENIED.code,
      result.code
    )
  }

  @Test
  fun `edit_file rejects parent traversal on the local schema`() {
    val result = skill.execute(
      mapOf(
        "path" to "../escaped.txt",
        "oldString" to "x",
        "newString" to "y"
      ),
      context()
    )
    assertTrue(result is SkillResult.Failure)
    result as SkillResult.Failure
    assertEquals(
      ErrorCode.PERMISSION_DENIED.code,
      result.code
    )
  }

  @Test
  fun `edit_file accepts in-project edit on the local schema`() {
    val result = skill.execute(
      mapOf(
        "path" to "editable.txt",
        "oldString" to "old content",
        "newString" to "new content"
      ),
      context()
    )
    assertTrue("in-project edit should succeed: $result", result is SkillResult.Success)
    assertEquals(
      "new content\n",
      File(projectRoot, "editable.txt").readText()
    )
  }

  @Test
  fun `edit_file rejects absolute system path on the cloud schema`() {
    val result = skill.execute(
      mapOf(
        "path" to "/etc/hostname",
        "edits" to listOf(
          mapOf("oldString" to "old", "newString" to "new")
        )
      ),
      SkillContext(
        toolMode = ToolMode.EDIT,
        projectRoot = projectRoot.absolutePath,
        provider = Provider.OPENAI,
        modelName = "gpt-4o"
      )
    )
    assertTrue(result is SkillResult.Failure)
    result as SkillResult.Failure
    assertEquals(
      ErrorCode.PERMISSION_DENIED.code,
      result.code
    )
  }

  @Test
  fun `edit_file rejects home dot ssh via cloud schema`() {
    val result = skill.execute(
      mapOf(
        "path" to "${System.getProperty("user.home")}/.ssh/authorized_keys",
        "edits" to listOf(
          mapOf("oldString" to "ssh-rsa AAA", "newString" to "ssh-rsa evil")
        )
      ),
      SkillContext(
        toolMode = ToolMode.EDIT,
        projectRoot = projectRoot.absolutePath,
        provider = Provider.OPENAI,
        modelName = "gpt-4o"
      )
    )
    assertTrue(result is SkillResult.Failure)
    result as SkillResult.Failure
    assertEquals(
      ErrorCode.PERMISSION_DENIED.code,
      result.code
    )
  }

  @Test
  fun `edit_file with blank projectRoot rejects every path`() {
    val result = skill.execute(
      mapOf(
        "path" to "editable.txt",
        "oldString" to "old",
        "newString" to "new"
      ),
      SkillContext(
        toolMode = ToolMode.EDIT,
        projectRoot = "",
        provider = Provider.OLLAMA,
        modelName = "qwen2.5:7b"
      )
    )
    assertTrue(result is SkillResult.Failure)
    result as SkillResult.Failure
    assertEquals(
      ErrorCode.PERMISSION_DENIED.code,
      result.code
    )
  }
}
