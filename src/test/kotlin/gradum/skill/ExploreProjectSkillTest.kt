/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ExploreProjectSkillTest.kt  2026-08-25 21:48:28 Changed by gwy
 */

package gradum.skill

import gradum.SkillResult
import gradum.ToolMode
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/**
 * Pins [ExploreProjectSkill] behavior the model depends on:
 *
 *  1. Paths in `code_files` / `config_files` / `other_files` are
 *     project-root-relative — never bare basenames.
 *  2. The three lists share the same shape (`{path, lines}`) so the
 *     model can pass any entry straight into `read_file` /
 *     `edit_file` without a type switch.
 *  3. `project_root` is always returned so the model can compose
 *     full paths if it needs to.
 *  4. Every returned relative path resolves to a real file on disk
 *     when joined with `project_root`.
 *
 * Uses the FULL schema path (qwen2.5:7b resolves to SIMPLE, so we
 * pick a cloud-sized model name to keep `isSimpleModel` false).
 */
class ExploreProjectSkillTest {

  private lateinit var projectRoot: File
  private lateinit var skill: ExploreProjectSkill

  @BeforeTest
  fun setUp() {
    projectRoot = Files.createTempDirectory("gradum_explore_test_").toFile()
    skill = ExploreProjectSkill()

    fun write(relative: String, body: String) {
      val file = File(projectRoot, relative)
      file.parentFile?.mkdirs()
      file.writeText(body)
    }

    // Mimics a Maven layout: files live under a submodule.
    write(relative = "reading-notes/src/main/java/com/example/readingnotes/Main.java", body = "class Main {}\n")
    write(relative = "reading-notes/src/main/java/com/example/readingnotes/Note.java", body = "class Note {}\n")
    write(relative = "reading-notes/pom.xml", body = "<project/>\n")
    write(relative = "README.md", body = "# readme\n")
    write(relative = "notes", body = "plain notes content\n")
  }

  @AfterTest
  fun tearDown() {
    projectRoot.deleteRecursively()
  }

  private fun runSkill(): SkillResult {
    val context = SkillContext(
      toolMode = ToolMode.AGENT,
      projectRoot = projectRoot.absolutePath,
      modelName = "gpt-4o",
    )
    return skill.execute(arguments = mapOf("depth" to 8), context)
  }

  private fun assertSuccess(result: SkillResult): Map<String, Any> {
    val success: SkillResult.Success = assertIs<SkillResult.Success>(value = result)
    return success.data
  }

  private fun pathsFor(listValue: Any?): List<String> {
    @Suppress("UNCHECKED_CAST")
    val entries: List<Map<String, Any>> = listValue as? List<Map<String, Any>> ?: return emptyList()
    return entries.mapNotNull { it["path"] as? String }
  }

  @Test
  fun `code files carry project-root-relative paths, not bare basenames`() {
    val payload: Map<String, Any> = assertSuccess(runSkill())
    val codePaths: List<String> = pathsFor(listValue = payload["code_files"])

    assertTrue(
      codePaths.isNotEmpty(),
      "code_files must not be empty for the fixture project"
    )
    codePaths.forEach { filePath ->
      assertFalse(
        filePath.startsWith(char = '/'),
        "code_files path '$filePath' must be project-relative, not absolute"
      )
    }

    val mainPath: String = codePaths.firstOrNull { it.endsWith(suffix = "Main.java") }
      ?: error("code_files must contain Main.java, got: $codePaths")
    assertTrue(
      mainPath.contains(char = '/'),
      "Main.java path '$mainPath' must include its directory segments"
    )
    assertTrue(
      mainPath.contains(other = "reading-notes"),
      "Main.java path '$mainPath' must include the 'reading-notes' module segment"
    )
  }

  @Test
  fun `config files share the same shape as code files`() {
    val payload: Map<String, Any> = assertSuccess(runSkill())
    val configEntries: List<Map<String, Any>> = @Suppress("UNCHECKED_CAST")
    (payload["config_files"] as? List<Map<String, Any>> ?: emptyList())

    assertTrue(
      configEntries.isNotEmpty(),
      "config_files must include pom.xml / README.md for the fixture"
    )
    configEntries.forEach { entry ->
      val path: String = entry["path"] as? String ?: ""
      assertTrue(
        path.isNotBlank(),
        "config_files entry missing 'path': $entry"
      )
      assertTrue(
        path.contains(char = '/') || !path.contains(char = '.'),
        "config_files path '$path' looks like a bare basename"
      )
    }
  }

  @Test
  fun `other files share the same shape as code files`() {
    val payload: Map<String, Any> = assertSuccess(runSkill())
    val otherEntries: List<Map<String, Any>> = @Suppress("UNCHECKED_CAST")
    (payload["other_files"] as? List<Map<String, Any>> ?: emptyList())

    assertTrue(
      otherEntries.isNotEmpty(),
      "other_files must include 'notes' for the fixture"
    )
    otherEntries.forEach { entry ->
      assertNotNull(
        entry["path"],
        "other_files entry must have a 'path' key, got: $entry"
      )
    }
  }

  @Test
  fun `result includes project_root for the model to reference`() {
    val payload: Map<String, Any> = assertSuccess(runSkill())
    val projectRootValue: String = payload["project_root"] as? String ?: ""
    assertEquals(
      projectRoot.absolutePath,
      projectRootValue,
      "project_root must equal the SkillContext's projectRoot"
    )
  }

  @Test
  fun `paths returned by explore_project resolve under project_root`() {
    val payload: Map<String, Any> = assertSuccess(runSkill())
    val projectRootPath: String = payload["project_root"] as? String ?: return

    @Suppress("UNCHECKED_CAST")
    val allEntries: List<List<Map<String, Any>>> = listOf(
      payload["code_files"] as? List<Map<String, Any>> ?: emptyList(),
      payload["config_files"] as? List<Map<String, Any>> ?: emptyList(),
      payload["other_files"] as? List<Map<String, Any>> ?: emptyList(),
    )
    val allPaths: List<String> = allEntries.flatten().mapNotNull { it["path"] as? String }

    assertTrue(
      allPaths.isNotEmpty(),
      "fixture project must produce at least one path entry"
    )
    allPaths.forEach { relativePath ->
      val composed = File(projectRootPath, relativePath)
      assertTrue(
        composed.exists(),
        "composed path '${composed.absolutePath}' (from '$relativePath') must exist on disk"
      )
    }
  }
}
