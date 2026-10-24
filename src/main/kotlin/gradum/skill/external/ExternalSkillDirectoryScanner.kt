package gradum.skill.external

import gradum.skill.Skill
import gradum.skill.SkillRegistry
import gradum.skill.SkillStore
import gradum.utils.JsonUtil
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

internal const val SKILLS_DIRECTORY_NAME: String = "skills"
internal const val BUILD_DIRECTORY_NAME: String = ".build"

/**
 * Compiles every skill source under ~/.gradum/skills/ with ExternalSkillCompiler,
 * loads the output with ExternalSkillClassLoader, and reconciles them into the
 * SkillStore so each becomes callable from the next agent turn.
 *
 * Entry points:
 * - scan(): startup pass. Creates ~/.gradum/skills/ when missing, drops a
 *   HelloSkill.kt starter, then runs reconcile().
 * - reconcile(): single idempotent entry point, called by scan() and by
 *   ExternalSkillDirectoryWatcher on any directory change. Recompiles what
 *   changed, loads results, registers new/changed skills, and unregisters
 *   external skills whose source disappeared. Only skills in externalOwned are
 *   ever unregistered; built-in and MCP skills are never touched, and name
 *   collisions with already-registered skills are skipped with a warning.
 *
 * Incremental compilation:
 * - State keyed by source file name:
 *   - compiledSourceHashes: SHA-256 of each file at last compile attempt
 *   - outputsBySource: .class files a successful compile produced
 *   - diagnosticsBySource: latest reported diagnostics
 * - Unchanged hash keeps .class output and diagnostics.
 * - New, edited, or missing-output source recompiles.
 * - Deleted source removes output and forces one pass over remaining sources,
 *   so dangling references surface instead of silently keeping a broken class.
 * - Nothing changed: compiler skipped entirely.
 * - State persists in .build-manifest.json beside .build, so first pass of a
 *   process restores previous state and unchanged skills load without recompiling.
 *   Only an unreadable manifest falls back to a clean pass.
 *
 * Two-stage compilation:
 * 1. Each changed source compiles alone into .build-staging (outside .build so
 *    the classloader never scans it), with .build on the classpath so unchanged
 *    skills still resolve. One broken file cannot hold up others' updates.
 * 2. Only if that fails, all sources compile as one batch in .build-staging,
 *    because a skill split across files only resolves when compiled together.
 *    The failure itself is the signal, so no import/dependency analysis needed.
 * - Only a successful compile is swapped into .build. Failure at either stage
 *   keeps the previous working .class files (last-good) instead of dropping
 *   every skill. Batch success refreshes hash, outputs, and diagnostics for
 *   every compiled source, changed or not.
 * - Failed attempts also record their hash, so the hash always matches the
 *   stored diagnostics. Recording only successes would let an edit back to
 *   previously-compiling text match the old hash, skip compilation, and report
 *   diagnostics left by the broken text.
 * - lastCompiledSourceNames records what the most recent pass compiled.
 *
 * currentFingerprint() is the mtime-based fingerprint the watcher polls to skip
 * redundant reloads. It only looks at skill sources, never at .build output,
 * so compiled artifacts cannot re-trigger a reload.
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
  private val manifestFile: File = skillsDirectory.resolve(".build-manifest.json")
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
   * the deployment path behind the editor's Run button: it runs the same
   * reconcile loop the directory watcher uses, but synchronously, so the caller
   * gets feedback in one request instead of waiting for a watcher tick.
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
   * The directory's other sources ride along as inputs (the edited text stands
   * in for its on-disk namesake) so a skill split across files verifies the
   * same way the reconcile batch would; only diagnostics are reported back.
   */
  fun compileOnly(fileName: String, sourceText: String): DeployResult {
    val safeName = normalizeSourceName(fileName)
    val buildRoot = Files.createTempDirectory("gradum-skill-build").toFile()
    try {
      val sourceFile = buildRoot.resolve(safeName)
      sourceFile.writeText(sourceText)
      val companionSources = listKtSources().filter { existing -> existing.name != safeName }

      val previousOutput = skillsDirectory.resolve(BUILD_DIRECTORY_NAME)
      val compileResult = skillCompiler.compile(
        outputDirectory = buildRoot.resolve("out"),
        sourceFiles = listOf(sourceFile) + companionSources,
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

  /** Names of the `.kt` sources currently in ~/.gradum/skills/, sorted */
  fun listSourceNames(): List<String> = listKtSources().map { sourceFile -> sourceFile.name }

  /** Reads the text of a skill source, or null when it does not exist */
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
      manifestFile.delete()
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
    if (compiledSourceHashes.isEmpty() && !restoreBuildManifest(sourceFiles, outputDirectory)) {
      wipeOutputDirectory(outputDirectory)
    }

    val currentHashes = sourceFiles.associate { sourceFile -> sourceFile.name to hashSourceFile(sourceFile) }
    val currentNames = currentHashes.keys
    val removedNames = compiledSourceHashes.keys.filterNot { hashName -> hashName in currentNames }
    for (staleName in removedNames) {
      val retainedOutputs = outputsBySource.filterKeys { ownerName -> ownerName != staleName }
        .values.flatten().toSet()
      removeOutputs(outputDirectory, staleName, retainedOutputs)
      compiledSourceHashes.remove(staleName)
      outputsBySource.remove(staleName)
      diagnosticsBySource.remove(staleName)
    }

    val sourceWasRemoved = removedNames.isNotEmpty()
    val changedSources = sourceFiles.filter { sourceFile ->
      sourceWasRemoved ||
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
    if (sourceWasRemoved) {
      logger.info("A skill source was removed; recompiling the remaining sources once")
    }
    val failedSources = changedSources.filterNot { sourceFile ->
      compileSourceBatch(listOf(sourceFile), currentHashes, outputDirectory, setOf(sourceFile.name))
    }
    if (failedSources.isNotEmpty()) {
      logger.info(
        "Retrying {} failed external skill source(s) together with all {} sources",
        failedSources.size,
        sourceFiles.size,
      )
      compileSourceBatch(sourceFiles, currentHashes, outputDirectory, emptySet())
    }
    saveBuildManifest()
  }

  // Restores the incremental compile state written by a previous process.
  // A source is only trusted when its recorded hash still matches the file on
  // disk and every recorded output file exists under [outputDirectory]; the
  // rest recompile through the normal changed-source path. Any output file
  // that no restored source owns is deleted, so a partially restored cache
  // can never leave stale classes behind for the classloader to pick up.
  // Returns `true` when a manifest existed and parsed, even if it matched
  // nothing; only an unreadable manifest falls back to the clean rebuild.
  private fun restoreBuildManifest(sourceFiles: List<File>, outputDirectory: File): Boolean {
    if (!manifestFile.isFile) return false
    val manifest: Map<String, Any?> = try {
      JsonUtil.decodeMap(manifestFile.readText())
    } catch (readException: Exception) {
      logger.warn("Ignoring unreadable skill build manifest: {}", readException.message)
      return false
    }

    val manifestSources: Map<*, *> = manifest["sources"] as? Map<*, *> ?: return false
    val currentHashes: Map<String, String> =
      sourceFiles.associate { sourceFile -> sourceFile.name to hashSourceFile(sourceFile) }

    for (sourceFile in sourceFiles) {
      val sourceEntry: Map<*, *> = manifestSources[sourceFile.name] as? Map<*, *> ?: continue
      val recordedHash: String = sourceEntry["hash"] as? String ?: continue
      if (recordedHash != currentHashes.getValue(sourceFile.name)) continue

      val recordedOutputs: List<String> =
        (sourceEntry["outputs"] as? List<*>)?.filterIsInstance<String>().orEmpty()
      if (recordedOutputs.isEmpty()) continue
      if (recordedOutputs.any { relativePath -> !outputDirectory.resolve(relativePath).isFile }) continue

      compiledSourceHashes[sourceFile.name] = recordedHash
      outputsBySource[sourceFile.name] = recordedOutputs.toMutableSet()
      diagnosticsBySource[sourceFile.name] = decodeDiagnostics(sourceEntry["diagnostics"])
    }

    val ownedOutputs: Set<String> = outputsBySource.values.flatten().toSet()
    outputDirectory.walkTopDown().filter { cachedFile -> cachedFile.isFile }.forEach { cachedFile ->
      if (cachedFile.relativeTo(outputDirectory).path !in ownedOutputs) cachedFile.delete()
    }

    if (compiledSourceHashes.isNotEmpty()) {
      logger.debug(
        "Restored {} of {} external skill(s) from build cache; recompile skipped",
        compiledSourceHashes.size, sourceFiles.size
      )
    }
    return true
  }

  // Persists the incremental state so the next process start can skip recompiles
  private fun saveBuildManifest() {
    if (compiledSourceHashes.isEmpty()) {
      manifestFile.delete()
      return
    }

    val manifestSources: Map<String, Any> = compiledSourceHashes.keys.associateWith { sourceName ->
      mapOf(
        "hash" to compiledSourceHashes.getValue(sourceName),
        "outputs" to outputsBySource[sourceName].orEmpty().toList(),
        "diagnostics" to diagnosticsBySource[sourceName].orEmpty().map { diagnostic ->
          mapOf(
            "severity" to diagnostic.severity.name,
            "message" to diagnostic.message,
            "line" to diagnostic.line,
            "column" to diagnostic.column,
          )
        },
      )
    }

    try {
      manifestFile.writeText(
        JsonUtil.encodeMap(input = mapOf("version" to 1, "sources" to manifestSources), prettyPrint = true)
      )
    } catch (writeException: Exception) {
      logger.warn("Failed to write skill build manifest: {}", writeException.message)
    }
  }

  private fun decodeDiagnostics(rawDiagnostics: Any?): List<ExternalDiagnostic> =
    (rawDiagnostics as? List<*>).orEmpty().mapNotNull { rawDiagnostic ->
      val diagnostic: Map<*, *> = rawDiagnostic as? Map<*, *> ?: return@mapNotNull null
      val severity: ExternalDiagnosticSeverity =
        (diagnostic["severity"] as? String)
          ?.let { severityName -> runCatching { ExternalDiagnosticSeverity.valueOf(severityName) }.getOrNull() }
          ?: return@mapNotNull null
      ExternalDiagnostic(
        line = (diagnostic["line"] as? Number)?.toInt(),
        column = (diagnostic["column"] as? Number)?.toInt(),
        message = diagnostic["message"] as? String ?: "",
        severity = severity,
      )
    }

  // Compiles one source, or a whole batch of them, into .build-staging and
  // swaps the output into [outputDirectory] only when all of [batchSources]
  // compile. A success records the batch's outputs, hashes, and diagnostics
  // under every source name, so unchanged files pulled into the batch have
  // their output ownership refreshed too and outputsExist() cannot fail against
  // a stale record. A failure keeps the previous working classes (last-good);
  // only [failureOwners] then record their new hash beside the batch's
  // diagnostics for them, and an empty set leaves every source's record as the
  // single-source pass left it.
  private fun compileSourceBatch(
    batchSources: List<File>,
    currentHashes: Map<String, String>,
    outputDirectory: File,
    failureOwners: Set<String>
  ): Boolean {
    wipeOutputDirectory(stagingDirectory)
    val compileResult = skillCompiler.compile(
      outputDirectory = stagingDirectory,
      sourceFiles = batchSources,
      runtimeClasspath = runtimeClasspath(),
      additionalClasspath = listOf(outputDirectory),
    )
    compileResult.compileWarnings.forEach { compileWarning ->
      logger.warn("External skill compile warning: {}", compileWarning)
    }

    val batchNames = batchSources.map { sourceFile -> sourceFile.name }.toSet()
    val diagnosticsByBatchSource = attributeDiagnostics(compileResult.diagnostics, batchNames)
    if (!compileResult.isSuccess) {
      for (sourceName in failureOwners) {
        compiledSourceHashes[sourceName] = currentHashes.getValue(sourceName)
        diagnosticsBySource[sourceName] = diagnosticsByBatchSource.getValue(sourceName)
      }
      logger.error(
        "External skill batch [{}] failed to compile; keeping previous versions: {}",
        batchNames.joinToString(),
        compileResult.compileErrors.joinToString(separator = "\n"),
      )
      stagingDirectory.deleteRecursively()
      return false
    }

    val stagedOutputs = stagingDirectory.walkTopDown().filter { stagedFile -> stagedFile.isFile }.toList()
    val outputsByBatchSource = claimStagedOutputs(stagedOutputs, batchNames)
    val retainedOutputs = outputsBySource.filterKeys { ownerName -> ownerName !in batchNames }
      .values.flatten().toSet()

    for (sourceName in batchNames) {
      removeOutputs(outputDirectory, sourceName, retainedOutputs)
    }
    for (stagedFile in stagedOutputs) {
      val relativePath = stagedFile.relativeTo(stagingDirectory).path
      val targetFile = outputDirectory.resolve(relativePath)
      targetFile.parentFile.mkdirs()
      stagedFile.copyTo(targetFile, overwrite = true)
    }
    for (sourceName in batchNames) {
      outputsBySource[sourceName] = outputsByBatchSource.getValue(sourceName)
      compiledSourceHashes[sourceName] = currentHashes.getValue(sourceName)
      diagnosticsBySource[sourceName] = diagnosticsByBatchSource.getValue(sourceName)
    }
    stagingDirectory.deleteRecursively()
    return true
  }

  // Attributes each staged .class file to the batch source that produced it: a
  // file facade (`NameKt`) or a top-level type (`Name`, `Name$Nested`) carries
  // the source's base name. A class named unlike its file is shared by the
  // whole batch instead, so no source's record can leave it orphaned for the
  // manifest's ownership sweep to delete.
  private fun claimStagedOutputs(
    stagedOutputs: List<File>, batchNames: Set<String>
  ): Map<String, MutableSet<String>> {
    val claimed: Map<String, MutableSet<String>> = batchNames.associateWith { mutableSetOf() }

    for (stagedFile in stagedOutputs) {
      val relativePath = stagedFile.relativeTo(stagingDirectory).path
      val classBase = stagedFile.name.removeSuffix(".class")
      val owners = batchNames.filter { name ->
        val sourceBase = name.removeSuffix(".kt")
        classBase == sourceBase ||
          classBase == sourceBase + "Kt" ||
          classBase.startsWith("$sourceBase$") ||
          classBase.startsWith(sourceBase + "Kt$")
      }

      val recipients = owners.ifEmpty { batchNames }
      for (name in recipients)
        claimed.getValue(name).add(relativePath)
    }
    return claimed
  }

  // Files each diagnostic under the batch source its path names, so a broken
  // file's errors stay on that file; a diagnostic the compiler left unlocated
  // (or that names an outside file) is reported against every source in the
  // batch, which keeps a lone failed file's own diagnostics on it.
  private fun attributeDiagnostics(
    diagnostics: List<ExternalDiagnostic>, batchNames: Set<String>
  ): Map<String, List<ExternalDiagnostic>> {
    val attributed: Map<String, MutableList<ExternalDiagnostic>> = batchNames.associateWith { mutableListOf() }

    for (diagnostic in diagnostics) {
      val diagnosticPath = diagnostic.path
      val sourceName =
        if (diagnosticPath == null) null
        else batchNames.firstOrNull { name -> File(diagnosticPath).name == name }
      val recipients: Collection<String> = if (sourceName == null) batchNames else listOf(sourceName)
      for (name in recipients) attributed.getValue(name).add(diagnostic)
    }
    return attributed
  }

  private fun outputsExist(outputDirectory: File, sourceName: String): Boolean {
    val producedOutputs = outputsBySource[sourceName] ?: return false
    return producedOutputs.all { relativePath -> outputDirectory.resolve(relativePath).isFile }
  }

  // Deletes the .class files [sourceName] owns, skipping any [retainedOutputs]
  // another source still claims, so a shared output survives its owner's
  // removal and keeps a surviving skill loadable.
  private fun removeOutputs(outputDirectory: File, sourceName: String, retainedOutputs: Set<String> = emptySet()) {
    for (relativePath in outputsBySource[sourceName].orEmpty()) {
      if (relativePath in retainedOutputs) continue
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
          logger.debug("Registered external skill '{}'", skillName)
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
 * deploy sources and read the skills directory. Mirrors the [SkillRegistry]
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