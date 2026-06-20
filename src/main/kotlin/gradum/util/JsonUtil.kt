package gradum.util

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
        val formatter: Json = Json { this.prettyPrint = prettyPrint }
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
            for (item: Any? in value) {
                add(toJsonElement(item))
            }
        }
        is Array<*> -> buildJsonArray {
            for (item: Any? in value) {
                add(toJsonElement(item))
            }
        }
        is JsonObject -> value
        is JsonArray -> value
        else -> JsonPrimitive(value.toString())
    }
}
