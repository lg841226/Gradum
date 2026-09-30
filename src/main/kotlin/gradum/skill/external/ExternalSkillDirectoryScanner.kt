package gradum.skill.external

import gradum.skill.Skill
import gradum.skill.SkillRegistry
import gradum.skill.SkillStore
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

internal const val SKILLS_DIRECTORY_NAME: String = "skills"

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
 * Incremental compilation. Two hash tables keyed by source file name drive the
 * pass: compiledSourceHashes holds the content SHA-256 of every file at its
 * last successful compile, and outputsBySource holds the .class files that
 * compile produced. A source whose hash still matches keeps its .class output
 * untouched; a source whose recorded output went missing, a new source, or an
 * edited source is recompiled; a deleted source has its output removed. When
 * nothing changed the compiler is skipped entirely. The first pass inside a
 * process starts from a clean .build because no output has been attributed
 * yet. Each changed source is compiled on its own into .build-staging, which
 * lives outside .build so the classloader never scans it, with .build on the
 * classpath so unchanged skills still resolve. Only a successful compile is
 * swapped into .build: a failed compile keeps the previous working .class
 * files (last-good) instead of dropping every skill. lastCompiledSourceNames
 * records what the most recent pass compiled, for tests and logs.
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

  @Synchronized
  fun reconcile() {
    if (!skillsDirectory.exists()) {
      unloadAllExternal()
      return
    }

    val sourceFiles = listKtSources()
    val outputDirectory = skillsDirectory.resolve(".build")
    if (sourceFiles.isEmpty()) {
      wipeOutputDirectory(outputDirectory)
      compiledSourceHashes.clear()
      outputsBySource.clear()
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
    compiledSourceHashes[sourceFile.name] = sourceHash
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
