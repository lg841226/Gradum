/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SystemPromptLoader.kt  2026-08-25 22:50:20 Changed by gwy
 */

package gradum.agent

import gradum.AgentConfiguration
import gradum.PromptVariant
import gradum.SchemaVariant
import gradum.ToolMode
import org.slf4j.LoggerFactory

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
   * @param taskDescription When non-null, appended as a "Sub-Agent Task"
   *   section at the end of the prompt.
   * @return The fully assembled system prompt string.
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
      logger.warn("Could not load system prompt, reason: ${promptLoadException.message}", promptLoadException)
      "You are a helpful AI assistant. You can't call any tool and report it"
    }

    val modeSectionPath: String = when (configuration.toolMode) {
      ToolMode.READ_ONLY -> "/prompts/modes/read_only.xml"
      ToolMode.EDIT -> "/prompts/modes/edit.xml"
      ToolMode.AGENT -> "/prompts/modes/agent.xml"
    }
    val modeSection: String = try {
      Agent::class.java.getResourceAsStream(modeSectionPath)?.use { stream ->
        stream.reader(Charsets.UTF_8).readText()
      } ?: "You have no tools available in this session."
    } catch (modeSectionException: Exception) {
      logger.warn("Could not load mode section $modeSectionPath: ${modeSectionException.message}", modeSectionException)
      "You have no tools available in this session."
    }

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
        !insideConditional && trimmedLine.startsWith(prefix = "<!-- if ") && trimmedLine.endsWith(" -->") -> {
          val conditionName = trimmedLine
            .removePrefix("<!-- if ")
            .removeSuffix(" -->")
            .trim()
          val conditionVariant = try {
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

  /**
   * Loads red-line keywords from the classpath resource
   * `/red_line_keywords.txt`.
   */
  fun loadRedLineKeywords(): List<String> {
    return try {
      Agent::class.java.getResourceAsStream("/red_line_keywords.txt")?.use { stream ->
        stream.reader(Charsets.UTF_8).readLines().map { it.trim() }
          .filter { it.isNotBlank() && !it.startsWith(prefix = "#") }
      } ?: run {
        logger.info("red_line_keywords.txt not found on classpath, red line detection disabled")
        emptyList()
      }
    } catch (keywordLoadException: Exception) {
      logger.warn("Failed to load red_line_keywords.txt: ${keywordLoadException.message}", keywordLoadException)
      emptyList()
    }
  }
}
