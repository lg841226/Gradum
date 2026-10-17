package gradum.skill

import gradum.SkillResult
import gradum.ToolMode
import gradum.skill.external.ExternalDiagnosticSeverity
import gradum.skill.external.ExternalSkillDirectoryScanner
import gradum.skill.external.ExternalSkillDirectoryWatcher
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.*

/**
 * Pins the external-skill hot-reload against a fake [SkillStore], so the
 * global registry is never polluted by tests.
 *
 * [ExternalSkillDirectoryScanner.reconcile] must register a new skill,
 * re-register a changed one with a freshly compiled class (the old class is
 * gone and the new behavior shows), unregister a deleted one, and unregister
 * everything when the directory empties. A broken edit keeps last-good: the
 * working skill must not be dropped.
 *
 * Reconciliation is incremental: a touch without a content change compiles
 * nothing, an edit compiles exactly that one source while the untouched source
 * keeps its output, and deleting a source removes its .class output as well.
 *
 * A skill split across files compiles in two stages: the single-source pass
 * fails on the file that names a symbol living in its sibling, then the whole
 * directory is retried as one batch and both land. A genuinely broken source
 * fails both stages and stays out while its siblings still register, a fixed
 * source recovers on the next pass, and deleting a file that another still
 * references drops its classes and reports the dangling reference instead of
 * silently keeping a class that can no longer link.
 *
 * The watcher reloads only when the fingerprint changes: a tick with an
 * unchanged fingerprint is a no-op, a changed fingerprint reloads. Watcher
 * start() and close() are idempotent and must not corrupt the
 * already-registered skill.
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
      assertNotNull(store.getSkill("echo"))
    }
  }

  @Test
  fun reconcileCompilesMutuallyReferencingSourcesAsOneBatch() {
    withScanner { store, skillsDir, scanner ->
      File(skillsDir, "AlphaSkill.kt").writeText(ALPHA_SKILL_SOURCE)
      File(skillsDir, "ZuluHelper.kt").writeText(ZULU_HELPER_SOURCE)

      scanner.reconcile()

      assertNotNull(store.getSkill("alpha"))
      assertEquals("from zulu: hi", runAlpha(store, "hi"))
    }
  }

  @Test
  fun reconcileKeepsOtherSkillsWhileOneSourceIsBroken() {
    withScanner { store, skillsDir, scanner ->
      File(skillsDir, "EchoSkill.kt").writeText(ECHO_SKILL_SOURCE)
      File(skillsDir, "ZuluHelper.kt").writeText(BROKEN_HELPER_SOURCE)

      scanner.reconcile()

      assertNotNull(store.getSkill("echo"))
      assertEquals("hi there", runEcho(store, "hi there"))
      assertFalse(File(skillsDir, ".build").walkTopDown().any { built -> built.name == "ZuluHelper.class" })
      assertTrue("ZuluHelper.kt" in scanner.lastCompiledSourceNames)
    }
  }

  @Test
  fun reconcileRecoversSplitSkillAfterHelperIsFixed() {
    withScanner { store, skillsDir, scanner ->
      File(skillsDir, "AlphaSkill.kt").writeText(ALPHA_SKILL_SOURCE)
      File(skillsDir, "ZuluHelper.kt").writeText(BROKEN_HELPER_SOURCE)
      scanner.reconcile()
      assertNull(store.getSkill("alpha"))

      File(skillsDir, "ZuluHelper.kt").writeText(ZULU_HELPER_SOURCE)
      scanner.reconcile()

      assertNotNull(store.getSkill("alpha"))
      assertEquals("from zulu: hi", runAlpha(store, "hi"))
    }
  }

  @Test
  fun reconcileDropsDeletedHelperAndReportsDanglingReference() {
    withScanner { store, skillsDir, scanner ->
      File(skillsDir, "AlphaSkill.kt").writeText(ALPHA_SKILL_SOURCE)
      File(skillsDir, "ZuluHelper.kt").writeText(ZULU_HELPER_SOURCE)
      scanner.reconcile()
      assertEquals("from zulu: hi", runAlpha(store, "hi"))

      File(skillsDir, "ZuluHelper.kt").delete()
      scanner.reconcile()

      assertFalse(File(skillsDir, ".build").walkTopDown().any { built -> built.name == "ZuluHelper.class" })
      assertTrue("AlphaSkill.kt" in scanner.lastCompiledSourceNames)
      val redeploy = scanner.deploy("AlphaSkill.kt", ALPHA_SKILL_SOURCE)
      assertFalse(redeploy.compiled)
      assertTrue(redeploy.diagnostics.any { diagnostic -> diagnostic.severity == ExternalDiagnosticSeverity.ERROR })
      assertNotNull(store.getSkill("alpha"))
    }
  }

  @Test
  fun compileOnlyResolvesSiblingSourceOnDisk() {
    withScanner { _, skillsDir, scanner ->
      File(skillsDir, "ZuluHelper.kt").writeText(ZULU_HELPER_SOURCE)

      val result = scanner.compileOnly("AlphaSkill.kt", ALPHA_SKILL_SOURCE)

      assertTrue(result.compiled, "compile diagnostics: ${result.diagnostics}")
    }
  }

  @Test
  fun reconcileRecompilesOnlyChangedSources() {
    withScanner { store, skillsDir, scanner ->
      File(skillsDir, "EchoSkill.kt").writeText(ECHO_SKILL_SOURCE)
      File(skillsDir, "SecondSkill.kt").writeText(SECOND_SKILL_SOURCE)
      scanner.reconcile()
      assertEquals(listOf("EchoSkill.kt", "SecondSkill.kt"), scanner.lastCompiledSourceNames)
      File(skillsDir, "EchoSkill.kt").setLastModified(System.currentTimeMillis() + 5_000)
      scanner.reconcile()
      assertEquals(emptyList(), scanner.lastCompiledSourceNames)
      assertEquals("hi there", runEcho(store, "hi there"))
      File(skillsDir, "EchoSkill.kt").writeText(ECHO_SKILL_SOURCE_V2)
      scanner.reconcile()
      assertEquals(listOf("EchoSkill.kt"), scanner.lastCompiledSourceNames)
      assertEquals("V2: hi there", runEcho(store, "hi there"))
      assertNotNull(store.getSkill("second"))
    }
  }

  @Test
  fun reconcileRemovesOutputsOfDeletedSource() {
    withScanner { _, skillsDir, scanner ->
      File(skillsDir, "SecondSkill.kt").writeText(SECOND_SKILL_SOURCE)
      scanner.reconcile()
      val outputDirectory = File(skillsDir, ".build")
      assertTrue(outputDirectory.walkTopDown().any { outputFile -> outputFile.name == "SecondSkill.class" })

      File(skillsDir, "SecondSkill.kt").delete()
      scanner.reconcile()

      assertFalse(outputDirectory.walkTopDown().any { outputFile -> outputFile.name == "SecondSkill.class" })
    }
  }

  @Test
  fun onPeriodicTickReloadsOnlyWhenFingerprintChanges() {
    withScanner { store, skillsDir, scanner ->
      val watcher = ExternalSkillDirectoryWatcher(skillScanner = scanner)
      File(skillsDir, "EchoSkill.kt").writeText(ECHO_SKILL_SOURCE)
      watcher.reconcileNow()
      watcher.onPeriodicTick()
      assertEquals("hi there", runEcho(store, "hi there"))
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

      val watcher = ExternalSkillDirectoryWatcher(skillScanner = scanner)
      watcher.start()

      watcher.close()

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

  private fun runAlpha(store: FakeSkillStore, message: String): String {
    val result = store.getSkill("alpha")!!.execute(
      arguments = mapOf("message" to message),
      context = SkillContext(toolMode = ToolMode.AGENT, projectRoot = ""),
    )
    val success = assertIs<SkillResult.Success>(result)
    return success.data["alpha"] as String
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

private val ECHO_SKILL_SOURCE_V2: String = $$"""
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
  |      string("echo", "V2: $message")
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

/**
 * A skill that cannot compile on its own: `ZuluHelper` lives in another file, so
 * only the whole-directory batch pass can resolve it. The file is named after
 * its class, so the batch can attribute `AlphaSkill.class` back to it.
 */
