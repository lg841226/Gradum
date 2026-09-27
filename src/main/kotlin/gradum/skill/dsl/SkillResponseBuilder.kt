/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SkillResponseBuilder.kt  2026-09-27 Changed by gwy
 */

package gradum.skill.dsl

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
