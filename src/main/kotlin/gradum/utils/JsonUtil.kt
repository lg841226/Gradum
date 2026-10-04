package gradum.utils

import kotlinx.serialization.json.*

/**
 * Helpers for serializing heterogeneous [Map] / [List] payloads where the
 * element type is [Any] (i.e. the value type is not statically known).
 *
 * kotlinx-serialization cannot resolve a serializer for `Any`, so the
 * standard `Json.encodeToString(serializer<Map<String, Any>>(), ...)`
 * pattern fails at runtime. These helpers recursively convert an
 * `Any`-typed tree to a `JsonElement` tree first, then call
 * `JsonElement.serializer()` which is well-defined.
 *
 * Supported value types:
 * - `null` -> `JsonNull`
 * - `String`, `Boolean`, `Number` -> `JsonPrimitive`
 * - `Map<*, *>` -> `JsonObject` (recurses)
 * - `Iterable<*>` / `Array<*>` -> `JsonArray` (recurses)
 * - Anything else -> `JsonPrimitive(toString())` as a last-resort fallback
 *
 * encodeMap(input, prettyPrint) encodes a heterogeneous Map to a JSON
 * string (optionally pretty-printed), and decodeMap(input) is its inverse:
 * it parses a JSON string back into a plain Map<String, Any?> with nested
 * Map and List values, throwing SerializationException when the input is not
 * a JSON object. toJsonElement(value) recursively converts an Any? value to
 * its JsonElement representation, and fromJsonElement(value) is its
 * inverse: it converts a JsonElement tree back into plain Kotlin types so
 * Skills can consume them without depending on the kotlinx-serialization
 * types directly, handling every JsonElement subtype and unwrapping the
 * underlying scalar (unlike value.jsonPrimitive.content, which throws on
 * JsonObject or JsonArray).
 */
object JsonUtil {

  private val compactJson: Json = Json.Default

  private val prettyJson: Json = Json { prettyPrint = true }

  fun encodeMap(input: Map<String, Any?>, prettyPrint: Boolean = false): String {
    val jsonFormatter: Json = if (prettyPrint) prettyJson else compactJson
    return jsonFormatter.encodeToString(JsonElement.serializer(), toJsonElement(input))
  }

  fun decodeMap(input: String): Map<String, Any?> {
    val jsonElement: JsonElement = Json.parseToJsonElement(input)
    require(jsonElement is JsonObject) {
      "Expected JSON object at the top level, got: ${jsonElement::class.simpleName}"
    }
    @Suppress("UNCHECKED_CAST")
    return fromJsonElement(jsonElement) as Map<String, Any?>
  }

  fun toJsonElement(value: Any?): JsonElement = when (value) {
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

  fun fromJsonElement(value: JsonElement): Any? = when (value) {
    is JsonNull -> null
    is JsonPrimitive -> when {
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
