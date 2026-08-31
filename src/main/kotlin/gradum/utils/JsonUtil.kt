/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * JsonUtil.kt  2026-08-31 19:21:55 Changed by gwy
 */

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
 */
object JsonUtil {

  private val compactJson: Json = Json { }

  private val prettyJson: Json = Json { prettyPrint = true }

  /** Encodes a heterogeneous [Map] to a JSON string (optionally pretty-printed). */
  fun encodeMap(input: Map<String, Any?>, prettyPrint: Boolean = false): String {
    val jsonFormatter: Json = if (prettyPrint) prettyJson else compactJson
    return jsonFormatter.encodeToString(JsonElement.serializer(), toJsonElement(input))
  }

  /**
   * Inverse of [encodeMap]: parse a JSON string back into a plain
   * `Map<String, Any?>` with nested `Map`/`List` values.
   *
   * Throws [kotlinx.serialization.SerializationException] if the input
   * is not a JSON object.
   */
  fun decodeMap(input: String): Map<String, Any?> {
    val jsonElement: JsonElement = Json.parseToJsonElement(input)
    require(jsonElement is JsonObject) {
      "Expected JSON object at the top level, got: ${jsonElement::class.simpleName}"
    }
    @Suppress("UNCHECKED_CAST")
    return fromJsonElement(jsonElement) as Map<String, Any?>
  }

  /** Recursively convert an `Any?` value to its [JsonElement] representation. */
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

  /**
   * Inverse of [toJsonElement]: convert a [JsonElement] tree back into plain
   * Kotlin types so Skills can consume them without depending on the
   * kotlinx-serialization types directly.
   *
   * Unlike calling `value.jsonPrimitive.content` (which throws on
   * [JsonObject] / [JsonArray]), this handles every [JsonElement] subtype
   * and unwraps the underlying scalar.
   */
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
