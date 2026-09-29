package gradum.skill

import gradum.SkillResult
import gradum.ToolMode
import gradum.skill.external.ExternalSkillDirectoryScanner
import gradum.skill.external.ExternalSkillDirectoryWatcher
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.*

/**
 * Pins the external-skill hot-reload: [ExternalSkillDirectoryScanner.reconcile]
 * must register new skills, re-register changed ones with freshly-compiled
 * classes, and unregister deleted ones: all against a fake [SkillStore] so the
 * global registry is never polluted by tests.
 */
class ExternalSkillReloadTest {

  @Test
  fun reconcileRegistersNewSkill() {
    withScanner { store, skillsDir, scanner ->
      File(skillsDir, "EchoSkill.kt").writeText(ECHO_SKILL_SOURCE)

      scanner.reconcile()

      assertNotNull(store.getSkill("echo"))
    }
  }

  @Test
  fun reconcileReloadsChangedSkillWithFreshClass() {
    withScanner { store, skillsDir, scanner ->
      File(skillsDir, "EchoSkill.kt").writeText(ECHO_SKILL_SOURCE)
      scanner.reconcile()
      assertEquals("hi there", runEcho(store, "hi there"))

      File(skillsDir, "EchoSkill.kt").writeText(ECHO_SKILL_SOURCE_V2)
      scanner.reconcile()

      // The old class must be gone: the reloaded skill reflects the new source.
      assertEquals("V2: hi there", runEcho(store, "hi there"))
    }
  }

  @Test
  fun reconcileUnregistersDeletedSkill() {
    withScanner { store, skillsDir, scanner ->
      File(skillsDir, "EchoSkill.kt").writeText(ECHO_SKILL_SOURCE)
      scanner.reconcile()
      assertNotNull(store.getSkill("echo"))

      File(skillsDir, "EchoSkill.kt").delete()
      scanner.reconcile()

      assertNull(store.getSkill("echo"))
    }
  }

  @Test
  fun reconcileWithEmptyDirectoryUnloadsAllExternalSkills() {
    withScanner { store, skillsDir, scanner ->
      File(skillsDir, "EchoSkill.kt").writeText(ECHO_SKILL_SOURCE)
      File(skillsDir, "SecondSkill.kt").writeText(SECOND_SKILL_SOURCE)
      scanner.reconcile()
      assertNotNull(store.getSkill("echo"))
      assertNotNull(store.getSkill("second"))

      File(skillsDir, "EchoSkill.kt").delete()
      File(skillsDir, "SecondSkill.kt").delete()
      scanner.reconcile()

      assertNull(store.getSkill("echo"))
      assertNull(store.getSkill("second"))
    }
  }

  @Test
  fun reconcileKeepsRegistryOnCompileFailure() {
    withScanner { store, skillsDir, scanner ->
      File(skillsDir, "EchoSkill.kt").writeText(ECHO_SKILL_SOURCE)
      scanner.reconcile()
      assertNotNull(store.getSkill("echo"))

      File(skillsDir, "EchoSkill.kt").writeText(BROKEN_SKILL_SOURCE)
      scanner.reconcile()

      // Last-good semantics: a broken edit must not drop the working skill.
      assertNotNull(store.getSkill("echo"))
    }
  }

  @Test
  fun onPeriodicTickReloadsOnlyWhenFingerprintChanges() {
    withScanner { store, skillsDir, scanner ->
      val watcher = ExternalSkillDirectoryWatcher(scanner)
      File(skillsDir, "EchoSkill.kt").writeText(ECHO_SKILL_SOURCE)
      watcher.reconcileNow()

      // Unchanged fingerprint: the tick must be a no-op.
      watcher.onPeriodicTick()
      assertEquals("hi there", runEcho(store, "hi there"))

      // Changed fingerprint: the tick triggers a reload.
      File(skillsDir, "EchoSkill.kt").writeText(ECHO_SKILL_SOURCE_V2)
      watcher.onPeriodicTick()
      assertEquals("V2: hi there", runEcho(store, "hi there"))

      watcher.close()
    }
  }

  @Test
  fun watcherStartAndCloseAreIdempotent() {
    withScanner { store, skillsDir, scanner ->
      File(skillsDir, "EchoSkill.kt").writeText(ECHO_SKILL_SOURCE)
      scanner.reconcile()
      assertNotNull(store.getSkill("echo"))

      val watcher = ExternalSkillDirectoryWatcher(scanner)
      watcher.start()
      watcher.start() // no-op
      watcher.close()
      watcher.close() // no-op

      // start/close must not corrupt the already-registered skill.
      assertNotNull(store.getSkill("echo"))
    }
  }

  private fun withScanner(
    block: (FakeSkillStore, File, ExternalSkillDirectoryScanner) -> Unit
  ) {
    val tempHome = createTempDir()
    try {
      val store = FakeSkillStore()
      val scanner = ExternalSkillDirectoryScanner(homeDirectory = tempHome, skillStore = store)
      val skillsDir = scanner.skillsDirectory
      skillsDir.mkdirs()
      block(store, skillsDir, scanner)
    } finally {
      tempHome.deleteRecursively()
    }
  }

  private fun runEcho(store: FakeSkillStore, message: String): String {
    val result = store.getSkill("echo")!!.execute(
      arguments = mapOf("message" to message),
      context = SkillContext(toolMode = ToolMode.AGENT, projectRoot = ""),
    )
    val success = assertIs<SkillResult.Success>(result)
    return success.data["echo"] as String
  }

  private fun createTempDir(): File =
    Files.createTempDirectory("external-skill-reload-test").toFile()
}

/** In-memory [SkillStore] so reconcile tests never touch the global registry. */
private class FakeSkillStore : SkillStore {
  private val skills: ConcurrentHashMap<String, Skill> = ConcurrentHashMap()

  override fun register(skill: Skill) {
    skills[skill.skillName] = skill
  }

  override fun unregister(skillName: String): Skill? = skills.remove(skillName)

  override fun getSkill(skillName: String): Skill? = skills[skillName]
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

private val ECHO_SKILL_SOURCE_V2: String = """
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
  |  override val description: String = "Echoes a message back with a V2 prefix."
  |
  |  override fun execute(arguments: Map<String, Any>, context: SkillContext): SkillResult {
  |    val message: String = arguments["message"] as? String ?: ""
  |    return makeSuccess {
  |      string("echo", "V2: ${'$'}message")
  |    }
  |  }
  |
  |  override val schemaProperties: SchemaBuilder.() -> Unit = {
  |    string("message", "Text to echo back.", required = true)
  |  }
  |}
""".trimMargin()

private val SECOND_SKILL_SOURCE: String = """
  |package external
  |
  |import gradum.SkillResult
  |import gradum.makeSuccess
  |import gradum.skill.dsl.SchemaBuilder
  |import gradum.skill.Skill
  |import gradum.skill.SkillContext
  |
  |class SecondSkill : Skill() {
  |  override val alias: String = "second"
  |  override val skillName: String = "second"
  |  override val description: String = "A second external skill."
  |
  |  override fun execute(arguments: Map<String, Any>, context: SkillContext): SkillResult =
  |    makeSuccess(emptyMap())
  |
  |  override val schemaProperties: SchemaBuilder.() -> Unit = {}
  |}
""".trimMargin()

private val BROKEN_SKILL_SOURCE: String = """
  |package external
  |
  |import gradum.Skill
  |
  |class EchoSkill : Skill() {
""".trimMargin()
