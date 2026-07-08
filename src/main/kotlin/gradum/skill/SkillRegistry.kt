/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SkillRegistry.kt  2026-07-05 22:56:12 Changed by gwy
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
        val schemaContext = SkillContext(toolMode, "", provider, modelName)
        return registeredSkills.values
            .filter { skill: Skill -> toolMode in skill.allowedToolModes }
            .map { skill: Skill -> skill.getSchema(schemaContext) }
    }

    /**
     * Registers a single skill in the registry.
     *
     * @param skill the skill instance to register
     */
    private fun registerSkill(skillInstance: Skill) {
        registeredSkills[skillInstance.skillName] = skillInstance
        logger.info("Registered skill: ${skillInstance.skillName} (${skillInstance.alias})")
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

    /**
     * Finds all concrete classes in the given package.
     *
     * @return list of Class objects for concrete classes in the package
     */
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

    /**
     * Finds classes in a directory (for development/IDE environments).
     */
    private fun findClassesInDirectory(targetDirectory: Path): List<Class<*>> {
        val discoveredClasses = mutableListOf<Class<*>>()

        Files.walk(targetDirectory).use { paths ->
            paths.filter { it.toString().endsWith(".class") }
                .forEach { classFilePath ->
                    if (getClassName(classFilePath, targetDirectory) != null) {
                        try {
                            val matchedClass = Class.forName(getClassName(classFilePath, targetDirectory))
                            if (!matchedClass.isInterface && !Modifier.isAbstract(matchedClass.modifiers)) {
                                discoveredClasses.add(matchedClass)
                            }
                        } catch (_: ClassNotFoundException) {
                            // Skip classes that can't be loaded
                        }
                    }
                }
        }
        return discoveredClasses
    }

    /**
     * Finds classes in a JAR file (for packaged environments).
     */
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

                            try {
                                val matchedClass = Class.forName(className)
                                if (!matchedClass.isInterface && !Modifier.isAbstract(matchedClass.modifiers)) {
                                    discoveredClasses.add(matchedClass)
                                }
                            } catch (_: ClassNotFoundException) {
                                // Skip classes that can't be loaded
                            }
                        }
                }
            }
        } catch (jarScanException: Exception) {
            logger.debug("Failed to scan JAR for skills", jarScanException)
        }

        return discoveredClasses
    }

    /**
     * Converts a class file path to a fully qualified class name.
     */
    private fun getClassName(classFilePath: Path, baseDirectory: Path): String? {
        val packageName = SKILL_PACKAGE
        val relativePath = baseDirectory.relativize(classFilePath).toString()
        val className = relativePath
            .replace(File.separatorChar, '.')
            .removeSuffix(".class")

        return if (className.isNotEmpty()) "$packageName.$className" else null
    }
}
