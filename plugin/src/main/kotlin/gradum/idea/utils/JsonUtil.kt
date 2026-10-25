package gradum.idea.utils

import kotlinx.serialization.json.*

/**
 * Helpers for serializing heterogeneous [Map] / [List] payloads where the
 * element type is [Any] (i.e. the value type is not statically known).
 */
object JsonUtil {

  fun toJsonElement(value: Any?): JsonElement =
    when (value) {
      null -> JsonNull
      is JsonElement -> value
      is String -> JsonPrimitive(value)
      is Boolean -> JsonPrimitive(value)
      is Number -> JsonPrimitive(value)
      is Map<*, *> -> {
        buildJsonObject {
          for ((rawKey, rawValue) in value) {
            val key: String = rawKey?.toString() ?: continue
            put(key, toJsonElement(rawValue))
          }
        }
      }

      is Iterable<*> -> buildJsonArray {
        for (element: Any? in value) {
          add(toJsonElement(element))
        }
      }

      is Array<*> -> buildJsonArray {
        for (element: Any? in value) {
          add(toJsonElement(element))
        }
      }

      else -> JsonPrimitive(value.toString())
    }

  fun fromJsonElement(value: JsonElement): Any? =
    when (value) {
      is JsonNull -> null
      is JsonPrimitive ->
        when {
          value.isString -> value.content
          value.content.toBooleanStrictOrNull() != null -> value.content.toBooleanStrict()
          value.content.toLongOrNull() != null -> value.content.toLong()
          value.content.toDoubleOrNull() != null -> value.content.toDouble()
          else -> value.content
        }

      is JsonObject -> value.entries.associate { (key: String, element: JsonElement) ->
        key to fromJsonElement(element)
      }

      is JsonArray -> value.map { fromJsonElement(it) }
    }
}
