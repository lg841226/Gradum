/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * SchemaDsl.kt  Type-safe, compile-time DSL for building OpenAI function schemas.
 *
 * Replaces hand-rolled `mapOf("type" to "string", ...)` property literals
 * across skills with a checked builder. Each property names its JSON key once
 * and derives its OpenAI type from the adapter method, so a typo in a field
 * name or a wrong property type fails at compile time instead of silently
 * reaching the LLM.
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
  val name: String,
  val required: Boolean,
  val level: ParameterLevel,
  internal val schema: Map<String, Any>,
)

/**
 * A producer that accumulates [SkillParameter]s. Every scope that wants to
 * declare schema properties (`[SchemaBuilder]`, `[CloudOnlyScope]`,
 * `[SimpleOnlyScope]`, and the items of an `objectArray`) implements this via
 * the extension methods below, so the four share one API surface.
 */
interface SchemaAdapter {
  /**
   * The level forced onto every parameter appended through this adapter, or
   * `null` on the main [SchemaBuilder] where the caller chooses per property.
   */
  val fixedLevel: ParameterLevel?

  fun add(parameter: SkillParameter)
}

/** A `string` parameter, optionally restricted to [enumValues] or given a [default]. */
fun SchemaAdapter.string(
  name: String,
  description: String,
  required: Boolean = false,
  level: ParameterLevel = fixedLevel ?: ParameterLevel.ALL,
  default: String? = null,
  enumValues: List<String> = emptyList(),
) {
  val jsonSchema: MutableMap<String, Any> = linkedMapOf(
    "type" to "string",
    "description" to description,
  )
  if (default != null) jsonSchema["default"] = default
  if (enumValues.isNotEmpty()) jsonSchema["enum"] = enumValues
  add(SkillParameter(name, required, level, jsonSchema))
}

/** An `integer` parameter with optional [default], [minimum], and [maximum] constraints. */
fun SchemaAdapter.integer(
  name: String,
  description: String,
  required: Boolean = false,
  level: ParameterLevel = fixedLevel ?: ParameterLevel.ALL,
  default: Int? = null,
  minimum: Int? = null,
  maximum: Int? = null,
) {
  val jsonSchema: MutableMap<String, Any> = linkedMapOf(
    "type" to "integer",
    "description" to description,
  )
  if (default != null) jsonSchema["default"] = default
  if (minimum != null) jsonSchema["minimum"] = minimum
  if (maximum != null) jsonSchema["maximum"] = maximum
  add(SkillParameter(name, required, level, jsonSchema))
}

/** A `boolean` parameter. */
fun SchemaAdapter.boolean(
  name: String,
  description: String,
  required: Boolean = false,
  level: ParameterLevel = fixedLevel ?: ParameterLevel.ALL,
) {
  add(
    SkillParameter(name, required, level, mapOf("type" to "boolean", "description" to description))
  )
}

/** An array of plain `string` items. */
fun SchemaAdapter.stringArray(
  name: String,
  description: String,
  required: Boolean = false,
  level: ParameterLevel = fixedLevel ?: ParameterLevel.ALL,
) {
  val jsonSchema: Map<String, Any> = mapOf(
    "type" to "array",
    "description" to description,
    "items" to mapOf("type" to "string"),
  )
  add(SkillParameter(name, required, level, jsonSchema))
}

/**
 * An array of objects; each object is shaped by the properties declared in
 * [items], with [itemRequired] naming which of them are mandatory.
 */
fun SchemaAdapter.objectArray(
  name: String,
  description: String,
  required: Boolean = false,
  level: ParameterLevel = fixedLevel ?: ParameterLevel.ALL,
  itemRequired: List<String>,
  items: ObjectItemBuilder.() -> Unit,
) {
  val itemBuilder: ObjectItemBuilder = ObjectItemBuilder()
  itemBuilder.items()
  val itemProperties: Map<String, Any> = itemBuilder.itemParameters.associate { item ->
    item.name to item.schema
  }
  val jsonSchema: Map<String, Any> = mapOf(
    "type" to "array",
    "description" to description,
    "items" to mapOf(
      "type" to "object",
      "properties" to itemProperties,
      "required" to itemRequired,
    ),
  )
  add(SkillParameter(name, required, level, jsonSchema))
}

/**
 * Receiver for [objectArray] that collects the properties of each array item.
 */
class ObjectItemBuilder : SchemaAdapter {
  override val fixedLevel: ParameterLevel? = null

  internal val itemParameters: MutableList<SkillParameter> = mutableListOf()

  override fun add(parameter: SkillParameter) {
    itemParameters.add(parameter)
  }
}

/**
 * Main builder for a skill's `properties` object. Every property method is a
 * distinct OpenAI JSON type derived from the method itself. The [cloudOnly]
 * and [simpleOnly] scopes group parameters that are only visible to one model
 * tier, so a single declaration set serves both schemas.
 */
class SchemaBuilder : SchemaAdapter {
  override val fixedLevel: ParameterLevel? = null

  internal val schemaParameters: MutableList<SkillParameter> = mutableListOf()

  override fun add(parameter: SkillParameter) {
    schemaParameters.add(parameter)
  }

  /**
   * Registers every parameter declared inside [block] as cloud-only, so a
   * cloud-only group reads like a sub-scope instead of repeating the level
   * on each property.
   */
  fun cloudOnly(block: CloudOnlyScope.() -> Unit) {
    val scope: CloudOnlyScope = CloudOnlyScope(this)
    scope.block()
  }

  /**
   * Registers every parameter declared inside [block] as simple-only, mirroring
   * [cloudOnly] for parameters that must only reach simple (local) models.
   */
  fun simpleOnly(block: SimpleOnlyScope.() -> Unit) {
    val scope: SimpleOnlyScope = SimpleOnlyScope(this)
    scope.block()
  }
}

/** Receiver for [SchemaBuilder.cloudOnly]: pins every property to [ParameterLevel.CLOUD_ONLY]. */
class CloudOnlyScope(private val schemaBuilder: SchemaAdapter) : SchemaAdapter {
  override val fixedLevel: ParameterLevel? = ParameterLevel.CLOUD_ONLY

  override fun add(parameter: SkillParameter) {
    schemaBuilder.add(parameter)
  }
}

/** Receiver for [SchemaBuilder.simpleOnly]: pins every property to [ParameterLevel.SIMPLE_ONLY]. */
class SimpleOnlyScope(private val schemaBuilder: SchemaAdapter) : SchemaAdapter {
  override val fixedLevel: ParameterLevel? = ParameterLevel.SIMPLE_ONLY

  override fun add(parameter: SkillParameter) {
    schemaBuilder.add(parameter)
  }
}