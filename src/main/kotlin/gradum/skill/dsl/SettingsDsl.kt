/*
 * Declares the plugin config a skill expects, once, and hands it to the
 * reader bound to the user's `plugins.<skillName>` section.
 *
 * The pieces, in the order they are used:
 *
 * - [settings]: entry point; returns a [SettingsSpec] from a block of named groups.
 * - [SettingsBuilder]: receiver of that block; collects named groups.
 * - [GroupBuilder]: receiver of one group; `"key" set default` declares a key.
 * - [SettingsKey]: one key plus its typed default; the default's Kotlin type is
 *   the single source of truth for how [BoundPluginConfig] reads the value back.
 * - [SettingsGroup]: one named group of keys, e.g. `settings("search") { ... }`.
 * - [SettingsSpec]: the full set of declared groups; [SettingsSpec.defaultOf]
 *   resolves the fallback default for `groupName.keyName`.
 *
 * ```kotlin
 * override val settingsSpec = settings {
 *   settings("search") {
 *     "maxResults" set 5
 *     "depth"      set "basic"
 *   }
 * }
 * ```
 *
 * Consumed through `PluginConfig.forSpec(settingsSpec)`; see PluginConfig.kt.
 */

package gradum.skill.dsl

data class SettingsKey<out T>(
  val defaultValue: T,
  val keyName: String,
)

data class SettingsGroup(
  val groupName: String,
  val groupKeys: Map<String, SettingsKey<*>>,
)

class SettingsSpec internal constructor(
  val settingsGroups: Map<String, SettingsGroup>,
) {
  internal fun defaultOf(groupName: String, keyName: String): Any? =
    settingsGroups[groupName]?.groupKeys?.get(keyName)?.defaultValue
}

class GroupBuilder internal constructor() {
  internal val declaredKeys: MutableMap<String, SettingsKey<*>> = mutableMapOf()

  infix fun String.set(defaultValue: Any) {
    declaredKeys[this] = SettingsKey(defaultValue, this)
  }
}

class SettingsBuilder internal constructor() {
  private val settingsGroups: MutableMap<String, SettingsGroup> = mutableMapOf()

  fun settings(groupName: String, groupBlock: GroupBuilder.() -> Unit) {
    val groupBuilder = GroupBuilder()
    groupBuilder.groupBlock()
    settingsGroups[groupName] = SettingsGroup(groupName, groupBuilder.declaredKeys.toMap())
  }

  internal fun build(): SettingsSpec = SettingsSpec(settingsGroups.toMap())
}

fun settings(settingsBlock: SettingsBuilder.() -> Unit): SettingsSpec {
  val settingsBuilder = SettingsBuilder()
  settingsBuilder.settingsBlock()
  return settingsBuilder.build()
}
