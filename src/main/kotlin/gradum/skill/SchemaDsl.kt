/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SchemaDsl.kt  2026-08-25 15:51:48 Changed by gwy
 */

package gradum.skill

/**
 * Controls whether a skill parameter is exposed to the simple (local) model
 * schema, the cloud schema, or both.
 */
enum class ParameterLevel {
  /** Present in both the simple and the cloud schema. */
  ALL,

  /** Present only in the simple schema; omitted for cloud models. */
  SIMPLE_ONLY,

  /** Present only in the cloud schema; omitted for simple models. */
  CLOUD_ONLY,
}

/**
 * A single compiled schema parameter: its JSON key, whether it is required,
 * its visibility level, and the OpenAI JSON-schema map describing it. The
 * [schema] map and the [level] are consumed by the framework; a skill only
 * names a parameter once and never inspects these internals.
 */
class SkillParameter(
  val name: String, val required: Boolean,
  val level: ParameterLevel, internal val schema: Map<String, Any>
)

/**
 * A producer that accumulates [SkillParameter]s. Every scope that wants to
 * declare schema properties implements this via the extension methods below.
 */
interface SchemaAdapter {
  val fixedLevel: ParameterLevel?
  fun add(parameter: SkillParameter)
}

open class MutableSchemaAdapter : SchemaAdapter {
  override val fixedLevel: ParameterLevel? get() = null
  val parameters: MutableList<SkillParameter> = mutableListOf()
  override fun add(parameter: SkillParameter) { parameters.add(parameter) }
}

/** A `string` parameter, optionally restricted to [enumValues] or given a [default]. */
fun SchemaAdapter.string(
  name: String, description: String, default: String? = null,
  required: Boolean = false, enumValues: List<String> = emptyList(),
  level: ParameterLevel = fixedLevel ?: ParameterLevel.ALL
) {
  val schema = linkedMapOf<String, Any>("type" to "string", "description" to description)
  if (default != null) schema["default"] = default
  if (enumValues.isNotEmpty()) schema["enum"] = enumValues
  add(SkillParameter(name, required, level, schema))
}

/** An `integer` parameter with optional [default], [minimum], and [maximum] constraints. */
fun SchemaAdapter.integer(
  name: String, description: String, default: Int? = null,
  minimum: Int? = null, maximum: Int? = null,
  required: Boolean = false, level: ParameterLevel = fixedLevel ?: ParameterLevel.ALL
) {
  val schema = linkedMapOf<String, Any>("type" to "integer", "description" to description)
  if (default != null) schema["default"] = default
  if (minimum != null) schema["minimum"] = minimum
  if (maximum != null) schema["maximum"] = maximum
  add(SkillParameter(name, required, level, schema))
}

/** A `boolean` parameter. */
fun SchemaAdapter.boolean(
  name: String, description: String,
  required: Boolean = false, level: ParameterLevel = fixedLevel ?: ParameterLevel.ALL
) {
  add(SkillParameter(name, required, level, mapOf("type" to "boolean", "description" to description)))
}

/** An array of plain `string` items. */
fun SchemaAdapter.stringArray(
  name: String, description: String,
  required: Boolean = false, level: ParameterLevel = fixedLevel ?: ParameterLevel.ALL
) {
  val schema = mapOf("type" to "array", "description" to description, "items" to mapOf("type" to "string"))
  add(SkillParameter(name, required, level, schema))
}

/**
 * An array of objects; each object is shaped by the properties declared in
 * [items], with [itemRequired] naming which of them are mandatory.
 */
fun SchemaAdapter.objectArray(
  name: String, description: String, required: Boolean = false,
  itemRequired: List<String>, items: ObjectItemBuilder.() -> Unit,
  level: ParameterLevel = fixedLevel ?: ParameterLevel.ALL
) {
  val builder = ObjectItemBuilder()
  builder.items()
  val itemProperties = builder.parameters.associate { it.name to it.schema }
  val schema = mapOf(
    "type" to "array", "description" to description,
    "items" to mapOf("type" to "object", "required" to itemRequired, "properties" to itemProperties)
  )
  add(SkillParameter(name, required, level, schema))
}

/** Receiver for [objectArray] that collects the properties of each array item. */
class ObjectItemBuilder : MutableSchemaAdapter()

/**
 * Main builder for a skill's `properties` object. The [cloudOnly] and
 * [simpleOnly] scopes group parameters that are only visible to one model
 * tier, so a single declaration set serves both schemas.
 */
class SchemaBuilder : MutableSchemaAdapter() {
  val schemaParameters: List<SkillParameter> get() = parameters.toList()
  fun cloudOnly(block: SchemaAdapter.() -> Unit) { LevelScope(ParameterLevel.CLOUD_ONLY, this).block() }
  fun simpleOnly(block: SchemaAdapter.() -> Unit) { LevelScope(ParameterLevel.SIMPLE_ONLY, this).block() }
}

private class LevelScope(
  override val fixedLevel: ParameterLevel, private val delegate: SchemaAdapter
) : SchemaAdapter {
  override fun add(parameter: SkillParameter) = delegate.add(parameter)
}
