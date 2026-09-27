/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ExternalSkillCompilerTest.kt  2026-09-27 11:42:23 Changed by gwy
 */

package gradum.skill

import gradum.SkillResult
import gradum.ToolMode
import gradum.skill.external.ExternalSkillClassLoader
import gradum.skill.external.ExternalSkillCompiler
import gradum.skill.external.ExternalSkillDirectoryScanner
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins the `.kt` skill base: a standalone source dropped next to the server,
 * compiled with the runtime classpath, loaded through [ExternalSkillClassLoader]
 * and executed as a first-class [Skill]. The end-to-end path mirrors what
 * [ExternalSkillDirectoryScanner] does at startup, minus the global registry.
 */
class ExternalSkillCompilerTest {

  @Test
  fun compilesLoadsAndExecutesAnExternalSkill() {
    val tempRoot = createTempDir()
    try {
      val source = File(tempRoot, "EchoSkill.kt")
      source.writeText(ECHO_SKILL_SOURCE)

      val outputDirectory = File(tempRoot, "out")
      val result = ExternalSkillCompiler().compile(
        sources = listOf(source),
        outputDirectory = outputDirectory,
        runtimeClasspath = runtimeClasspath(),
      )
      assertTrue(result.isSuccess, "compiler diagnostics: ${result.errors}")

      val classLoader = ExternalSkillClassLoader(Skill::class.java.classLoader, outputDirectory)
      val skills = classLoader.loadSkills()
      assertEquals(1, skills.size)

      val skill = skills.single()
      assertEquals("echo", skill.skillName)
      val executionResult = skill.execute(
        arguments = mapOf("message" to "hi there"),
        context = SkillContext(toolMode = ToolMode.AGENT, projectRoot = ""),
      )
      val success = assertIs<SkillResult.Success>(executionResult)
      assertEquals("hi there", success.data["echo"])
    } finally {
      tempRoot.deleteRecursively()
    }
  }

  @Test
  fun scannerWritesStarterSkillOnFirstRun() {
    val tempHome = createTempDir()
    try {
      ExternalSkillDirectoryScanner(homeDirectory = tempHome).scan()
      val starter = tempHome.resolve(".gradum").resolve("skills").resolve("HelloSkill.kt")
      assertTrue(starter.isFile, "expected a starter skill to be written on first run")
      assertTrue(starter.readText().contains("class HelloSkill"))
    } finally {
      tempHome.deleteRecursively()
    }
  }

  private fun runtimeClasspath(): List<String> =
    System.getProperty("java.class.path")
      .split(File.pathSeparator)
      .filter { entry -> entry.isNotBlank() }

  private fun createTempDir(): File =
    Files.createTempDirectory("external-skill-test").toFile()
}

private val ECHO_SKILL_SOURCE: String = """
  |package external
  |
  |import gradum.SkillResult
  |import gradum.makeSuccess
  |import gradum.skill.dsl.SchemaBuilder
  |import gradum.skill.Skill
  |import gradum.skill.SkillContext
  |import gradum.skill.dsl.string
  |
  |class EchoSkill : Skill() {
  |  override val alias: String = "echo"
  |  override val skillName: String = "echo"
  |  override val description: String = "Echoes a message back to the caller."
  |
  |  override fun execute(arguments: Map<String, Any>, context: SkillContext): SkillResult {
  |    val message: String = arguments["message"] as? String ?: ""
  |    return makeSuccess {
  |      string("echo", message)
  |    }
  |  }
  |
  |  override val schemaProperties: SchemaBuilder.() -> Unit = {
  |    string("message", "Text to echo back.", required = true)
  |  }
  |}
""".trimMargin()
