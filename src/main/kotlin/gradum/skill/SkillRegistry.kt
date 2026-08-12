/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SkillRegistry.kt  2026-08-12 12:38:25 Changed by gwy
 */

package gradum.skill

import gradum.Provider
import gradum.ToolMode
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.lang.reflect.Modifier
import java.net.URI
import java.net.URL
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private val logger: Logger = LoggerFactory.getLogger("SkillRegistry")

/**
 * Central registry for all available skills.
 *
 * Discovers [Skill] implementations at runtime by scanning the
 * `gradum.skill` package for concrete classes extending [Skill].
 */
object SkillRegistry {

  private const val SKILL_PACKAGE = "gradum.skill"

  private val registeredSkills: MutableMap<String, Skill> = mutableMapOf()

  init {
    discoverSkills()
  }

  /** Returns all registered skill instances. */
  fun getAllSkills(): Collection<Skill> {
    return registeredSkills.values
  }

  /** Returns the skill registered under [skillName], or null. */
  fun getSkill(skillName: String): Skill? {
    return registeredSkills[skillName]
  }

  /**
   * Returns function schemas for every skill that allows the given
   * [toolMode].
   *
   * The filter mirrors the runtime gate in [gradum.agent.Agent] — both
   * delegate to [Skill.allows]. The two checks must agree: a tool whose
   * schema is in the LLM's list must pass the runtime gate, and a tool
   * left out of the list must still be gated (the model can hallucinate
   * calls regardless of the schema filter).
   */
  fun getSchemas(
    modelName: String = "",
    toolMode: ToolMode = ToolMode.AGENT,
    provider: Provider = Provider.OLLAMA
  ): List<Map<String, Any>> {
    val schemaContext = SkillContext(toolMode, "", provider, modelName)
    return registeredSkills.values
      .filter { skill: Skill -> skill.allows(toolMode) }
      .map { skill: Skill -> skill.getSchema(schemaContext) }
  }

  private fun registerSkill(skillInstance: Skill) {
    registeredSkills[skillInstance.skillName] = skillInstance
    logger.info("Registered skill: ${skillInstance.skillName} (${skillInstance.alias})")
  }

  private fun discoverSkills() {
    val discoveredClasses = findClassesInPackage()

    for (skillClass in discoveredClasses) {
      if (!Skill::class.java.isAssignableFrom(skillClass)) continue

      try {
        val skillInstance = skillClass.getDeclaredConstructor().newInstance()
        if (skillInstance is Skill) {
          registerSkill(skillInstance)
        }
      } catch (instantiationException: Exception) {
        logger.warn("Failed to instantiate skill class: ${skillClass.name}", instantiationException)
      }
    }

    if (registeredSkills.isEmpty())
      logger.warn("No skills discovered via classpath scanning")
    else
      logger.info("Discovered ${registeredSkills.size} skills via classpath scanning")
  }

  private fun findClassesInPackage(): List<Class<*>> {
    val packageName = SKILL_PACKAGE

    val classLoader = Thread.currentThread().contextClassLoader
    val packagePath = packageName.replace('.', '/')
    val discoveredClasses = mutableListOf<Class<*>>()

    val packageResources = classLoader.getResources(packagePath)
    while (packageResources.hasMoreElements()) {
      val resourceUrl: URL = packageResources.nextElement()
      val resourceUri: URI = resourceUrl.toURI()

      when (resourceUri.scheme) {
        "file" -> {
          val filePath = Paths.get(resourceUri)
          discoveredClasses.addAll(findClassesInDirectory(filePath))
        }

        "jar" -> {
          discoveredClasses.addAll(findClassesInJar(resourceUri, packagePath))
        }
      }
    }

    return discoveredClasses
  }

  private fun findClassesInDirectory(targetDirectory: Path): List<Class<*>> {
    val discoveredClasses = mutableListOf<Class<*>>()

    Files.walk(targetDirectory).use { paths ->
      paths.filter { it.toString().endsWith(".class") }
        .forEach { classFilePath ->
          loadConcreteClass(getClassName(classFilePath, targetDirectory))
            ?.let { discoveredClasses.add(it) }
        }
    }
    return discoveredClasses
  }

  private fun findClassesInJar(jarUri: URI, packagePath: String): List<Class<*>> {
    val discoveredClasses = mutableListOf<Class<*>>()

    try {
      val jarPathString = jarUri.toString().removePrefix("jar:").removePrefix("file:")
      val jarFilePath = jarPathString.substringBeforeLast("!")

      FileSystems.newFileSystem(Paths.get(jarFilePath), emptyMap<String, Nothing>()).use { fileSystem ->
        val pathInJar = fileSystem.getPath(packagePath)
        Files.walk(pathInJar).use { paths ->
          paths.filter { it.toString().endsWith(".class") }
            .forEach { classFilePath ->
              val className = classFilePath.toString()
                .removePrefix("/")
                .replace('/', '.')
                .removeSuffix(".class")

              loadConcreteClass(className)?.let { discoveredClasses.add(it) }
            }
        }
      }
    } catch (jarScanException: Exception) {
      logger.debug("Failed to scan JAR for skills", jarScanException)
    }

    return discoveredClasses
  }

  /**
   * Loads [className] and returns it only when it is a concrete (non-interface,
   * non-abstract) class, or null when unloadable. Shared by the directory and
   * JAR scanners so both apply the identical filter.
   */
  private fun loadConcreteClass(className: String?): Class<*>? {
    if (className == null) return null
    return try {
      val matchedClass: Class<*> = Class.forName(className)
      if (!matchedClass.isInterface && !Modifier.isAbstract(matchedClass.modifiers))
        matchedClass
      else null
    } catch (loadException: ClassNotFoundException) {
      logger.debug("Skipping unloadable class $className: ${loadException.message}", loadException)
      null
    }
  }

  private fun getClassName(classFilePath: Path, baseDirectory: Path): String? {
    val packageName = SKILL_PACKAGE
    val relativePath = baseDirectory.relativize(classFilePath).toString()
    val className = relativePath
      .replace(File.separatorChar, '.')
      .removeSuffix(".class")

    return if (className.isNotEmpty()) "$packageName.$className" else null
  }
}
