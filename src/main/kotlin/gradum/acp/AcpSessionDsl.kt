package gradum.acp

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*

// Builder DSL for session-scoped ACP payloads:
// `session/new`, `session/set_config_option`, `session/prompt`,
// `session/cancel`, `session/update` notifications.
//
// Following [AcpCapabilityDsl] style: call sites are declarative, never touch
// `put(...)` or `JsonPrimitive(...)` directly; every `put` lives here in one
// `toJsonObject()` or builder function per payload shape.
//
// Shapes with named/optional fields are scope blocks (`acpConfigOption { ... }`);
// fixed-arity shapes are plain builder functions (`acpPromptResult("end_turn")`).
//
// Usage:
//     result = acpSessionNew {
//       sessionId = id
//       configOptions = listOf(buildModelConfigOption(model))
//     }
//     acpToolCallStart {
//       toolCallId = callId
//       name = toolName
//       rawInput = eventData["arguments"]
//     }

internal fun acpSessionNew(block: AcpSessionNewScope.() -> Unit): JsonObject =
  AcpSessionNewScope().apply(block).toJsonObject()

internal class AcpSessionNewScope {
  var sessionId: String = ""
  var configOptions: List<JsonObject> = emptyList()

  internal fun toJsonObject(): JsonObject = buildJsonObject {
    put("sessionId", JsonPrimitive(sessionId))
    put("configOptions", buildJsonArray { configOptions.forEach { add(it) } })
  }
}

internal fun acpConfigOptions(options: List<JsonObject>): JsonObject = buildJsonObject {
  put("configOptions", buildJsonArray { options.forEach { add(it) } })
}

// config_option_update session update: full option set with fresh values
internal fun acpConfigOptionUpdate(options: List<JsonObject>): JsonObject = buildJsonObject {
  put("sessionUpdate", JsonPrimitive("config_option_update"))
  put("configOptions", buildJsonArray { options.forEach { add(it) } })
}

// Select config option (model, mode, ...): every field maps to its wire key
internal fun acpConfigOption(block: AcpConfigOptionScope.() -> Unit): JsonObject =
  AcpConfigOptionScope().apply(block).toJsonObject()

internal class AcpConfigOptionScope {
  var id: String = ""
  var name: String = ""
  var category: String = ""
  var type: String = ""
  var currentValue: String = ""
  var options: List<JsonObject> = emptyList()

  internal fun toJsonObject(): JsonObject = buildJsonObject {
    put("id", JsonPrimitive(id))
    put("name", JsonPrimitive(name))
    put("type", JsonPrimitive(type))
    put("category", JsonPrimitive(category))
    put("currentValue", JsonPrimitive(currentValue))
    put("options", buildJsonArray { options.forEach { add(it) } })
  }
}

// One entry of a select config option: wire `value`, display `name`, optional `description`
internal fun acpOptionValue(value: String, name: String, description: String?): JsonObject = buildJsonObject {
  put("value", JsonPrimitive(value))
  put("name", JsonPrimitive(name))
  description?.let { put("description", JsonPrimitive(it)) }
}

internal fun acpPromptResult(stopReason: String): JsonObject = buildJsonObject {
  put("stopReason", JsonPrimitive(stopReason))
}

internal fun acpEmptyResult(): JsonObject = buildJsonObject {}

internal fun acpSessionUpdate(sessionId: String, update: JsonObject): JsonObject = buildJsonObject {
  put("sessionId", JsonPrimitive(sessionId))
  put("update", update)
}

internal fun acpAgentTextChunk(discriminator: String, content: String): JsonObject =
  buildJsonObject {
    put("sessionUpdate", JsonPrimitive(discriminator))
    put("content", buildJsonObject {
      put("type", JsonPrimitive("text"))
      put("text", JsonPrimitive(content))
    })
  }

internal fun acpToolCallStart(block: AcpToolCallStartScope.() -> Unit): JsonObject =
  AcpToolCallStartScope().apply(block).toJsonObject()

internal class AcpToolCallStartScope {
  var toolCallId: String = ""
  var name: String = ""
  var title: String = ""
  var kind: String = "other"
  var rawInput: Any? = null

  internal fun toJsonObject(): JsonObject = buildJsonObject {
    put("sessionUpdate", JsonPrimitive("tool_call"))
    put("toolCallId", JsonPrimitive(toolCallId))
    put("title", JsonPrimitive(title.ifBlank { "Executing $name" }))
    put("name", JsonPrimitive(name))
    put("kind", JsonPrimitive(kind))
    put("rawInput", jsonAnyToJson(rawInput))
  }
}

internal fun acpToolCallUpdate(block: AcpToolCallUpdateScope.() -> Unit): JsonObject =
  AcpToolCallUpdateScope().apply(block).toJsonObject()

internal class AcpToolCallUpdateScope {
  var status: String = ""
  var toolCallId: String = ""
  var rawOutput: Any? = null

  internal fun toJsonObject(): JsonObject = buildJsonObject {
    put("sessionUpdate", JsonPrimitive("tool_call_update"))
    put("toolCallId", JsonPrimitive(toolCallId))
    put("status", JsonPrimitive(status))
    put("rawOutput", jsonAnyToJson(rawOutput))
  }
}

// One session/request_permission option. optionId = stable semantic code echoed by client.
// kind = ACP permission kind (allow_once / allow_always / reject_once / reject_always)
internal fun acpPermissionOption(optionId: String, name: String, kind: String): JsonObject =
  buildJsonObject {
    put("optionId", JsonPrimitive(optionId))
    put("name", JsonPrimitive(name))
    put("kind", JsonPrimitive(kind))
  }

// Params for agent->client session/request_permission request. toolCall is partial ToolCallUpdate:
// toolCallId required, title echoes display text from tool call announcement.
internal fun acpRequestPermissionParams(
  title: String?,
  sessionId: String,
  toolCallId: String,
  options: List<JsonObject>
): JsonObject = buildJsonObject {
  put("sessionId", JsonPrimitive(sessionId))
  put("toolCall", buildJsonObject {
    put("toolCallId", JsonPrimitive(toolCallId))
    title?.let { put("title", JsonPrimitive(it)) }
  })
  put("options", buildJsonArray { options.forEach { add(it) } })
}

// Converts event-map payloads (arguments/result) into arbitrary JsonElement trees.
// Number/Boolean stay unquoted (wire-identical to JsonPrimitive); String/scalars use toString.
@OptIn(ExperimentalSerializationApi::class)
private fun jsonAnyToJson(value: Any?): JsonElement =
  when (value) {
    null -> JsonNull
    is Map<*, *> -> buildJsonObject {
      for ((rawKey, rawValue) in value) {
        if (rawKey is String) put(rawKey, jsonAnyToJson(rawValue))
      }
    }
    is List<*> -> buildJsonArray { value.forEach { add(jsonAnyToJson(it)) } }
    is Number, is Boolean -> JsonUnquotedLiteral(value.toString())
    else -> JsonPrimitive(value.toString())
  }