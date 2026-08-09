/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ToolCallScenarioParser.kt  2026-08-09 20:05:00 Changed by gwy
 */

package gradum.debug

import gradum.client.ToolCallEntry
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory

/** A single scenario step: either an AI reply segment or a tool call. */
sealed interface ScenarioStep {
  /**
   * Human-composed AI reply text. During a real agent run this is what the
   * LLM says between tool calls; in the debug playback the author types it
   * by hand so text and tools interleave the way they do on a real turn
   * (`<tt msg="..."/>`).
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
  /** Convenience: only the tool steps, in order. */
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
 *   <tt msg="Let me look at the configuration first."/>
 *   <t nam="read_file" pth="src/main/kotlin/gradum/AgentConfiguration.kt" lin="10-30"/>
 *   <tt msg="Found one TODO; searching for more."/>
 *   <t nam="grep" pth="src/main" pat="TODO"/>
 *   <t nam="save_file" pth="/tmp/out.txt" ctl="hello" exp="error"/>
 * </tls>
 * ```
 *
 * Two elements may be interleaved freely in document order, mixing the
 * AI's reply text with its tool calls exactly like a real agent turn:
 *  - `<tt msg="..."/>`  — AI reply segment rendered as assistant text.
 *  - `<t .../>`          — a real tool call (see attribute map below).
 *
 * All shorthand attributes are uniformly three letters, matching the
 * terse `<tls>/<t>/<tt>` element naming; every other attribute is passed
 * through verbatim under its own key, so full schema keys like
 * `include="*.kt"` or `caseSensitive="true"` just work as-is.
 *
 * The abbreviation map (attribute -> real argument key):
 *  - `msg` message text          (on `<tt>`, the AI reply content)
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

  private val DEFAULT_EXPECTED_SUCCESS = true

  /**
   * Parse a raw scenario XML string into a [ToolCallScenario].
   *
   * @throws ToolCallScenarioParseException when the XML cannot be read or
   * contains no tool call entries.
   */
  fun parse(rawXml: String): ToolCallScenario {
    val documentBuilderFactory = DocumentBuilderFactory.newInstance()
    // Best-effort hardening: the scenario is developer-authored, not remote,
    // but disabling external DTDs makes a copy-pasted fragment safe anyway.
    try {
      documentBuilderFactory.setFeature(
        "http://apache.org/xml/features/nonvalidating/load-external-dtd", false
      )
    } catch (_: Exception) {
      // Parser implementations without this feature just parse as-is.
    }

    val documentBuilder = documentBuilderFactory.newDocumentBuilder()
    val document = try {
      documentBuilder.parse(ByteArrayInputStream(rawXml.toByteArray(StandardCharsets.UTF_8)))
    } catch (parseException: Exception) {
      throw ToolCallScenarioParseException("Invalid scenario XML: ${parseException.message}", parseException)
    }

    val root = document.documentElement ?: throw ToolCallScenarioParseException("Scenario XML has no root element")
    if (root.tagName != "tls") {
      throw ToolCallScenarioParseException(
        "Expected root element <tls> but found <${root.tagName}>"
      )
    }

    val toolName = root.getAttribute("name").trim().ifEmpty {
      root.getAttribute("nam").trim()
    }

    val calls: MutableList<ScenarioStep> = mutableListOf()
    val rootChildren = root.childNodes
    for (index in 0 until rootChildren.length) {
      val child = rootChildren.item(index)
      if (child !is org.w3c.dom.Element) continue
      when (child.tagName) {
        "t" -> calls.add(parseToolElement(child))
        "tt" -> calls.add(parseReplyElement(child))
        else -> throw ToolCallScenarioParseException(
          "Unexpected element <${child.tagName}> inside <tls>; expected <t> or <tt>"
        )
      }
    }

    if (calls.isEmpty()) {
      throw ToolCallScenarioParseException("Scenario <tls> contains no <t> tool entries")
    }

    return ToolCallScenario(
      steps = calls,
      scenarioName = toolName,
    )
  }

  private fun parseReplyElement(element: org.w3c.dom.Element): ScenarioStep.AiReply {
    val content: String = element.getAttribute("msg").trim()
    if (content.isEmpty()) {
      // Fall back to the element's text so `<tt>free text</tt>` and
      // CDATA blocks also work, not just the `msg="..."` attribute.
      val textContent: String = element.textContent?.trim().orEmpty()
      if (textContent.isEmpty()) {
        throw ToolCallScenarioParseException("A <tt> element is missing the required msg=\"...\" attribute")
      }
      return ScenarioStep.AiReply(textContent)
    }
    return ScenarioStep.AiReply(content)
  }

  private fun parseToolElement(element: org.w3c.dom.Element): ParsedToolCall {
    val functionName: String = element.getAttribute("nam").trim()
    if (functionName.isEmpty()) {
      throw ToolCallScenarioParseException("A <t> element is missing the required nam=\"tool name\" attribute")
    }

    val argumentsMap: Map<String, JsonElement> = buildArgumentsMap(element)
    val expectRaw: String = element.getAttribute("exp").trim()
    val expectSuccess: Boolean = when (expectRaw.lowercase()) {
      "" -> DEFAULT_EXPECTED_SUCCESS
      "success" -> true
      "error" -> false
      else -> throw ToolCallScenarioParseException(
        "Invalid exp=\"$expectRaw\" on tool <$functionName>; use \"success\" or \"error\""
      )
    }

    return ParsedToolCall(
      functionName = functionName,
      functionArguments = argumentsMap,
      expectSuccess = expectSuccess,
    )
  }

  private fun buildArgumentsMap(element: org.w3c.dom.Element): Map<String, JsonElement> {
    val namedAttributes = element.attributes
    val attributesMap: MutableMap<String, JsonElement> = mutableMapOf()
    for (index in 0 until namedAttributes.length) {
      val attribute = namedAttributes.item(index) as org.w3c.dom.Attr
      val key: String = attribute.name
      val value: String = attribute.value
      when (key) {
        "nam", "exp" -> Unit // control attributes, not tool arguments
        else -> attributesMap[ARGUMENT_ALIASES[key] ?: key] = JsonPrimitive(value)
      }
    }
    return attributesMap
  }

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
class ToolCallScenarioParseException(
  message: String,
  cause: Throwable? = null,
) : Exception(message, cause)