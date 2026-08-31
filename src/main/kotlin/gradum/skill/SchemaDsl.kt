/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SchemaDsl.kt  2026-08-31 19:21:55 Changed by gwy
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

/** Bundled constraints for [SchemaAdapter.integer]. */
class IntConstraints(
  val default: Int? = null,
  val minimum: Int? = null,
  val maximum: Int? = null
)

/**
 * A single compiled schema parameter: its JSON key, whether it is required,
 * its visibility level, and the OpenAI JSON-schema map describing it.
 */
class SkillParameter(
  val name: String,
  val required: Boolean,
  val level: ParameterLevel,
  internal val schema: Map<String, Any>
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
  override fun add(parameter: SkillParameter) {
    parameters.add(parameter)
  }
}

fun SchemaAdapter.string(
  name: String,
  description: String,
  required: Boolean = false,
  enumValues: List<String> = emptyList(),
  level: ParameterLevel = fixedLevel ?: ParameterLevel.ALL
) {
  val schema = linkedMapOf<String, Any>(
    "type" to "string",
    "description" to description
  )
  if (enumValues.isNotEmpty()) schema["enum"] = enumValues
  add(SkillParameter(name, required, level, schema))
}

fun SchemaAdapter.integer(
  name: String,
  description: String,
  required: Boolean = false,
  constraints: IntConstraints = IntConstraints(),
  level: ParameterLevel = fixedLevel ?: ParameterLevel.ALL
) {
  val schema = linkedMapOf<String, Any>(
    "type" to "integer",
    "description" to description
  )
  if (constraints.default != null) schema["default"] = constraints.default
  if (constraints.minimum != null) schema["minimum"] = constraints.minimum
  if (constraints.maximum != null) schema["maximum"] = constraints.maximum
  add(SkillParameter(name, required, level, schema))
}

fun SchemaAdapter.boolean(
  name: String,
  description: String,
  required: Boolean = false,
  level: ParameterLevel = fixedLevel ?: ParameterLevel.ALL
) {
  add(
    SkillParameter(
      name, required, level, schema =
        mapOf(
          "type" to "boolean",
          "description" to description
        )
    )
  )
}

fun SchemaAdapter.stringArray(
  name: String,
  description: String,
  required: Boolean = false,
  level: ParameterLevel = fixedLevel ?: ParameterLevel.ALL
) {
  val schema = mapOf(
    "type" to "array",
    "description" to description,
    "items" to mapOf(
      "type" to "string"
    )
  )
  add(SkillParameter(name, required, level, schema))
}

/**
 * An array of objects; each object is shaped by the properties declared in
 * [items], with [itemRequired] naming which of them are mandatory.
 */
fun SchemaAdapter.objectArray(
  name: String,
  description: String,
  required: Boolean = false,
  itemRequired: List<String>,
  items: ObjectItemBuilder.() -> Unit,
  level: ParameterLevel = fixedLevel ?: ParameterLevel.ALL
) {
  val builder = ObjectItemBuilder()
  builder.items()
  val itemProperties = builder.parameters.associate {
    it.name to it.schema
  }
  val schema = mapOf(
    "type" to "array",
    "description" to description,
    "items" to mapOf(
      "type" to "object",
      "required" to itemRequired,
      "properties" to itemProperties
    )
  )
  add(SkillParameter(name, required, level, schema))
}

class ObjectItemBuilder : MutableSchemaAdapter()

/**
 * Main builder for a skill's `properties` object. The [cloudOnly] and
 * [simpleOnly] scopes group parameters that are only visible to one model
 * tier, so a single declaration set serves both schemas.
 */
class SchemaBuilder : MutableSchemaAdapter() {
  val schemaParameters: List<SkillParameter> get() = parameters.toList()
  fun cloudOnly(block: SchemaAdapter.() -> Unit) {
    LevelScope(delegate = this, fixedLevel = ParameterLevel.CLOUD_ONLY).block()
  }

  fun simpleOnly(block: SchemaAdapter.() -> Unit) {
    LevelScope(delegate = this, fixedLevel = ParameterLevel.SIMPLE_ONLY).block()
  }
}

private class LevelScope(
  private val delegate: SchemaAdapter,
  override val fixedLevel: ParameterLevel
) : SchemaAdapter {
  override fun add(parameter: SkillParameter) = delegate.add(parameter)
}
