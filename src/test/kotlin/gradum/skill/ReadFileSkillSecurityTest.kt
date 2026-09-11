/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ReadFileSkillSecurityTest.kt  2026-08-31 19:21:55 Changed by gwy
 */
package gradum.skill

import gradum.ErrorCode
import gradum.SkillResult
import gradum.ToolMode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.DefaultAsserter.assertTrue
import kotlin.test.Test

/**
 * Security boundary tests for [ReadFileSkill].
 *
 * Pinned behaviors:
 *  - `read_file("/etc/passwd")` and similar absolute escapes fail
 *    with `PERMISSION_DENIED` (not `FILE_NOT_FOUND` — that's an
 *    information leak about which files exist outside the project).
 *  - `read_file("~/.ssh/id_rsa")` likewise fails.
 *  - Sibling directories sharing a name prefix are not silently
 *    accepted as "inside the project".
 *  - In-project relative paths still work end-to-end.
 *
 * The LLM is treated as untrusted input — a prompt-injected search
 * result or a malicious context file can ask the skill to read
 * anywhere. The agent's first line of defense is the schema
 * description ("relative to project root"), but the second line —
 * the runtime check that runs regardless of what the schema let
 * through — is what these tests pin.
 */
class ReadFileSkillSecurityTest {

  private lateinit var projectRoot: File
  private lateinit var skill: ReadFileSkill

  @BeforeEach
  fun setUp() {
    projectRoot = Files.createTempDirectory("gradum-read-security-test").toFile()
    File(projectRoot, "in-project.txt").writeText("hello\nworld\n")
    skill = ReadFileSkill()
  }

  @AfterEach
  fun tearDown() {
    projectRoot.deleteRecursively()
  }

  private fun context(): SkillContext = SkillContext(
    toolMode = ToolMode.READ_ONLY,
    projectRoot = projectRoot.absolutePath,
    modelName = "qwen2.5:7b"
  )

  @Test
  fun `read_file rejects absolute system path with PERMISSION_DENIED`() {
    val result = skill.execute(
      mapOf("path" to "/etc/passwd"),
      context()
    )
    assertTrue("expected failure for absolute system path", result is SkillResult.Failure)
    result as SkillResult.Failure
    assertEquals(
      ErrorCode.PERMISSION_DENIED.code,
      result.code
    )
    assertTrue(
      "rejection message should not leak whether the file exists: ${result.message}",
      result.message.contains("outside the project root")
    )
  }

  @Test
  fun `read_file rejects home ssh directory`() {
    val result = skill.execute(
      mapOf("path" to "${System.getProperty("user.home")}/.ssh/id_rsa"),
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
  fun `read_file rejects sibling directory with shared prefix`() {
    // Resolver/CommandFilter treat absolute `/tmp` paths as safe scratch space,
    // and `java.io.tmpdir` is `/tmp` on Linux but `/var/folders/.../T` on macOS.
    // Building the project root and its evil sibling in the *working directory*
    // (never a safe prefix) exercises the boundary check identically on every OS.
    val scratch: Path = Files.createTempDirectory(
      Path.of(System.getProperty("user.dir")), "gradum-read-security-scratch-"
    )
    try {
      val trapRoot: File = Files.createTempDirectory(scratch, "proj").toFile()
      val evilRoot: File = Files.createTempDirectory(scratch, trapRoot.name + "-evil").toFile()
      val result = skill.execute(
        mapOf("path" to evilRoot.absolutePath),
        SkillContext(
          toolMode = ToolMode.READ_ONLY,
          projectRoot = trapRoot.absolutePath,
          modelName = "qwen2.5:7b"
        )
      )
      assertTrue(result is SkillResult.Failure)
      result as SkillResult.Failure
      assertEquals(
        ErrorCode.PERMISSION_DENIED.code,
        result.code
      )
    } finally {
      scratch.toFile().deleteRecursively()
    }
  }

  @Test
  fun `read_file accepts in-project relative path`() {
    val result = skill.execute(
      mapOf("path" to "in-project.txt"),
      context()
    )
    assertTrue("expected success for in-project file: $result", result is SkillResult.Success)
  }

  @Test
  fun `read_file rejects parent traversal`() {
    val result = skill.execute(
      mapOf("path" to "../escaped.txt"),
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
  fun `read_file with blank projectRoot rejects every path`() {
    val result = skill.execute(
      mapOf("path" to "in-project.txt"),
      SkillContext(
        toolMode = ToolMode.READ_ONLY,
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
  fun `read_file allows tmp scratch files via safe prefix`() {
    val tmpFile: java.nio.file.Path = java.nio.file.Paths.get("/tmp/gradum-scratch-test.txt")
    try {
      Files.writeString(tmpFile, "scratch content")
      val result = skill.execute(
        mapOf("path" to tmpFile.toAbsolutePath().toString()),
        context()
      )
      assertTrue(
        "tmp files should be allowed regardless of project root: $result",
        result is SkillResult.Success
      )
    } finally {
      Files.deleteIfExists(tmpFile)
    }
  }
}
