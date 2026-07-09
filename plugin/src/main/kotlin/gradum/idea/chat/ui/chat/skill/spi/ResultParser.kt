package gradum.idea.chat.ui.chat.skill.spi

import kotlinx.serialization.json.*

/**
 * Parse the chat panel's tool result string (a JSON document produced
 * by the server-side `SkillResult` and serialised into the `tool_call`
 * event payload) into a flat `Map<String, Any?>` that any
 * [ToolCallRenderer] can inspect.
 *
 * The server serialises results as JSON objects, but a renderer should
 * not need to know that — it should just receive a `Map`. This
 * helper does the JSON → `Map<String, Any?>` translation once, with
 * a tolerant fall-back (returns an empty map on any parse error,
 * so the renderer still has a `Map` to call `get` on).
 */
fun parseJsonResult(serializedResult: String): Map<String, Any?> {
    if (serializedResult.isBlank()) return emptyMap()
    val element: JsonElement = try {
        Json.parseToJsonElement(serializedResult)
    } catch (_: Exception) {
        return emptyMap()
    }
    val rootObject: JsonObject = element as? JsonObject ?: return emptyMap()
    return jsonObjectToMap(rootObject)
}

private fun jsonObjectToMap(jsonObject: JsonObject): Map<String, Any?> =
    jsonObject.mapValues { (_, value) -> jsonElementToAny(value) }

private fun jsonElementToAny(element: JsonElement): Any? = when (element) {
    is JsonNull -> null
    is JsonPrimitive -> when {
        element.isString -> element.content
        element.intOrNull != null -> element.int
        element.longOrNull != null -> element.long
        element.doubleOrNull != null -> element.double
        element.booleanOrNull != null -> element.boolean
        else -> element.content
    }
    is JsonArray -> element.map { jsonElementToAny(it) }
    is JsonObject -> jsonObjectToMap(element)
}
