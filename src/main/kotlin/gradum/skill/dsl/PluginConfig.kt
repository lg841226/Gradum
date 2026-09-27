/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * PluginConfig.kt  2026-09-27 13:05:45 Changed by gwy
 */

/**
 * Reading side of the plugin config declared in SettingsDsl.kt: a skill
 * reaches its own `plugins.<skillName>` slice and binds it to its declared
 * [SettingsSpec], so every getter knows each key's fallback default and the
 * type the skill asked for.
 *
 * The pieces:
 *
 * - [Skill.pluginConfig]: binds the skill's own slice from [SkillContext.plugins].
 * - [PluginConfig]: read-only view of that slice; [PluginConfig.forSpec] binds it.
 * - [BoundPluginConfig]: the bound reader; its `int`, `string`, `boolean`,
 *   `stringArray` or `enum` getters resolve `(groupName, keyName)`, and the
 *   user's settings.json value wins over the spec default. A present value
 *   whose type does not match throws [PluginConfigTypeError], which the
 *   executor surfaces as a skill failure instead of silently ignoring a
 *   misconfigured section.
 * - `toIntOrNull` and `toStringListOrNull` decide whether a raw JSON value
 *   can be read back as the requested type.
 *
 * ```kotlin
 * val boundConfig = pluginConfig(skillContext).forSpec(settingsSpec)
 * val maxResults = boundConfig.int("search", "maxResults")
 * ```
 */

package gradum.skill.dsl

import gradum.skill.Skill
import gradum.skill.SkillContext

class PluginConfig private constructor(
  private val configSource: Map<String, Any?>,
) {
  companion object {
    internal fun of(configSource: Map<String, Any?>): PluginConfig = PluginConfig(configSource)
  }

  fun forSpec(settingsSpec: SettingsSpec): BoundPluginConfig = BoundPluginConfig(configSource, settingsSpec)
}

class BoundPluginConfig internal constructor(
  private val configSource: Map<String, Any?>,
  private val settingsSpec: SettingsSpec,
) {
  fun int(groupName: String, keyName: String): Int =
    coerce(groupName, keyName, rawValue(groupName, keyName), "an integer") { it.toIntOrNull() }

  fun string(groupName: String, keyName: String): String =
    coerce(groupName, keyName, rawValue(groupName, keyName), "a string") { it as? String }

  fun boolean(groupName: String, keyName: String): Boolean =
    coerce(groupName, keyName, rawValue(groupName, keyName), "a boolean") { it as? Boolean }

  fun stringArray(groupName: String, keyName: String): List<String> =
    coerce(groupName, keyName, rawValue(groupName, keyName), "an array of strings") { it.toStringListOrNull() }

  fun <T : Enum<T>> enum(groupName: String, keyName: String, enumValues: Array<T>): T {
    val groupEntries: Map<*, *>? = configSource[groupName] as? Map<*, *>
    val userValue: Any? = groupEntries?.get(keyName)
    if (userValue != null) {
      val enumName: String = userValue as? String
        ?: throw PluginConfigTypeError(keyName, groupName, userValue, "an enum name string")
      return enumValues.firstOrNull { it.name == enumName }
        ?: throw PluginConfigTypeError(
          keyName, groupName, userValue, "one of ${enumValues.joinToString(",") { it.name }}"
        )
    }
    @Suppress("UNCHECKED_CAST")
    return (settingsSpec.defaultOf(groupName, keyName) as? T)
      ?: throw PluginConfigTypeError(
        keyName, groupName, settingsSpec.defaultOf(groupName, keyName),
        "one of ${enumValues.joinToString(",") { it.name }}"
      )
  }

  private fun rawValue(groupName: String, keyName: String): Any? {
    val groupEntries: Map<*, *>? = configSource[groupName] as? Map<*, *>
    val isPresent: Boolean = groupEntries?.containsKey(keyName) == true
    return if (isPresent) groupEntries[keyName]
    else settingsSpec.defaultOf(groupName, keyName)
  }

  private fun <T> coerce(
    groupName: String,
    keyName: String,
    configValue: Any?,
    expectedType: String,
    valueConverter: (Any?) -> T?
  ): T = valueConverter(configValue)
    ?: throw PluginConfigTypeError(keyName, groupName, configValue, expectedType)
}

private fun Any?.toIntOrNull(): Int? =
  (this as? Number)?.toInt()

private fun Any?.toStringListOrNull(): List<String>? =
  (this as? List<*>)?.takeIf { elementList -> elementList.all { it is String } }?.filterIsInstance<String>()

fun Skill.pluginConfig(skillContext: SkillContext): PluginConfig =
  PluginConfig.of(skillContext.plugins[skillName] ?: emptyMap())
