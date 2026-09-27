package gradum.skill.dsl

import gradum.SkillResult
import gradum.ToolMode
import gradum.makeSuccess
import gradum.skill.Skill
import gradum.skill.SkillContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private enum class Mode { BASIC, ADVANCED }

private class NamedSkill(private val skillIdentifier: String) : Skill() {
  override val alias: String = skillIdentifier
  override val skillName: String = skillIdentifier
  override val description: String = "test skill"
  override fun execute(arguments: Map<String, Any>, context: SkillContext): SkillResult =
    makeSuccess(emptyMap())

  override val schemaProperties: SchemaBuilder.() -> Unit = {}
}

class SettingsDslTest {

  private val settingsSpec: SettingsSpec = settings {
    settings("search") {
      "maxResults" set 5
      "depth" set "basic"
    }
    settings("output") {
      "verbose" set false
      "tags" set listOf("a", "b")
      "mode" set Mode.BASIC
    }
  }

  @Test
  fun `settings DSL keeps declared defaults`() {
    assertEquals(5, settingsSpec.defaultOf("search", "maxResults"))
    assertEquals("basic", settingsSpec.defaultOf("search", "depth"))
    assertEquals(false, settingsSpec.defaultOf("output", "verbose"))
    assertEquals(listOf("a", "b"), settingsSpec.defaultOf("output", "tags"))
    assertEquals(Mode.BASIC, settingsSpec.defaultOf("output", "mode"))
    assertEquals(null, settingsSpec.defaultOf("search", "undeclared"))
  }

  @Test
  fun `user value overrides declared default`() {
    val boundConfig = PluginConfig.of(
      mapOf("search" to mapOf<String, Any?>("maxResults" to 8))
    ).forSpec(settingsSpec)

    assertEquals(8, boundConfig.int("search", "maxResults"))
    assertEquals("basic", boundConfig.string("search", "depth")) // missing in source -> default
  }

  @Test
  fun `missing key falls back to declared default`() {
    val boundConfig = PluginConfig.of(emptyMap()).forSpec(settingsSpec)

    assertEquals(5, boundConfig.int("search", "maxResults"))
    assertEquals(false, boundConfig.boolean("output", "verbose"))
    assertEquals(listOf("a", "b"), boundConfig.stringArray("output", "tags"))
  }

  @Test
  fun `type mismatch throws PluginConfigTypeError`() {
    val boundConfig = PluginConfig.of(
      mapOf("search" to mapOf<String, Any?>("maxResults" to "eight"))
    ).forSpec(settingsSpec)

    val typeError = assertFailsWith<PluginConfigTypeError> {
      boundConfig.int("search", "maxResults")
    }
    assertEquals("search", typeError.groupName)
    assertEquals("maxResults", typeError.keyName)
  }

  @Test
  fun `stringArray rejects a non-string array`() {
    val boundConfig = PluginConfig.of(
      mapOf("output" to mapOf<String, Any?>("tags" to listOf(1, 2)))
    ).forSpec(settingsSpec)

    assertFailsWith<PluginConfigTypeError> {
      boundConfig.stringArray("output", "tags")
    }
  }

  @Test
  fun `enum reads user name string or falls back to default`() {
    val advancedConfig = PluginConfig.of(
      mapOf("output" to mapOf<String, Any?>("mode" to "ADVANCED"))
    ).forSpec(settingsSpec)
    assertEquals(Mode.ADVANCED, advancedConfig.enum("output", "mode", Mode.values()))

    val defaultConfig = PluginConfig.of(emptyMap()).forSpec(settingsSpec)
    assertEquals(Mode.BASIC, defaultConfig.enum("output", "mode", Mode.values()))
  }

  @Test
  fun `enum rejects an unknown name string`() {
    val boundConfig = PluginConfig.of(
      mapOf("output" to mapOf<String, Any?>("mode" to "TURBO"))
    ).forSpec(settingsSpec)

    assertFailsWith<PluginConfigTypeError> {
      boundConfig.enum("output", "mode", Mode.values())
    }
  }

  @Test
  fun `Skill pluginConfig resolves its own slice by skill name`() {
    val skillContext = SkillContext(
      toolMode = ToolMode.AGENT,
      projectRoot = "",
      plugins = mapOf(
        "search_web" to mapOf<String, Any?>("search" to mapOf<String, Any?>("depth" to "advanced"))
      )
    )

    val boundConfig = NamedSkill("search_web").pluginConfig(skillContext).forSpec(settingsSpec)
    assertEquals("advanced", boundConfig.string("search", "depth"))

    // A different skill name sees none of the other skill's config.
    val otherSkillConfig = NamedSkill("other_skill").pluginConfig(skillContext).forSpec(settingsSpec)
    assertEquals("basic", otherSkillConfig.string("search", "depth"))
  }
}
