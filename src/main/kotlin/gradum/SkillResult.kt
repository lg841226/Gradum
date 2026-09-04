/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SkillResult.kt  2026-08-31 19:21:55 Changed by gwy
 */

package gradum

sealed class SkillResult {

  data class Success(val data: Map<String, Any>) : SkillResult()

  data class Failure(val code: String, val message: String, val context: Map<String, Any> = emptyMap()) :
    SkillResult()
}

fun makeSuccess(data: Map<String, Any>): SkillResult {
  return SkillResult.Success(data)
}

fun makeSuccess(builder: SkillResponseBuilder.() -> Unit): SkillResult {
  return SkillResult.Success(SkillResponseBuilder().apply(builder).build())
}

/**
 * Type-safe builder for the success payload map. Mirrors the schema DSL
 * styling: each field is declared via a per-type method instead of a raw
 * `mapOf("key" to value, ...)`. Insertion order is preserved so the LLM
 * sees a stable field sequence.
 */
class SkillResponseBuilder {
  private val entries: LinkedHashMap<String, Any> = LinkedHashMap()

  fun string(key: String, value: String) {
    entries[key] = value
  }

  fun integer(key: String, value: Int) {
    entries[key] = value
  }

  fun long(key: String, value: Long) {
    entries[key] = value
  }

  fun boolean(key: String, value: Boolean) {
    entries[key] = value
  }

  fun stringList(key: String, value: List<String>) {
    entries[key] = value
  }

  fun objectList(key: String, value: List<Map<String, Any>>) {
    entries[key] = value
  }

  fun set(key: String, value: Any) {
    entries[key] = value
  }

  fun `object`(key: String, block: SkillResponseBuilder.() -> Unit) {
    entries[key] = SkillResponseBuilder().apply(block).build()
  }

  internal fun build(): Map<String, Any> = entries
}

fun makeFailure(code: String, message: String, context: Map<String, Any> = emptyMap()): SkillResult {
  return SkillResult.Failure(code, message, context)
}

fun makeFailure(code: ErrorCode, message: String, context: Map<String, Any> = emptyMap()): SkillResult {
  return SkillResult.Failure(code.code, message, context)
}
