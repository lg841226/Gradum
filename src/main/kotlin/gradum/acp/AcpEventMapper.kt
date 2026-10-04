package gradum.acp

import gradum.agent.GradumEventType
import kotlinx.serialization.json.JsonObject

/**
 * Translates Gradum agent events into ACP `session/update` payloads.
 * Pure and stateless: given the event type and its data, it returns the
 * update body (or null when the event maps to no ACP update).
 */
internal fun mapGradumEventToAcpUpdate(
  sessionId: String, eventType: String, eventData: Map<String, Any>
): JsonObject? {
  val update: JsonObject =
    when (eventType) {
      GradumEventType.RESPONSE.wireName,
      GradumEventType.THINKING.wireName -> {
        val content: String? = eventData["content"] as? String
        if (content.isNullOrEmpty()) return null
        val discriminator =
          if (eventType == GradumEventType.RESPONSE.wireName) "agent_message_chunk"
          else "agent_thought_chunk"
        acpAgentTextChunk(discriminator, content)
      }

      GradumEventType.TOOL_CALL_START.wireName -> {
        val callId: String = eventData["toolCallId"] as? String ?: return null
        val toolName: String = eventData["tool"] as? String ?: "tool"
        val arguments: Any? = eventData["arguments"]
        val displayKind: String? = (eventData["displayKind"] as? String)?.takeIf { it.isNotBlank() }
        val displayLabel: String? = (eventData["displayLabel"] as? String)?.takeIf { it.isNotBlank() }
        val displayParamKey: String? = (eventData["displayParamKey"] as? String)?.takeIf { it.isNotBlank() }
        acpToolCallStart {
          toolCallId = callId
          name = toolName
          title = buildToolCallTitle(toolName, displayLabel, displayParamKey, arguments)
          kind = displayKind ?: "other"
          rawInput = arguments
        }
      }

      GradumEventType.TOOL_CALL.wireName -> {
        val callId: String = eventData["toolCallId"] as? String ?: return null
        val success = eventData["success"] as? Boolean
        acpToolCallUpdate {
          toolCallId = callId
          status = if (success == true) "completed" else "failed"
          rawOutput = eventData["result"]
        }
      }

      else -> return null
    }

  return acpSessionUpdate(sessionId, update)
}

private const val TITLE_PARAM_MAX = 60

/**
 * Builds the ACP capsule title as `Label: value`, e.g. `Run command: npm test`.
 * [declaredLabel] / [declaredParamKey] come from the skill's ToolDisplay; when
 * absent (an external skill that declared nothing) the label is humanized from
 * [toolName] and the parameter is the first non-blank String argument.
 */
private fun buildToolCallTitle(
  toolName: String,
  declaredLabel: String?,
  declaredParamKey: String?,
  arguments: Any?
): String {
  val label: String = declaredLabel ?: humanizeToolName(toolName)
  val paramKey: String = declaredParamKey ?: firstStringArgumentKey(arguments) ?: return label
  val display: String =
    ((arguments as? Map<*, *>)?.get(paramKey) as? String)
      ?.trim()
      ?.takeIf { it.isNotEmpty() }
      ?: return label
  val clipped: String =
    if (display.length > TITLE_PARAM_MAX) display.take(TITLE_PARAM_MAX) + "…" else display
  return "$label: $clipped"
}

private fun humanizeToolName(toolName: String): String =
  toolName.split('_')
    .filter { word -> word.isNotEmpty() }
    .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

private fun firstStringArgumentKey(arguments: Any?): String? =
  (arguments as? Map<*, *>)?.entries
    ?.firstOrNull { (_, value) -> value is String && value.isNotBlank() }
    ?.key as? String
