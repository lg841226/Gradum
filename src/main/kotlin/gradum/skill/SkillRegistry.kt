/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SkillRegistry.kt  2026-07-04 11:56:04 Changed by gwy
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
 * Uses classpath scanning to discover Skill implementations at runtime.
 * Skills are automatically discovered by scanning the `gradum.skill` package
 * for concrete classes that extend [Skill]. No manual registration required.
 *
 * @see Skill
 */
object SkillRegistry {

    private const val SKILL_PACKAGE = "gradum.skill"

    private val registeredSkills: MutableMap<String, Skill> = mutableMapOf()

    init {
        discoverSkills()
    }

    fun getAllSkills(): Collection<Skill> {
        return registeredSkills.values
    }

    /**
     * Retrieves a skill by its name.
     *
     * @param skillName the unique identifier of the skill
     * @return the Skill instance, or null if not found
     */
    fun getSkill(skillName: String): Skill? {
        return registeredSkills[skillName]
    }

    /**
     * Returns function schemas for every skill that allows the given
     * [toolMode]. Used to build the tools definition sent to the LLM.
     *
     * The filter is `toolMode in skill.allowedToolModes` — the same
     * membership check [gradum.agent.Agent] uses at runtime to gate
     * tool calls. The two checks must agree: if a tool's schema is in
     * the LLM's tool list, the LLM can call it, so the runtime gate
     * must not reject it. If a tool is not in the LLM's tool list, the
     * runtime gate might still be hit (LLM-hallucinated calls), so the
     * runtime gate must not let it through.
     *
     * @param toolMode Which mode the LLM is being started in. Defaults
     *   to [ToolMode.AGENT] (unrestricted).
     * @param provider Which LLM backend is in use. Passed through to
     *   each skill's [Skill.getSchema] so that provider-aware skills
     *   (e.g. [EditFileSkill]) can return a schema tailored to local
     *   or cloud models.
     * @param modelName Model name string for capability inference.
     * @return list of schema maps from every registered skill that
     *   declares [toolMode] in its [Skill.allowedToolModes]
     */
    fun getSchemas(
        toolMode: ToolMode = ToolMode.AGENT,
        provider: Provider = Provider.OLLAMA,
        modelName: String = "",
    ): List<Map<String, Any>> {
        val context = SkillContext(toolMode, "", provider, modelName)
        return registeredSkills.values
            .filter { skill: Skill -> toolMode in skill.allowedToolModes }
            .map { skill: Skill -> skill.getSchema(context) }
    }

    /**
     * Registers a single skill in the registry.
     *
     * @param skill the skill instance to register
     */
    private fun registerSkill(skill: Skill) {
        registeredSkills[skill.skillName] = skill
        logger.info("Registered skill: ${skill.skillName} (${skill.alias})")
    }

    /**
     * Discovers and registers all available skills via classpath scanning.
     *
     * Scans the `gradum.skill` package for all concrete classes that
     * extend [Skill]. Each discovered class is instantiated via its
     * no-argument constructor and registered.
     *
     * This approach requires no manual configuration — just place your
     * skill class in the `gradum.skill` package (or a sub-package) and
     * it will be automatically discovered.
     */
    private fun discoverSkills() {
        val skillClasses = findClassesInPackage()

        for (clazz in skillClasses) {
            // Only process Skill subclasses
            if (!Skill::class.java.isAssignableFrom(clazz)) continue

            try {
                val instance = clazz.getDeclaredConstructor().newInstance()
                if (instance is Skill) {
                    registerSkill(instance)
                }
            } catch (exception: Exception) {
                logger.warn("Failed to instantiate skill class: ${clazz.name}", exception)
            }
        }

        if (registeredSkills.isEmpty())
            logger.warn("No skills discovered via classpath scanning")
        else
            logger.info("Discovered ${registeredSkills.size} skills via classpath scanning")
    }

    /**
     * Finds all concrete classes in the given package.
     *
     * @return list of Class objects for concrete classes in the package
     */
    private fun findClassesInPackage(): List<Class<*>> {
        val packageName = SKILL_PACKAGE

        val classLoader = Thread.currentThread().contextClassLoader
        val packagePath = packageName.replace('.', '/')
        val classes = mutableListOf<Class<*>>()

        val resources = classLoader.getResources(packagePath)
        while (resources.hasMoreElements()) {
            val resource: URL = resources.nextElement()
            val uri: URI = resource.toURI()

            when (uri.scheme) {
                "file" -> {
                    val filePath = Paths.get(uri)
                    classes.addAll(findClassesInDirectory(filePath))
                }

                "jar" -> {
                    classes.addAll(findClassesInJar(uri, packagePath))
                }
            }
        }

        return classes
    }

    /**
     * Finds classes in a directory (for development/IDE environments).
     */
    private fun findClassesInDirectory(directory: Path): List<Class<*>> {
        val classes = mutableListOf<Class<*>>()

        Files.walk(directory).use { paths ->
            paths.filter { it.toString().endsWith(".class") }
                .forEach { classFile ->
                    if (getClassName(classFile, directory) != null) {
                        try {
                            val clazz = Class.forName(getClassName(classFile, directory))
                            if (!clazz.isInterface && !Modifier.isAbstract(clazz.modifiers)) {
                                classes.add(clazz)
                            }
                        } catch (_: ClassNotFoundException) {
                            // Skip classes that can't be loaded
                        }
                    }
                }
        }
        return classes
    }

    /**
     * Finds classes in a JAR file (for packaged environments).
     */
    private fun findClassesInJar(jarUri: URI, packagePath: String): List<Class<*>> {
        val classes = mutableListOf<Class<*>>()

        try {
            val jarPath = jarUri.toString().removePrefix("jar:").removePrefix("file:")
            val jarFile = jarPath.substringBeforeLast("!")

            FileSystems.newFileSystem(Paths.get(jarFile), emptyMap<String, Nothing>()).use { fs ->
                val pathInJar = fs.getPath(packagePath)
                Files.walk(pathInJar).use { paths ->
                    paths.filter { it.toString().endsWith(".class") }
                        .forEach { classFile ->
                            val className = classFile.toString()
                                .removePrefix("/")
                                .replace('/', '.')
                                .removeSuffix(".class")

                            try {
                                val clazz = Class.forName(className)
                                if (!clazz.isInterface && !Modifier.isAbstract(clazz.modifiers)) {
                                    classes.add(clazz)
                                }
                            } catch (_: ClassNotFoundException) {
                                // Skip classes that can't be loaded
                            }
                        }
                }
            }
        } catch (exception: Exception) {
            logger.debug("Failed to scan JAR for skills", exception)
        }

        return classes
    }

    /**
     * Converts a class file path to a fully qualified class name.
     */
    private fun getClassName(classFile: Path, baseDir: Path): String? {
        val packageName = SKILL_PACKAGE
        val relativePath = baseDir.relativize(classFile).toString()
        val className = relativePath
            .replace(File.separatorChar, '.')
            .removeSuffix(".class")

        return if (className.isNotEmpty()) "$packageName.$className" else null
    }
}
