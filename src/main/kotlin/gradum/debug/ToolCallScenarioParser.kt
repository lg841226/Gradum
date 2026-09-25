/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ToolCallScenarioParser.kt  2026-08-31 19:21:55 Changed by gwy
 */

package gradum.debug

import gradum.client.ToolCallEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.w3c.dom.Attr
import org.w3c.dom.Element
import org.w3c.dom.NamedNodeMap
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory

private val logger: Logger = LoggerFactory.getLogger("ToolCallScenarioParser")

/** A single scenario step: either an AI reply segment or a tool call. */
sealed interface ScenarioStep {
  /**
   * AI reply narration — what the LLM says between tool calls on a real
   * turn. In playback, it is carried over from the Markdown document
   * (`<tt>` segments) so text and tools interleave exactly like a turn.
   */
  data class AiReply(val content: String) : ScenarioStep
}

/**
 * A single parsed tool-call entry plus its expected outcome. Mirrors
 * [ToolCallEntry] but carries the optional `expect` hint from the XML
 * so error-path scenarios can assert on success vs failure.
 */
data class ParsedToolCall(
  val functionName: String,
  val functionArguments: Map<String, JsonElement>,
  val expectSuccess: Boolean,
) : ScenarioStep

/** Result of parsing a `.tls.xml` scenario file. */
data class ToolCallScenario(
  val steps: List<ScenarioStep>,
  val scenarioName: String,
) {
  /** Only the tool steps, in document order. */
  val toolCalls: List<ParsedToolCall> get() = steps.filterIsInstance<ParsedToolCall>()
}

/**
 * Simplified XML story-board for the tool-call debug playback mode.
 *
 * The developer plays the role of the LLM: instead of the model deciding
 * which tools to call, the intent is written by hand in a short, terse
 * XML document that reuses the real `executeSingleTool` pipeline on the
 * server. No LLM tokens are spent.
 *
 * Example:
 *
 * ```
 * <tls>
 *   <t nam="read_file" pth="src/main/kotlin/gradum/AgentConfiguration.kt" lin="10-30"/>
 *   <t nam="grep" pth="src/main" ptr="TODO"/>
 *   <t nam="write_file" pth="/tmp/out.txt" ctl="hello" exp="error"/>
 * </tls>
 * ```
 *
 * AI reply narration is not authored by hand here: it is carried over
 * from Markdown documents as `<tt>` segments, and assistant text on real
 * agent turns comes from the LLM stream. Hand-written scenarios declare
 * tool calls only.
 *
 * Two elements may be interleaved freely in document order, matching a
 * real agent turn:
 *  - `<tt>text</tt>`  — AI reply narration (produced by Markdown compilation)
 *  - `<t .../>`       — a real tool call (see attribute map below).
 *
 * The abbreviation map (attribute -> real argument key):
 *  - `nam` tool name            (real tool name, e.g. `read_file`)
 *  - `pth` path
 *  - `lin` lineRange           (`start-end`, see ReadFileSkill)
 *  - `ctl` content
 *  - `ptr` pattern             (grep pattern, glob pattern)
 *  - `dep` depth               (explore_project)
 *  - `cmd` command             (run_cmd)
 *  - `exp` expect              — `success` or `error` (assertion hint)
 */
object ToolCallScenarioParser {

