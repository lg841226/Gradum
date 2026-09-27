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
  parent: ClassLoader, private val outputDirectory: File
) : URLClassLoader(arrayOf(outputDirectory.toURI().toURL()), parent) {

  private val logger: Logger = LoggerFactory.getLogger("ExternalSkillClassLoader")

  /**
   * Loads and instantiates every concrete [Skill] subclass reachable in this
   * loader. A class that fails to load or initialize is logged and skipped, so
   * one broken skill never blocks its siblings.
   */
  fun loadSkills(): List<Skill> {
    return loadSkillClasses().mapNotNull { skillClass ->
      try {
        val instance = skillClass.getDeclaredConstructor().newInstance()
        if (instance is Skill) instance else null
      } catch (instantiationException: Exception) {
        logger.warn("Failed to instantiate external skill class {}", skillClass.name, instantiationException)
        null
      }
    }
  }

  private fun loadSkillClasses(): List<Class<out Skill>> {
    val discovered = mutableListOf<Class<out Skill>>()
    val basePath = outputDirectory.toPath()
    if (!outputDirectory.exists()) return discovered

    Files.walk(basePath).use { paths ->
      paths.filter { path -> path.toString().endsWith(".class") }
        .forEach { classPath ->
          val className = basePath.relativize(classPath).toString()
            .removeSuffix(".class")
            .replace(File.separatorChar, '.')
          val loaded =
            try {
              Class.forName(className, true, this)
            } catch (loadException: Throwable) {
              logger.warn("Skipping unloadable external skill class {}", className, loadException)
              null
            }
          if (loaded != null && Skill::class.java.isAssignableFrom(loaded)
            && !loaded.isInterface && !Modifier.isAbstract(loaded.modifiers)
          ) {
            @Suppress("UNCHECKED_CAST")
            discovered += loaded as Class<out Skill>
          }
        }
    }
    return discovered
  }
}
