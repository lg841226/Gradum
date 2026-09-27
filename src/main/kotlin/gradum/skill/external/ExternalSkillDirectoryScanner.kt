/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ExternalSkillDirectoryScanner.kt  2026-09-27 11:49:08 Changed by gwy
 */

package gradum.skill.external

import gradum.skill.Skill
import gradum.skill.SkillRegistry
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File

/** The subdirectory under `~/.gradum/` that holds drop-in `.kt` skills. */
internal const val SKILLS_DIR_NAME: String = "skills"

/**
 * Scans `~/.gradum/skills/` at startup, compiles every `.kt` skill with
 * [ExternalSkillCompiler], loads the output with [ExternalSkillClassLoader],
 * and registers each skill through [SkillRegistry.register] so it is callable
 * from the next agent turn.
 *
 * First run creates the directory and drops a `HelloSkill.kt` starter so a
 * developer sees the shape of a skill without reading the docs. Hot reload
 * (watching the directory for changes) is deliberately out of scope for this
 * first version — only the startup pass runs.
 */
class ExternalSkillDirectoryScanner(
  private val homeDirectory: File = File(System.getProperty("user.home")),
  private val registry: SkillRegistry = SkillRegistry,
) {

  private val logger: Logger = LoggerFactory.getLogger("ExternalSkillDirectoryScanner")
  private val compiler = ExternalSkillCompiler()

  /**
   * Runs the startup pass: ensure the directory exists, compile and register
   * every `.kt` skill found. A compilation failure logs the diagnostics and
   * leaves the registry untouched rather than crashing startup.
   */
  fun scan() {
    val skillsDirectory = homeDirectory.resolve(".gradum").resolve(SKILLS_DIR_NAME)
    if (!skillsDirectory.exists()) {
      skillsDirectory.mkdirs()
      writeStarterSkill(skillsDirectory)
      logger.info("Created external skills directory at {}", skillsDirectory.absolutePath)
      return
    }

    val sources = skillsDirectory.listFiles { file -> file.isFile && file.extension == "kt" }
      ?.sortedBy { file -> file.name }
      ?.toList()
      .orEmpty()
    if (sources.isEmpty()) return

    val outputDirectory = skillsDirectory.resolve(".build")
    val result = compiler.compile(sources, outputDirectory, runtimeClasspath())
    result.warnings.forEach { warning -> logger.warn("External skill compile warning: {}", warning) }
    if (!result.isSuccess) {
      logger.error(
        "External .kt skills failed to compile and were skipped: {}",
        result.errors.joinToString(separator = "\n")
      )
      return
    }

    val classLoader = ExternalSkillClassLoader(Skill::class.java.classLoader, outputDirectory)
    val registeredThisScan = mutableSetOf<String>()
    classLoader.loadSkills().forEach { skill ->
      val skillName = skill.skillName
      val collidesWithBuiltIn = registry.getSkill(skillName) != null && skillName !in registeredThisScan
      val redefinedWithinScan = skillName in registeredThisScan
      when {
        collidesWithBuiltIn -> {
          logger.warn("Skipping external skill '{}': name collides with an already-registered skill", skillName)
        }

        redefinedWithinScan -> {
          logger.warn("External skill '{}' redefined by another .kt file; the latest definition wins", skillName)
          registry.register(skill)
          registeredThisScan += skillName
        }

        else -> {
          registry.register(skill)
          registeredThisScan += skillName
          logger.info("Registered external skill '{}'", skillName)
        }
      }
    }
  }

  private fun runtimeClasspath(): List<String> =
    System.getProperty("java.class.path")
      .split(File.pathSeparator)
      .filter { entry -> entry.isNotBlank() }
      .distinct()

  private fun writeStarterSkill(skillsDirectory: File) {
    val starter = skillsDirectory.resolve("HelloSkill.kt")
    if (starter.exists()) return
    starter.writeText(HELLO_SKILL_TEMPLATE)
  }
}

/** A minimal, compilable example skill written on first run. */
private val HELLO_SKILL_TEMPLATE: String = $$"""
  |package external
  |
  |import gradum.SkillResult
  |import gradum.makeSuccess
  |import gradum.skill.SchemaBuilder
  |import gradum.skill.Skill
  |import gradum.skill.SkillContext
  |import gradum.skill.string
  |
  |class HelloSkill : Skill() {
  |  override val alias: String = "hello"
  |  override val skillName: String = "hello"
  |  override val description: String =
  |    "A starter skill template. Drop any .kt file here, then restart " +
  |      "the server to compile and register it as a callable tool."
  |
  |  override fun execute(arguments: Map<String, Any>, context: SkillContext): SkillResult {
  |    val name: String = arguments["name"] as? String ?: "world"
  |    return makeSuccess {
  |      string("greeting", "Hello, $name!")
  |    }
  |  }
  |
  |  override val schemaProperties: SchemaBuilder.() -> Unit = {
  |    string(
  |      name = "name",
  |      description = "Who to greet.",
  |      required = false
  |    )
  |  }
  |}
""".trimMargin()