  /** Parses a raw scenario XML string into a [ToolCallScenario]. */
  fun parse(rawXml: String): ToolCallScenario {
    val documentBuilderFactory = DocumentBuilderFactory.newInstance()

    try {
      documentBuilderFactory.setFeature(
        "http://apache.org/xml/features/nonvalidating/load-external-dtd", false
      )
    } catch (featureException: Exception) {
      logger.debug("Parser does not support the anti-DTD feature, parsing without it", featureException)
    }

    val documentBuilder = documentBuilderFactory.newDocumentBuilder()
    val document =
      try {
        documentBuilder.parse(ByteArrayInputStream(rawXml.toByteArray(charset = StandardCharsets.UTF_8)))
      } catch (parseException: Exception) {
        throw ToolCallScenarioParseException("Invalid scenario XML: ${parseException.message}", parseException)
      }

    val root = document.documentElement ?: throw ToolCallScenarioParseException("Scenario XML has no root element")
    if (root.tagName != "tls") {
      throw ToolCallScenarioParseException(
        "Expected root element <tls> but found <${root.tagName}>"
      )
    }

    val scenarioName: String = root.getAttribute("name").trim().ifEmpty {
      root.getAttribute("nam").trim()
    }

    val stepList = buildList {
      for (child: Element in childElements(parent = root)) {
        when (child.tagName) {
          "t" -> add(parseToolElement(child))
          "tt" -> add(parseReplyElement(child))
          else -> throw ToolCallScenarioParseException(
            "Unexpected element <${child.tagName}> inside <tls>; expected <t> or <tt>"
          )
        }
      }
    }

    if (stepList.isEmpty())
      throw ToolCallScenarioParseException("Scenario <tls> contains no <t> tool entries")

    return ToolCallScenario(steps = stepList, scenarioName = scenarioName)
  }

  private fun childElements(parent: Element): List<Element> {
    val children = parent.childNodes
    return (0 until children.length).mapNotNull { index ->
      children.item(index) as? Element
    }
  }

  private fun parseReplyElement(element: Element): ScenarioStep.AiReply {
    val content: String = element.getAttribute("msg").trim().ifEmpty {
      element.textContent.orEmpty().trim()
    }
    if (content.isEmpty())
      throw ToolCallScenarioParseException("A <tt> element is missing the required msg=\"...\" attribute")

    return ScenarioStep.AiReply(content)
  }

  private fun parseToolElement(element: Element): ParsedToolCall {
    val functionName: String = element.getAttribute("nam").trim()
    if (functionName.isEmpty())
      throw ToolCallScenarioParseException("A <t> element is missing the required nam=\"tool name\" attribute")

    val argumentsMap: Map<String, JsonElement> = buildArgumentsMap(element)
    val expectRaw: String = element.getAttribute("exp").trim()
    val expectSuccess: Boolean =
      when (expectRaw.lowercase()) {
        "" -> true
        "success" -> true
        "error" -> false
        else -> throw ToolCallScenarioParseException(
          "Invalid exp=\"$expectRaw\" on tool <$functionName>; use \"success\" or \"error\""
        )
      }

    return ParsedToolCall(
      functionName = functionName,
      expectSuccess = expectSuccess,
      functionArguments = argumentsMap
    )
  }

  private fun buildArgumentsMap(element: Element): Map<String, JsonElement> {
    val attributes: NamedNodeMap = element.attributes
    return buildMap {
      for (attributeIndex in 0 until attributes.length) {
        val attribute = attributes.item(attributeIndex) as Attr
        if (attribute.name == "nam" || attribute.name == "exp") continue
        put(
          ARGUMENT_ALIASES[attribute.name] ?: attribute.name,
          parseArgumentValue(rawValue = attribute.value)
        )
      }
    }
  }

  private fun parseArgumentValue(rawValue: String): JsonElement {
    val trimmed: String = rawValue.trim()
    if (trimmed.firstOrNull() !in ARRAY_OR_OBJECT_MARKERS)
      return JsonPrimitive(rawValue)

    return runCatching { Json.parseToJsonElement(string = trimmed) }
      .getOrElse { JsonPrimitive(rawValue) }
  }

  private val ARRAY_OR_OBJECT_MARKERS: Set<Char> = setOf('[', '{')

  private val ARGUMENT_ALIASES: Map<String, String> = mapOf(
    "pth" to "path",
    "lin" to "lineRange",
    "ctl" to "content",
    "ptr" to "pattern",
    "dep" to "depth",
    "cmd" to "command",
  )
}

/** Thrown when a scenario XML file cannot be parsed. */
class ToolCallScenarioParseException(message: String, cause: Throwable? = null) :
  Exception(message, cause)
