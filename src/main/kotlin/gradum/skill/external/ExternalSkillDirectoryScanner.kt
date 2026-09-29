package gradum.skill.external

import gradum.skill.Skill
import gradum.skill.SkillRegistry
import gradum.skill.SkillStore
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** The subdirectory under `~/.gradum/` that holds drop-in `.kt` skills. */
internal const val SKILLS_DIRECTORY_NAME: String = "skills"

/**
 * Compiles every `.kt` skill under `~/.gradum/skills/` with
 * [ExternalSkillCompiler], loads the output with [ExternalSkillClassLoader],
 * and reconciles them into the [SkillStore] so each becomes callable from the
 * next agent turn.
 *
 * [reconcile] is the single idempotent entry point: it is run once at startup
 * (via [scan]) and again by [ExternalSkillDirectoryWatcher] on any change to
 * the directory. First run creates the directory and drops a `HelloSkill.kt`
 * starter so a developer sees the shape of a skill without reading the docs.
 */
class ExternalSkillDirectoryScanner(
  homeDirectory: File = File(System.getProperty("user.home")),
  private val skillStore: SkillStore = SkillRegistry
) {

  private val logger: Logger = LoggerFactory.getLogger("ExternalSkillDirectoryScanner")
  private val skillCompiler = ExternalSkillCompiler()

  /** The skills directory (`~/.gradum/skills/`). */
  val skillsDirectory: File = homeDirectory.resolve(".gradum").resolve(SKILLS_DIRECTORY_NAME)

  /**
   * Skills registered by this scanner, keyed by name. Only these are ever
   * unregistered on reload: built-in and MCP skills are never touched.
   */
  private val externalOwned: ConcurrentHashMap<String, Skill> = ConcurrentHashMap()

  /**
   * Runs the startup pass: ensure the directory exists (writing the starter
   * skill on first run) and then reconcile whatever is present.
   */
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
   * Recompiles the current `.kt` sources and reconciles them into the
   * registry: registers new/changed skills, and unregisters external skills
   * whose source file disappeared. A compilation failure leaves the registry
   * untouched (last-good) rather than dropping every skill.
   */
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
      unloadAllExternal()
      return
    }

    wipeOutputDirectory(outputDirectory)
    val compileResult = skillCompiler.compile(sourceFiles, outputDirectory, runtimeClasspath())
    compileResult.compileWarnings.forEach { compileWarning -> logger.warn("External skill compile warning: {}", compileWarning) }
    if (!compileResult.isSuccess) {
      logger.error(
        "External .kt skills failed to compile; keeping previously-loaded skills: {}",
        compileResult.compileErrors.joinToString(separator = "\n")
      )
      return
    }

    val loadedByName = loadSkillsByName(outputDirectory)
    unloadMissingExternal(loadedByName.keys)
    registerOrSkipLoaded(loadedByName)
  }

  /**
   * Returns a stable fingerprint of the current `.kt` sources, used by the
   * watcher to skip redundant reloads when nothing changed. Only source files
   * are considered: never the `.build` output, so compiled artifacts cannot
   * re-trigger a reload.
   */
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

  /** Unregisters external skills whose source file is no longer present. */
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

  /** Registers new skills, re-registers reloaded ones, skips name collisions. */
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

  /** Empties and recreates [outputDirectory] so stale `.class` files cannot survive a reload. */
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

/** A minimal, compilable example skill written on first run. */
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
