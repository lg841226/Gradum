/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ToolArguments.kt  2026-08-24 12:00:00 Changed by gwy
 */

package gradum.idea.chat.ui.chat.skill.spi

/**
 * Type-safe accessor extensions for tool-call argument maps.
 *
 * The server sends `arguments` as `Map<String, Any?>` (kotlinx-serialization
 * round-trip). Instead of scattering `(arguments["key"] as? String).orEmpty()`
 * across every renderer, these extensions provide a single, tested place
 * for safe extraction.
 *
 * Usage in renderers:
 * ```
 * val path = arguments.string("path")
 * val depth = arguments.int("depth", defaultValue = 8)
 * val tasks = arguments.stringList("tasks")
 * ```
 */

/** Extract a [String] value, returning empty string if missing or wrong type. */
fun Map<String, Any?>.string(key: String): String =
  (get(key) as? String).orEmpty()

/** Extract an [Int] value, returning [defaultValue] if missing or wrong type. */
fun Map<String, Any?>.int(key: String, defaultValue: Int = 0): Int =
  (get(key) as? Number)?.toInt() ?: defaultValue

/** Extract a [Long] value, returning [defaultValue] if missing or wrong type. */
fun Map<String, Any?>.long(key: String, defaultValue: Long = 0L): Long =
  (get(key) as? Number)?.toLong() ?: defaultValue

/** Extract a [Boolean] value, returning [defaultValue] if missing or wrong type. */
fun Map<String, Any?>.boolean(key: String, defaultValue: Boolean = false): Boolean =
  (get(key) as? Boolean) ?: defaultValue

/** Extract a [Double] value, returning [defaultValue] if missing or wrong type. */
fun Map<String, Any?>.double(key: String, defaultValue: Double = 0.0): Double =
  (get(key) as? Number)?.toDouble() ?: defaultValue

/** Extract a list of strings, returning empty list if missing or wrong type. */
fun Map<String, Any?>.stringList(key: String): List<String> {
  val raw = get(key) ?: return emptyList()
  if (raw is List<*>) return raw.filterIsInstance<String>()
  if (raw is String) return listOf(raw)
  return emptyList()
}

/** Extract a nested map, returning empty map if missing or wrong type. */
fun Map<String, Any?>.nested(key: String): Map<String, Any?> {
  val raw = get(key)
  @Suppress("UNCHECKED_CAST")
  return raw as? Map<String, Any?> ?: emptyMap()
}
