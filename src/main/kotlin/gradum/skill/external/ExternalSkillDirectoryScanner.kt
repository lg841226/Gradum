package gradum.skill.external

import gradum.skill.Skill
import gradum.skill.SkillRegistry
import gradum.skill.SkillStore
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

internal const val SKILLS_DIRECTORY_NAME: String = "skills"

/** Subdirectory of the skills directory that holds the deployed .class output. */
internal const val BUILD_DIRECTORY_NAME: String = ".build"

/**
 * Compiles every skill source under ~/.gradum/skills/ with
 * [ExternalSkillCompiler], loads the output with [ExternalSkillClassLoader],
 * and reconciles them into the [SkillStore] so each becomes callable from the
 * next agent turn.
 *
 * Entry points. scan() is the startup pass: it creates ~/.gradum/skills/ when
 * missing and drops a HelloSkill.kt starter so a developer sees the shape of a
 * skill without reading the docs, then runs reconcile(). reconcile() is the
 * single idempotent entry point, called by scan() and again by
 * [ExternalSkillDirectoryWatcher] on any change to the directory. It
 * recompiles what changed, loads the result, registers new or changed skills,
 * and unregisters external skills whose source file disappeared. Only skills
 * this scanner registered (tracked in externalOwned) are ever unregistered:
 * built-in and MCP skills are never touched, and a name collision with an
 * already-registered skill is skipped with a warning.
 *
 * Incremental compilation. Three maps keyed by source file name drive the pass:
 * compiledSourceHashes holds the content SHA-256 of every file at its last
 * compile attempt, outputsBySource holds the .class files a successful compile
 * produced, and diagnosticsBySource holds what the latest attempt reported. A
 * source whose hash still matches keeps its .class output and its diagnostics
 * untouched; a source whose recorded output went missing, a new source, or an
 * edited source is recompiled; a deleted source has its output removed. When
 * nothing changed the compiler is skipped entirely. The first pass inside a
 * process starts from a clean .build because no output has been attributed
 * yet. Each changed source is compiled on its own into .build-staging, which
 * lives outside .build so the classloader never scans it, with .build on the
 * classpath so unchanged skills still resolve. Only a successful compile is
 * swapped into .build: a failed compile keeps the previous working .class
 * files (last-good) instead of dropping every skill. A failed attempt records
 * its hash too, so the hash always describes the diagnostics stored beside it;
 * recording only successes would let an edit back to the last text that did
 * compile match the old hash, skip to compile, and report the diagnostics the
 * broken text left behind. lastCompiledSourceNames records what the most recent
 * pass compiled, for tests and logs.
 *
 * currentFingerprint() is the mtime-based fingerprint the watcher polls to
 * skip redundant reloads. It only looks at skill sources, never at .build
 * output, so compiled artifacts cannot re-trigger a reload.
 */
