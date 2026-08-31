/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * GrepSkillTest.kt  2026-08-31 19:21:55 Changed by gwy
 */

package gradum.skill

import gradum.SkillResult
import gradum.ToolMode
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class GrepSkillTest {

  private lateinit var projectRoot: File
  private lateinit var skill: GrepSkill

  @BeforeTest
  fun setUp() {
    projectRoot = Files.createTempDirectory("gradum_grep_test_").toFile()
    skill = GrepSkill()

    fun write(relative: String, body: String) {
      val file = File(projectRoot, relative)
      file.parentFile?.mkdirs()
      file.writeText(body)
    }

    write(
      relative = "src/main/java/com/example/Main.java",
      body = "public class Main {\n  public static void main(String[] args) {\n    System.out.println(\"hello\");\n  }\n}\n"
    )
    write(
      relative = "src/main/java/com/example/Helper.java",
      body = "class Helper {\n  String name;\n}\n"
    )
    write(
      relative = "src/test/java/com/example/MainTest.java",
      body = "class MainTest {\n  void testMain() {}\n}\n"
    )
    write(
      relative = "README.md",
      body = "# readme\nThis is a project.\n"
    )
    write(
      relative = "config.properties",
      body = "key=value\nfoo=bar\n"
    )
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
    modelName = "gpt-4o",
  )

  @Test
  fun `schema function name matches skillName`() {
    assertEquals(
      expected = "grep",
      actual = functionOf(
        schema = skill.getSchema(context = ctx())
      )["name"],
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
  fun `schema properties contain pattern path include`() {
    @Suppress("UNCHECKED_CAST")
    val props: Map<String, Any> = paramsOf(
      schema = skill.getSchema(context = ctx())
    )["properties"] as Map<String, Any>
    assertTrue(
      "pattern" in props,
      "properties must contain 'pattern'"
    )
    assertTrue(
      "path" in props,
      "properties must contain 'path'"
    )
    assertTrue(
      "include" in props,
      "properties must contain 'include'"
    )
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
  fun `short pattern returns INVALID_PARAMETER`() {
    val result: SkillResult = skill.execute(
      arguments = mapOf("pattern" to "ab"),
      context = ctx(),
    )
    assertTrue(
      result is SkillResult.Failure,
      "2-char pattern should fail"
    )
    assertEquals(
      expected = "INVALID_PARAMETER",
      actual = result.code,
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
      expected = "INVALID_PARAMETER",
      actual = result.code,
    )
  }

  @Test
  fun `skill name and alias`() {
    assertEquals(
      expected = "grep",
      actual = skill.skillName,
    )
    assertEquals(
      expected = "Grep",
      actual = skill.alias,
    )
  }

  @Test
  fun `grep is available in all tool modes`() {
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
  fun `grep matches across multiple files`() {
    val result: SkillResult = skill.execute(
      arguments = mapOf("pattern" to "class"),
      context = ctx(),
    )
    val payload: Map<String, Any> = assertIs<SkillResult.Success>(value = result).data
    assertTrue(
      (payload["total_matches"] as Int) >= 3,
      "'class' must appear in at least 3 files"
    )
    @Suppress("UNCHECKED_CAST")
    val matches: List<Map<String, Any>> = payload["matches"] as List<Map<String, Any>>
    assertTrue(
      matches.isNotEmpty(),
      "matches must not be empty"
    )
    matches.forEach { match ->
      assertNotNull(
        match["file"],
        "each match must have a 'file' key"
      )
      assertNotNull(
        match["line"],
        "each match must have a 'line' key"
      )
      assertNotNull(
        match["content"],
        "each match must have a 'content' key"
      )
    }
  }

  @Test
  fun `grep with include filter restricts file types`() {
    val result: SkillResult = skill.execute(
      arguments = mapOf("pattern" to "class", "include" to "*.java"),
      context = ctx(),
    )
    val payload: Map<String, Any> = assertIs<SkillResult.Success>(value = result).data

    @Suppress("UNCHECKED_CAST")
    val matches: List<Map<String, Any>> = payload["matches"] as List<Map<String, Any>>
    assertTrue(
      matches.isNotEmpty(),
      "*.java must have matches"
    )
    matches.forEach { match ->
      val filePath: String = match["file"] as String
      assertTrue(
        filePath.endsWith(suffix = ".java"),
        "include filter must restrict to .java files, got: $filePath"
      )
    }
  }

  @Test
  fun `nonexistent path returns FILE_NOT_FOUND`() {
    val result: SkillResult = skill.execute(
      arguments = mapOf("pattern" to "class", "path" to "nonexistent_dir"),
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
