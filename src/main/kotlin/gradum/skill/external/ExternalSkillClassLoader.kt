package gradum.skill.external

import gradum.skill.Skill
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.nio.file.Files

/**
 * Loads compiled external `.kt` skills from a directory produced by
 * [ExternalSkillCompiler].
 *
 * The parent is the classloader that loaded [Skill], so parent-delegation
 * gives external classes access to the in-process `gradum.*` API while the
 * external skill classes themselves stay in this loader's own namespace. That
 * seam is what later enables hot-reloading: swapping this loader replaces a
 * skill without restarting the server.
 */
internal class ExternalSkillClassLoader(
  parentClassLoader: ClassLoader, private val outputDirectory: File
) : URLClassLoader(arrayOf(outputDirectory.toURI().toURL()), parentClassLoader) {

  private val logger: Logger = LoggerFactory.getLogger("ExternalSkillClassLoader")

  /**
   * Loads and instantiates every concrete [Skill] subclass reachable in this
   * loader. A class that fails to load or initialize is logged and skipped, so
   * one broken skill never blocks its siblings.
   */
  fun loadSkills(): List<Skill> {
    return loadSkillClasses().mapNotNull { skillClass ->
      try {
        val skillInstance = skillClass.getDeclaredConstructor().newInstance()
        if (skillInstance is Skill) skillInstance else null
      } catch (instantiationException: Exception) {
        logger.warn("Failed to instantiate external skill class {}", skillClass.name, instantiationException)
        null
      }
    }
  }

  private fun loadSkillClasses(): List<Class<out Skill>> {
    val discoveredClasses = mutableListOf<Class<out Skill>>()
    val basePath = outputDirectory.toPath()
    if (!outputDirectory.exists()) return discoveredClasses

    Files.walk(basePath).use { classFilePaths ->
      classFilePaths.filter { classFilePath -> classFilePath.toString().endsWith(".class") }
        .forEach { classPath ->
          val className = basePath.relativize(classPath).toString()
            .removeSuffix(".class")
            .replace(File.separatorChar, '.')
          val loadedClass =
            try {
              Class.forName(className, true, this)
            } catch (loadException: Throwable) {
              logger.warn("Skipping unloadable external skill class {}", className, loadException)
              null
            }
          if (loadedClass != null && Skill::class.java.isAssignableFrom(loadedClass)
            && !loadedClass.isInterface && !Modifier.isAbstract(loadedClass.modifiers)
          ) {
            @Suppress("UNCHECKED_CAST")
            discoveredClasses += loadedClass as Class<out Skill>
          }
        }
    }
    return discoveredClasses
  }
}
