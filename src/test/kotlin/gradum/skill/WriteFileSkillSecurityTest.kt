/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * WriteFileSkillSecurityTest.kt  2026-08-31 19:21:55 Changed by gwy
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
import kotlin.test.junit5.JUnit5Asserter.assertTrue

/**
 * Security boundary tests for [WriteFileSkill].
 *
 * Mirrors [ReadFileSkillSecurityTest] — the same attack surface
 * (absolute system paths, parent traversal, prefix-collision
 * siblings) applies to write_file with strictly worse blast radius
 * (the LLM can corrupt or replace the targeted file). The check
 * is shared via [resolveProjectPath] and applied at the same
 * point in the execute path, so a regression in one skill would
 * surface in the other's test as well.
 */
class WriteFileSkillSecurityTest {

  private lateinit var projectRoot: File
  private lateinit var skill: WriteFileSkill

  @BeforeEach
  fun setUp() {
    // Under /tmp (ProtectedPaths.safePathPrefixes) so whole-file writes pass
    // the protected-path guard; java.io.tmpdir (/var/folders) is deliberately blocked.
    projectRoot = File("/tmp", "gradum-edit-security-test-${System.nanoTime()}")
    projectRoot.mkdirs()
    File(projectRoot, "editable.txt").writeText("old content\n")
    skill = WriteFileSkill()
  }

  @AfterEach
  fun tearDown() {
    projectRoot.deleteRecursively()
  }

  private fun context(): SkillContext = SkillContext(
    toolMode = ToolMode.EDIT,
    projectRoot = projectRoot.absolutePath,
    modelName = "qwen2.5:7b"
  )

  @Test
  fun `write_file rejects absolute system path on the local schema`() {
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
  fun `write_file rejects parent traversal on the local schema`() {
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
  fun `write_file accepts in-project edit on the local schema`() {
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
  fun `write_file rejects absolute system path on the cloud schema`() {
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
        modelName = "gpt-4o",
        provider = Provider.OPENAI
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
  fun `write_file rejects home dot ssh via cloud schema`() {
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
        modelName = "gpt-4o",
        provider = Provider.OPENAI
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
  fun `write_file with blank projectRoot rejects every path`() {
    val result = skill.execute(
      mapOf(
        "path" to "editable.txt",
        "oldString" to "old",
        "newString" to "new"
      ),
      SkillContext(
        toolMode = ToolMode.EDIT,
        projectRoot = "",
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

  @Test
  fun `write_file creates a new file when oldString is omitted on the local schema`() {
    val result = skill.execute(
      mapOf(
        "path" to "fresh.txt",
        "newString" to "brand new content\n"
      ),
      context()
    )
    assertTrue("creation should succeed: $result", result is SkillResult.Success)
    assertEquals(
      "brand new content\n",
      File(projectRoot, "fresh.txt").readText()
    )
  }

  @Test
  fun `write_file overwrites an existing file when oldString is omitted on the local schema`() {
    val result = skill.execute(
      mapOf(
        "path" to "editable.txt",
        "newString" to "fully replaced\n"
      ),
      context()
    )
    assertTrue("overwrite should succeed: $result", result is SkillResult.Success)
    assertEquals(
      "fully replaced\n",
      File(projectRoot, "editable.txt").readText()
    )
  }

  @Test
  fun `write_file creates a new file via blank oldString on the cloud schema`() {
    val result = skill.execute(
      mapOf(
        "path" to "fresh.txt",
        "edits" to listOf(mapOf("newString" to "cloud content\n"))
      ),
      SkillContext(
        toolMode = ToolMode.EDIT,
        projectRoot = projectRoot.absolutePath,
        modelName = "gpt-4o",
        provider = Provider.OPENAI
      )
    )
    assertTrue("creation should succeed: $result", result is SkillResult.Success)
    assertEquals(
      "cloud content\n",
      File(projectRoot, "fresh.txt").readText()
    )
  }

  @Test
  fun `write_file rejects call with both oldString and newString blank`() {
    val result = skill.execute(
      mapOf(
        "path" to "fresh.txt",
        "oldString" to "",
        "newString" to ""
      ),
      context()
    )
    assertTrue(result is SkillResult.Failure)
    result as SkillResult.Failure
    assertEquals(
      ErrorCode.INVALID_PARAMETER.code,
      result.code
    )
  }
}
