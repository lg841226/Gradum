/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * JsonUtil.kt  2026-06-30 23:35:47 Changed by gwy
 */

package gradum.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

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

    /** Public entry point: encode a heterogeneous [Map] to a compact JSON string. */
    fun encodeMap(input: Map<String, Any?>, prettyPrint: Boolean = false): String {
        val formatter = Json { this.prettyPrint = prettyPrint }
        return formatter.encodeToString(JsonElement.serializer(), toJsonElement(input))
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