private val ALPHA_SKILL_SOURCE: String = """
  |package external
  |
  |import gradum.SkillResult
  |import gradum.makeSuccess
  |import gradum.skill.dsl.SchemaBuilder
  |import gradum.skill.Skill
  |import gradum.skill.SkillContext
  |import gradum.skill.dsl.string
  |
  |class AlphaSkill : Skill() {
  |  override val alias: String = "alpha"
  |  override val skillName: String = "alpha"
  |  override val description: String = "Splits its work across a second file."
  |
  |  override fun execute(arguments: Map<String, Any>, context: SkillContext): SkillResult {
  |    val message: String = arguments["message"] as? String ?: ""
  |    return makeSuccess {
  |      string("alpha", ZuluHelper.decorate(message))
  |    }
  |  }
  |
  |  override val schemaProperties: SchemaBuilder.() -> Unit = {
  |    string("message", "Text to decorate.", required = true)
  |  }
  |}
""".trimMargin()

/** The sibling [ALPHA_SKILL_SOURCE] references; it declares no skill itself. */
private val ZULU_HELPER_SOURCE: String = """
  |package external
  |
  |object ZuluHelper {
  |  fun decorate(message: String): String = "from zulu: " + message
  |}
""".trimMargin()

/** An unparsable sibling: no skill, but enough to fail every compile pass. */
private val BROKEN_HELPER_SOURCE: String = """
  |package external
  |
  |object ZuluHelper {
  |  val value: String =
  |}
""".trimMargin()
