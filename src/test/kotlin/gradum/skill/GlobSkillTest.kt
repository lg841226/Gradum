/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GlobSkillTest.kt  2026-08-25 19:05:02 Changed by gwy
 */

package gradum.skill

import gradum.Provider
import gradum.SkillResult
import gradum.ToolMode
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class GlobSkillTest {

  private lateinit var projectRoot: File
  private lateinit var skill: GlobSkill

  @BeforeTest
  fun setUp() {
    projectRoot = Files.createTempDirectory("gradum_glob_test_").toFile()
    skill = GlobSkill()

    fun write(relative: String, body: String) {
      val file = File(projectRoot, relative)
      file.parentFile?.mkdirs()
      file.writeText(body)
    }

    write(relative = "src/main/java/com/example/Main.java", body = "class Main {}\n")
    write(relative = "src/main/java/com/example/Helper.java", body = "class Helper {}\n")
    write(relative = "src/test/java/com/example/MainTest.java", body = "class MainTest {}\n")
    write(relative = "README.md", body = "# readme\n")
    write(relative = "build.gradle.kts", body = "plugins {}\n")
  }

  @AfterTest
  fun tearDown() {
    projectRoot.deleteRecursively()
  }

  @Suppress("UNCHECKED_CAST")
  private fun functionOf(schema: Map<String, Any>): Map<String, Any> =
    schema["function"] as Map<String, Any>

  @Suppress("UNCHECKED_CAST")
  private fun paramsOf(schema: Map<String, Any>): Map<String, Any> =
    functionOf(schema)["parameters"] as Map<String, Any>

  private fun ctx(): SkillContext = SkillContext(
    toolMode = ToolMode.AGENT,
    projectRoot = projectRoot.absolutePath,
    provider = Provider.OLLAMA,
    modelName = "gpt-4o",
  )

  @Test
  fun `schema function name matches skillName`() {
    assertEquals(
      expected = "glob",
      actual = functionOf(schema = skill.getSchema(context = ctx()))["name"],
    )
  }

  @Test
  fun `schema required contains pattern`() {
    @Suppress("UNCHECKED_CAST")
    val required: List<String> = paramsOf(
      schema = skill.getSchema(context = ctx())
    )["required"] as List<String>
    assertTrue(
      "pattern" in required,
      "required must include 'pattern'"
    )
  }

  @Test
  fun `schema properties contain pattern and path`() {
    @Suppress("UNCHECKED_CAST")
    val props: Map<String, Any> = paramsOf(
      schema = skill.getSchema(context = ctx())
    )["properties"] as Map<String, Any>
    assertTrue("pattern" in props, "properties must contain 'pattern'")
    assertTrue("path" in props, "properties must contain 'path'")
  }

  @Test
  fun `empty pattern returns INVALID_PARAMETER`() {
    val result: SkillResult = skill.execute(
      arguments = mapOf("pattern" to ""),
      context = ctx(),
    )
    assertTrue(result is SkillResult.Failure, "empty pattern should fail")
    assertEquals(
      expected = "INVALID_PARAMETER",
      actual = result.code,
    )
  }

  @Test
  fun `blank pattern returns INVALID_PARAMETER`() {
    val result: SkillResult = skill.execute(
      arguments = mapOf("pattern" to "   "),
      context = ctx(),
    )
    assertTrue(
      result is SkillResult.Failure,
      "blank pattern should fail"
    )
    assertEquals(
      actual = result.code,
      expected = "INVALID_PARAMETER"
    )
  }

  @Test
  fun `missing pattern returns INVALID_PARAMETER`() {
    val result: SkillResult = skill.execute(
      arguments = emptyMap(),
      context = ctx(),
    )
    assertTrue(
      result is SkillResult.Failure,
      "missing pattern should fail"
    )
    assertEquals(
      actual = result.code,
      expected = "INVALID_PARAMETER"
    )
  }

  @Test
  fun `skill name and alias`() {
    assertEquals(
      expected = "glob",
      actual = skill.skillName
    )
    assertEquals(
      expected = "Glob",
      actual = skill.alias
    )
  }

  @Test
  fun `glob is available in all tool modes`() {
    val modes: Set<ToolMode> = skill.allowedToolModes
    assertTrue(
      ToolMode.AGENT in modes,
      "AGENT must be allowed"
    )
    assertTrue(
      ToolMode.EDIT in modes,
      "EDIT must be allowed"
    )
    assertTrue(
      ToolMode.READ_ONLY in modes,
      "READ_ONLY must be allowed"
    )
  }

  @Test
  fun `schema only requires pattern`() {
    @Suppress("UNCHECKED_CAST")
    val required: List<String> = paramsOf(
      schema = skill.getSchema(context = ctx())
    )["required"] as List<String>
    assertEquals(
      expected = listOf("pattern"),
      actual = required,
    )
  }

  @Test
  fun `glob dot-md matches README only`() {
    val result: SkillResult = skill.execute(
      arguments = mapOf("pattern" to "*.md"),
      context = ctx(),
    )
    val payload: Map<String, Any> = assertIs<SkillResult.Success>(value = result).data

    @Suppress("UNCHECKED_CAST")
    val files: List<String> = payload["files"] as List<String>
    assertTrue(
      files.isNotEmpty(),
      "*.md must match at least README.md"
    )
    assertTrue(
      files.all { it.endsWith(".md") },
      "all matched files must end with .md, got: $files"
    )
  }

  @Test
  fun `glob double-star slash dot-java matches nested Java files`() {
    val result: SkillResult = skill.execute(
      arguments = mapOf("pattern" to "**/*.java"),
      context = ctx(),
    )
    val payload: Map<String, Any> = assertIs<SkillResult.Success>(value = result).data

    @Suppress("UNCHECKED_CAST")
    val files: List<String> = payload["files"] as List<String>
    assertTrue(
      files.size >= 3,
      "**/*.java must match at least 3 Java files, got: $files"
    )
    assertTrue(
      files.all { it.endsWith(".java") },
      "all matched files must end with .java, got: $files"
    )
  }

  @Test
  fun `nonexistent path returns FILE_NOT_FOUND`() {
    val result: SkillResult = skill.execute(
      arguments = mapOf("pattern" to "*.txt", "path" to "nonexistent_dir"),
      context = ctx(),
    )
    assertTrue(
      result is SkillResult.Failure,
      "nonexistent path should fail"
    )
    assertEquals(
      expected = "FILE_NOT_FOUND",
      actual = result.code,
    )
  }
}
