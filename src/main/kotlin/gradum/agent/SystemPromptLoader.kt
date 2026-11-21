package gradum.agent

import gradum.AgentConfiguration
import gradum.PromptVariant
import gradum.SchemaVariant
import gradum.mode.AgentMode
import gradum.mode.ModeRegistry
import org.slf4j.LoggerFactory
import java.io.File

private val logger = LoggerFactory.getLogger("SystemPromptLoader")

/**
 * Loads, filters, and substitutes the system prompt for an agent session.
 *
 * Responsibilities:
 * - Resolve the prompt variant (CLOUD / LOCAL / AUTO).
 * - Load the primary system prompt and mode-specific section from the
 *   classpath.
 * - Substitute template variables (`{{OS}}`, `{{MODE}}`, `{{SCHEMA_VARIANT}}`).
 * - Filter conditional sections (`<!-- if SIMPLE/FULL -->`).
 * - Append the sub-agent task description when present.
 */
class SystemPromptLoader(private val configuration: AgentConfiguration) {

  /**
   * Loads and assembles the system prompt for the current session.
   *
   *   section at the end of the prompt.
   *
   * Parameters:
   * - taskDescription: When non-null, appended as a "Sub-Agent Task"
   *
   * Returns The fully assembled system prompt string..
   */
  fun load(taskDescription: String?): String {
    val resolvedVariant: PromptVariant = when (configuration.promptVariant) {
      PromptVariant.CLOUD, PromptVariant.LOCAL -> configuration.promptVariant
      PromptVariant.AUTO -> PromptVariant.resolveAuto(configuration.provider)
    }
    val primaryPath: String = when (resolvedVariant) {
      PromptVariant.CLOUD -> "/prompts/system/cloud.xml"
      else -> "/prompts/system/local.xml"
    }

    val promptContent: String = try {
      Agent::class.java.getResourceAsStream(primaryPath)?.use { stream ->
        stream.reader(Charsets.UTF_8).readText()
      }
        ?: throw IllegalStateException("No system prompt found on classpath at $primaryPath")
    } catch (promptLoadException: Exception) {
      logger.warn("Could not load system prompt, reason: ${promptLoadException.message}")
      "You are a helpful AI assistant. You can't call any tool and report it"
    }

    val mode: AgentMode = ModeRegistry.resolve(configuration.toolMode)
    val modeSection: String = loadModeSection(mode.prompt)

    val osName: String = System.getProperty("os.name")
    val osVersion: String = System.getProperty("os.version")
    val schemaVariant: SchemaVariant = SchemaVariant.resolve(configuration.modelName)
    val substitutedContent: String = promptContent
      .replace("{{OS}}", "$osName $osVersion")
      .replace("{{MODE}}", modeSection)
      .replace("{{SCHEMA_VARIANT}}", schemaVariant.name)

    val filteredContent: String = filterConditionalSections(substitutedContent, schemaVariant)

    return taskDescription?.let { task ->
      "$filteredContent\n\n### Sub-Agent Task\n$task"
    } ?: filteredContent
  }

  /**
   * Loads the persona fragment for a mode's `prompt` reference: a user
   * file `~/.gradum/modes/<prompt>.xml` wins, then the packaged classpath
   * resource `/prompts/modes/<prompt>.xml`, then a plain-text fallback so
   * a dangling reference degrades the prompt instead of failing the run.
   */
  private fun loadModeSection(prompt: String): String {
    val userFile: File = File(ModeRegistry.userModesDir(), "$prompt.xml")
    if (userFile.isFile) {
      try {
        return userFile.readText(Charsets.UTF_8)
      } catch (readException: Exception) {
        logger.warn("Could not read ${userFile.absolutePath}: ${readException.message}")
      }
    }
    val resourcePath = "/prompts/modes/$prompt.xml"
    return try {
      Agent::class.java.getResourceAsStream(resourcePath)?.use { stream ->
        stream.reader(Charsets.UTF_8).readText()
      } ?: "You have no tools available in this session."
    } catch (modeSectionException: Exception) {
      logger.warn("Could not load mode section $resourcePath: ${modeSectionException.message}")
      "You have no tools available in this session."
    }
  }

  /**
   * Filters conditional sections in the prompt based on [SchemaVariant].
   *
   * Sections wrapped in `<!-- if SIMPLE -->...<!-- endif -->` are kept
   * only for [SchemaVariant.SIMPLE]; `<!-- if FULL -->` only for
   * [SchemaVariant.FULL].
   *
   * Edge cases: nested conditionals are NOT supported (outer block wins);
   * malformed tags (missing endif) drop the section; empty sections are
   * preserved.
   */
  fun filterConditionalSections(content: String, schemaVariant: SchemaVariant): String {
    if (content.isBlank()) return content

    val outputBuffer = StringBuilder()
    val contentLines = content.lines()
    var lineIndex = 0
    var skipUntilEndif = false
    var insideConditional = false

    while (lineIndex < contentLines.size) {
      val currentLine = contentLines[lineIndex]
      val trimmedLine = currentLine.trim()

      when {
        !insideConditional && trimmedLine.startsWith(prefix = "<!-- if ") && trimmedLine.endsWith(suffix = " -->") -> {
          val conditionName = trimmedLine
            .removePrefix("<!-- if ")
            .removeSuffix(" -->")
            .trim()
          val conditionVariant =
            try {
              SchemaVariant.valueOf(conditionName)
            } catch (variantException: IllegalArgumentException) {
              logger.debug("Unknown conditional variant '$conditionName': ${variantException.message}", variantException)
              null
            }
          insideConditional = true
          skipUntilEndif = conditionVariant != schemaVariant
          lineIndex++
        }

        insideConditional && trimmedLine == "<!-- endif -->" -> {
          insideConditional = false
          skipUntilEndif = false
          lineIndex++
        }

        insideConditional -> {
          if (!skipUntilEndif)
            outputBuffer.appendLine(value = currentLine)
          lineIndex++
        }

        else -> {
          outputBuffer.appendLine(value = currentLine)
          lineIndex++
        }
      }
    }

    return outputBuffer.toString().trimEnd()
  }
}
