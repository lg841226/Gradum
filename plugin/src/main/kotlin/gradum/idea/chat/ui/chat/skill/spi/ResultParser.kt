/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 */

package gradum.idea.chat.ui.chat.skill.spi

import kotlinx.serialization.json.*

/**
 * Parse the chat panel's tool result string (a JSON document produced
 * by the server-side `SkillResult` and serialized into the `tool_call`
 * event payload) into a flat `Map<String, Any?>` that any
 * [ToolCallRenderer] can inspect.
 *
 * The server serializes results as JSON objects, but a renderer should
 * not need to know that — it should just receive a `Map`. This
 * helper does the JSON → `Map<String, Any?>` translation once, with
 * a tolerant fall-back (returns an empty map on any parse error,
 * so the renderer still has a `Map` to call `get` on).
 */
fun parseJsonResult(serializedResult: String): Map<String, Any?> {
  if (serializedResult.isBlank()) return emptyMap()

  val parsedElement: JsonElement = try {
    Json.parseToJsonElement(serializedResult)
  } catch (_: Exception) {
    return emptyMap()
  }
  val rootObject: JsonObject = parsedElement as? JsonObject ?: return emptyMap()

  return jsonObjectToMap(rootObject)
}

private fun jsonObjectToMap(jsonObject: JsonObject): Map<String, Any?> =
  jsonObject.mapValues { (_, value) -> jsonElementToAny(value) }

private fun jsonElementToAny(jsonElement: JsonElement): Any? = when (jsonElement) {
  is JsonNull -> null
  is JsonPrimitive -> when {
    jsonElement.isString -> jsonElement.content
    jsonElement.intOrNull != null -> jsonElement.int
    jsonElement.longOrNull != null -> jsonElement.long
    jsonElement.doubleOrNull != null -> jsonElement.double
    jsonElement.booleanOrNull != null -> jsonElement.boolean
    else -> jsonElement.content
  }

  is JsonArray -> jsonElement.map { jsonElementToAny(it) }
  is JsonObject -> jsonObjectToMap(jsonElement)
}