class ExternalSkillDirectoryScanner(
  homeDirectory: File = File(System.getProperty("user.home")),
  private val skillStore: SkillStore = SkillRegistry
) {

  private val logger: Logger = LoggerFactory.getLogger("ExternalSkillDirectoryScanner")
  private val skillCompiler = ExternalSkillCompiler()
  val skillsDirectory: File = homeDirectory.resolve(".gradum").resolve(SKILLS_DIRECTORY_NAME)
  private val externalOwned: ConcurrentHashMap<String, Skill> = ConcurrentHashMap()
  private val stagingDirectory: File = skillsDirectory.resolve(".build-staging")
  private val compiledSourceHashes: MutableMap<String, String> = mutableMapOf()
  private val outputsBySource: MutableMap<String, MutableSet<String>> = mutableMapOf()
  private val diagnosticsBySource: MutableMap<String, List<ExternalDiagnostic>> = mutableMapOf()
  internal var lastCompiledSourceNames: List<String> = emptyList()
    private set

  fun scan() {
    if (!skillsDirectory.exists()) {
      skillsDirectory.mkdirs()
      writeStarterSkill(skillsDirectory)
      logger.info("Created external skills directory at {}", skillsDirectory.absolutePath)
      return
    }
    reconcile()
  }

  /**
   * Writes [sourceText] to ~/.gradum/skills/<fileName> and reconciles
   * immediately, then returns the compiler diagnostics for that file. This is
   * the deployment path behind the editor's Run button: it runs the same reconcile
   * loop the directory watcher uses, but synchronously, so the caller gets
   * feedback in one request instead of waiting for a watcher tick.
   *
   * [fileName] is normalized to a bare `*.kt` name and rejected when it is not
   * a plain identifier, so a caller can never escape the skills' directory.
   */
  fun deploy(fileName: String, sourceText: String): DeployResult {
    val safeName = normalizeSourceName(fileName)
    skillsDirectory.mkdirs()
    skillsDirectory.resolve(safeName).writeText(sourceText)
    reconcile()

    val diagnostics = diagnosticsBySource[safeName].orEmpty()
    return DeployResult(
      fileName = safeName,
      diagnostics = diagnostics,
      compiled = diagnostics.none { it.severity == ExternalDiagnosticSeverity.ERROR }
    )
  }

  /**
   * Compiles [sourceText] as [fileName] without touching the skills directory:
   * the source and the compiler output both go to a throwaway directory that is
   * removed before returning. Nothing is written to ~/.gradum/skills/, nothing
   * is loaded, and the registry is left alone, so this is the editor's Build
   * action: it answers "does this compile?" without making the skill callable.
   * Only the diagnostics are reported back.
   */
  fun compileOnly(fileName: String, sourceText: String): DeployResult {
    val safeName = normalizeSourceName(fileName)
    val buildRoot = Files.createTempDirectory("gradum-skill-build").toFile()
    try {
      val sourceFile = buildRoot.resolve(safeName)
      sourceFile.writeText(sourceText)
      // The previously deployed output sits on the classpath so a source that
      // references another installed skill still resolves, exactly as it would
      // during a deployment compile.
      val previousOutput = skillsDirectory.resolve(BUILD_DIRECTORY_NAME)
      val compileResult = skillCompiler.compile(
        outputDirectory = buildRoot.resolve("out"),
        sourceFiles = listOf(sourceFile),
        runtimeClasspath = runtimeClasspath(),
        additionalClasspath = if (previousOutput.isDirectory) listOf(previousOutput) else emptyList(),
      )
      return DeployResult(
        fileName = safeName,
        diagnostics = compileResult.diagnostics,
        compiled = compileResult.isSuccess,
      )
    } finally {
      buildRoot.deleteRecursively()
    }
  }

  /** Names of the `.kt` sources currently in ~/.gradum/skills/, sorted. */
  fun listSourceNames(): List<String> = listKtSources().map { sourceFile -> sourceFile.name }

  /** Reads the text of a skill source, or null when it does not exist. */
  fun readSource(fileName: String): String? {
    val safeName = try {
      normalizeSourceName(fileName)
    } catch (_: IllegalArgumentException) {
      return null
    }
    val sourceFile = skillsDirectory.resolve(safeName)
    return if (sourceFile.isFile) sourceFile.readText() else null
  }

  private fun normalizeSourceName(fileName: String): String {
    val bareName = fileName.trim().removeSuffix(".kt")
    require(SOURCE_NAME_PATTERN.matches(bareName)) {
      "Invalid skill source name: '$fileName' (expected [A-Za-z_][A-Za-z0-9_]*)"
    }
    return "$bareName.kt"
  }

  @Synchronized
  fun reconcile() {
    if (!skillsDirectory.exists()) {
      unloadAllExternal()
      return
    }

    val sourceFiles = listKtSources()
    val outputDirectory = skillsDirectory.resolve(BUILD_DIRECTORY_NAME)
    if (sourceFiles.isEmpty()) {
      wipeOutputDirectory(outputDirectory)
      compiledSourceHashes.clear()
      outputsBySource.clear()
      diagnosticsBySource.clear()
      lastCompiledSourceNames = emptyList()
      unloadAllExternal()
      return
    }

    refreshCompiledSources(sourceFiles, outputDirectory)

    val loadedByName = loadSkillsByName(outputDirectory)
    unloadMissingExternal(loadedByName.keys)
    registerOrSkipLoaded(loadedByName)
  }

  private fun refreshCompiledSources(sourceFiles: List<File>, outputDirectory: File) {
    if (compiledSourceHashes.isEmpty()) {
      wipeOutputDirectory(outputDirectory)
    }

    val currentHashes = sourceFiles.associate { sourceFile -> sourceFile.name to hashSourceFile(sourceFile) }
    val currentNames = currentHashes.keys
    for (staleName in compiledSourceHashes.keys.filterNot { hashName -> hashName in currentNames }) {
      removeOutputs(outputDirectory, staleName)
      compiledSourceHashes.remove(staleName)
      outputsBySource.remove(staleName)
      diagnosticsBySource.remove(staleName)
    }

    val changedSources = sourceFiles.filter { sourceFile ->
      compiledSourceHashes[sourceFile.name] != currentHashes[sourceFile.name] ||
        !outputsExist(outputDirectory, sourceFile.name)
    }
    lastCompiledSourceNames = changedSources.map { sourceFile -> sourceFile.name }
    if (changedSources.isEmpty()) {
      logger.debug("External skills unchanged ({} sources); skipping compile", sourceFiles.size)
      return
    }

    logger.info(
      "Compiling {} of {} external skill sources incrementally",
      changedSources.size,
      sourceFiles.size,
    )
    for (sourceFile in changedSources) {
      val sourceHash = currentHashes.getValue(sourceFile.name)
      compileSourceFile(sourceFile, sourceHash, outputDirectory)
    }
  }

  private fun compileSourceFile(sourceFile: File, sourceHash: String, outputDirectory: File): Boolean {
    wipeOutputDirectory(stagingDirectory)
    val compileResult = skillCompiler.compile(
      outputDirectory = stagingDirectory,
      sourceFiles = listOf(sourceFile),
      runtimeClasspath = runtimeClasspath(),
      additionalClasspath = listOf(outputDirectory),
    )
    compileResult.compileWarnings.forEach { compileWarning ->
      logger.warn("External skill compile warning: {}", compileWarning)
    }
    // Recorded together, because a skip is decided by the hash alone: the two
    // must describe the same attempt, or a skip would serve the previous
    // attempt's diagnostics.
    diagnosticsBySource[sourceFile.name] = compileResult.diagnostics
    compiledSourceHashes[sourceFile.name] = sourceHash
    if (!compileResult.isSuccess) {
      logger.error(
        "External skill '{}' failed to compile; keeping previous version: {}",
        sourceFile.name,
        compileResult.compileErrors.joinToString(separator = "\n"),
      )
      stagingDirectory.deleteRecursively()
      return false
    }

    removeOutputs(outputDirectory, sourceFile.name)
    val producedOutputs = mutableSetOf<String>()
    stagingDirectory.walkTopDown().filter { stagedFile -> stagedFile.isFile }.forEach { stagedFile ->
      val relativePath = stagedFile.relativeTo(stagingDirectory).path
      val targetFile = outputDirectory.resolve(relativePath)
      targetFile.parentFile.mkdirs()
      stagedFile.copyTo(targetFile, overwrite = true)
      producedOutputs += relativePath
    }
    outputsBySource[sourceFile.name] = producedOutputs
    stagingDirectory.deleteRecursively()
    return true
  }

  private fun outputsExist(outputDirectory: File, sourceName: String): Boolean {
    val producedOutputs = outputsBySource[sourceName] ?: return false
    return producedOutputs.all { relativePath -> outputDirectory.resolve(relativePath).isFile }
  }

  private fun removeOutputs(outputDirectory: File, sourceName: String) {
    for (relativePath in outputsBySource[sourceName].orEmpty()) {
      val staleOutput = outputDirectory.resolve(relativePath)
      if (staleOutput.exists()) staleOutput.delete()
    }
  }

  private fun hashSourceFile(sourceFile: File): String =
    MessageDigest.getInstance("SHA-256").digest(sourceFile.readBytes())
      .joinToString(separator = "") { digestByte -> "%02x".format(digestByte) }

  internal fun currentFingerprint(): String {
    if (!skillsDirectory.exists()) return ""
    return listKtSources().joinToString("|") { sourceFile ->
      "${sourceFile.name}:${sourceFile.lastModified()}:${sourceFile.length()}"
    }
  }

  private fun listKtSources(): List<File> =
    skillsDirectory.listFiles { sourceFile -> sourceFile.isFile && sourceFile.extension == "kt" }
      ?.sortedBy { sourceFile -> sourceFile.name }
      ?.toList()
      .orEmpty()

  private fun loadSkillsByName(outputDirectory: File): Map<String, Skill> {
    val classLoader = ExternalSkillClassLoader(Skill::class.java.classLoader, outputDirectory)
    val loadedByName = mutableMapOf<String, Skill>()
    val seenNames = mutableSetOf<String>()

    classLoader.loadSkills().forEach { loadedSkill ->
      val skillName = loadedSkill.skillName
      if (skillName in seenNames) {
        logger.warn("External skill '{}' redefined by another .kt file; the latest definition wins", skillName)
      }
      seenNames += skillName
      loadedByName[skillName] = loadedSkill
    }
    return loadedByName
  }

  private fun unloadMissingExternal(loadedNames: Set<String>) {
    val removedNames = externalOwned.keys.filterNot { skillName -> skillName in loadedNames }
    for (skillName in removedNames) {
      val ownedSkill = externalOwned[skillName]
      if (ownedSkill != null && skillStore.getSkill(skillName) === ownedSkill) {
        skillStore.unregister(skillName)
        logger.info("Unregistered removed external skill '{}'", skillName)
      }
      externalOwned.remove(skillName)
    }
  }

  private fun registerOrSkipLoaded(loadedByName: Map<String, Skill>) {
    for ((skillName, registeredSkill) in loadedByName) {
      when {
        externalOwned.containsKey(skillName) -> {
          skillStore.register(registeredSkill)
          externalOwned[skillName] = registeredSkill
          logger.info("Reloaded external skill '{}'", skillName)
        }

        skillStore.getSkill(skillName) != null -> {
          logger.warn("Skipping external skill '{}': name collides with an already-registered skill", skillName)
        }

        else -> {
          skillStore.register(registeredSkill)
          externalOwned[skillName] = registeredSkill
          logger.info("Registered external skill '{}'", skillName)
        }
      }
    }
  }

  private fun unloadAllExternal() {
    val ownedNames = externalOwned.keys.toList()
    for (skillName in ownedNames) {
      val ownedSkill = externalOwned[skillName]
      if (ownedSkill != null && skillStore.getSkill(skillName) === ownedSkill) {
        skillStore.unregister(skillName)
        logger.info("Unregistered external skill '{}' while unloading all", skillName)
      }
      externalOwned.remove(skillName)
    }
  }

  private fun wipeOutputDirectory(outputDirectory: File) {
    if (outputDirectory.exists()) outputDirectory.deleteRecursively()
    outputDirectory.mkdirs()
  }

  private fun runtimeClasspath(): List<String> =
    System.getProperty("java.class.path")
      .split(File.pathSeparator)
      .filter { classpathEntry -> classpathEntry.isNotBlank() }
      .distinct()

  private fun writeStarterSkill(skillsDirectory: File) {
    val starterFile = skillsDirectory.resolve("HelloSkill.kt")
    if (starterFile.exists()) return
    starterFile.writeText(HELLO_SKILL_TEMPLATE)
  }
}

private val SOURCE_NAME_PATTERN: Regex = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")

/** Result of [ExternalSkillDirectoryScanner.deploy]: what was written and why it did or did not compile. */
data class DeployResult(
  val fileName: String,
  val compiled: Boolean,
  val diagnostics: List<ExternalDiagnostic>
)

/**
 * Process-wide handle to the scanner built at startup, so the HTTP routes can
 * deploy sources and read the skills directory. Mirrors the [gradum.skill.SkillRegistry]
 * singleton pattern; it stays null in unit tests that construct their own
 * scanner, and routes treat that as "skills directory not initialized".
 */
object ExternalSkillHost {
  @Volatile
  var scanner: ExternalSkillDirectoryScanner? = null
}

private val HELLO_SKILL_TEMPLATE: String = $$"""
  |package external
  |
  |import gradum.SkillResult
  |import gradum.makeSuccess
  |import gradum.skill.dsl.SchemaBuilder
  |import gradum.skill.Skill
  |import gradum.skill.SkillContext
  |import gradum.skill.dsl.string
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
