/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ProviderSettingsPersistenceTest.kt  2026-09-24 23:23:59 Changed by gwy
 */

package gradum.idea.provider

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Persistence tests for [ProviderSettings.State] (defaults + per-kind
 * accessors) and [ProviderConfigFile] (the shared `~/.gradum/settings.json`
 * the embedded server reads). Provider overrides live at the top level of
 * the JSON object as VS Code style dotted keys (`ollama.baseUrl`,
 * `deepseek.apiKey`, `lmstudio.allowRemote`, ...). The config file is
 * redirected to a temp dir via `gradum.provider.configDir` so tests never
 * touch the user's real `~/.gradum/settings.json`.
 */
class ProviderSettingsPersistenceTest {

  private lateinit var tempDir: File

  @Before
  fun setUp() {
    tempDir = Files.createTempDirectory("gradum_settings_test_").toFile()
    System.setProperty("gradum.provider.configDir", tempDir.absolutePath)
  }

  @After
  fun tearDown() {
    System.clearProperty("gradum.provider.configDir")
    tempDir.deleteRecursively()
  }

  @Test
  fun `default state has sane defaults`() {
    val state = ProviderSettings.State()
    assertTrue("auto-detect on by default", state.autoDetectEnabled)
    assertEquals(
      5,
      state.pollIntervalSeconds
    )
    assertEquals(
      "http://localhost:11434",
      state.ollamaBaseUrl
    )
    assertEquals(
      "http://localhost:1234",
      state.lmStudioBaseUrl
    )
    assertEquals(
      "https://open.bigmodel.cn/api/paas/v4",
      state.zhipuBaseUrl
    )
    assertEquals(
      "https://api.deepseek.com/v1",
      state.deepseekBaseUrl
    )
    assertEquals(
      "https://api.minimaxi.com/v1",
      state.minimaxBaseUrl
    )

    assertFalse(state.zhipuEnabled)
    assertFalse(state.deepseekEnabled)
    assertFalse(state.minimaxEnabled)
  }

  @Test
  fun `configFor returns per-kind base url and api key`() {
    val state = ProviderSettings.State(
      zhipuApiKey = "kz",
      ollamaApiKey = "k1",
    )
    assertEquals(
      "http://localhost:11434" to "k1",
      state.configFor(kind = ProviderKind.OLLAMA)
    )
    assertEquals(
      "https://open.bigmodel.cn/api/paas/v4" to "kz",
      state.configFor(kind = ProviderKind.ZHIPU)
    )
  }

  @Test
  fun `setBaseUrl and setApiKey mutate the right kind only`() {
    val state = ProviderSettings.State()
    state.setBaseUrl(kind = ProviderKind.DEEPSEEK, value = "https://deepseek.example")
    state.setApiKey(kind = ProviderKind.DEEPSEEK, value = "dk")
    state.setBaseUrl(kind = ProviderKind.OLLAMA, value = "http://ollama.example")
    assertEquals(
      "https://deepseek.example",
      state.deepseekBaseUrl
    )
    assertEquals(
      "dk",
      state.deepseekApiKey
    )
    assertEquals(
      "http://ollama.example",
      state.ollamaBaseUrl
    )
    assertEquals(
      "other kinds must not be touched",
      "",
      state.zhipuApiKey
    )
    assertEquals(
      "https://open.bigmodel.cn/api/paas/v4",
      state.zhipuBaseUrl
    )
  }

  @Test
  fun `isEnabled is always true for local kinds and reflects flags for cloud`() {
    val state = ProviderSettings.State(zhipuEnabled = true)
    assertTrue(state.isEnabled(kind = ProviderKind.OLLAMA))
    assertTrue(state.isEnabled(kind = ProviderKind.LM_STUDIO))
    assertTrue(state.isEnabled(kind = ProviderKind.ZHIPU))
    assertFalse(state.isEnabled(kind = ProviderKind.DEEPSEEK))
    assertFalse(state.isEnabled(kind = ProviderKind.MINIMAX))
  }

  @Test
  fun `setEnabled is a no-op for local kinds`() {
    val state = ProviderSettings.State()
    state.setEnabled(kind = ProviderKind.OLLAMA, enabled = false)
    state.setEnabled(kind = ProviderKind.LM_STUDIO, enabled = false)
    assertTrue(state.isEnabled(kind = ProviderKind.OLLAMA))
    assertTrue(state.isEnabled(kind = ProviderKind.LM_STUDIO))
    state.setEnabled(kind = ProviderKind.MINIMAX, enabled = true)
    assertTrue(state.isEnabled(kind = ProviderKind.MINIMAX))
  }

  @Test
  fun `provider config file round-trips url and api key`() {
    ProviderConfigFile.updateProviderConfig(
      configKey = "ollama", baseUrl = "http://localhost:11434", apiKey = "secret-key"
    )
    val props = ProviderConfigFile.loadProperties()
    assertEquals(
      "http://localhost:11434",
      props.getProperty("ollama.baseUrl")
    )
    assertEquals(
      "secret-key",
      props.getProperty("ollama.apiKey")
    )
  }

  @Test
  fun `provider config file trims whitespace on write`() {
    ProviderConfigFile.updateProviderConfig(
      configKey = "deepseek", baseUrl = "  https://api.deepseek.com/v1  ", apiKey = "  dk  "
    )
    val props = ProviderConfigFile.loadProperties()
    assertEquals(
      "https://api.deepseek.com/v1",
      props.getProperty("deepseek.baseUrl")
    )
    assertEquals(
      "dk",
      props.getProperty("deepseek.apiKey")
    )
  }

  @Test
  fun `removeProviderConfig deletes the provider entries`() {
    ProviderConfigFile.updateProviderConfig(configKey = "zhipu", baseUrl = "https://open.bigmodel.cn", apiKey = "zk")
    ProviderConfigFile.updateAllowRemote(configKey = "zhipu", allowRemote = true)
    ProviderConfigFile.removeProviderConfig(configKey = "zhipu")
    val props = ProviderConfigFile.loadProperties()
    assertFalse(props.containsKey("zhipu.baseUrl"))
    assertFalse(props.containsKey("zhipu.apiKey"))
    assertFalse(props.containsKey("zhipu.allowRemote"))
  }

  @Test
  fun `updateAllowRemote persists the remote flag`() {
    ProviderConfigFile.updateAllowRemote(configKey = "lmstudio", allowRemote = true)
    assertEquals(
      "true",
      ProviderConfigFile.loadProperties().getProperty("lmstudio.allowRemote")
    )
    ProviderConfigFile.updateAllowRemote(configKey = "lmstudio", allowRemote = false)
    assertEquals(
      "false",
      ProviderConfigFile.loadProperties().getProperty("lmstudio.allowRemote")
    )
  }

  @Test
  fun `loadProperties returns empty when file missing`() {
    assertTrue(ProviderConfigFile.loadProperties().isEmpty)
  }

  @Test
  fun `updateProviderConfig preserves other providers entries`() {
    ProviderConfigFile.updateProviderConfig(
      configKey = "ollama", baseUrl = "http://localhost:11434", apiKey = "k1"
    )
    ProviderConfigFile.updateProviderConfig(configKey = "lmstudio", baseUrl = "http://localhost:1234", apiKey = "k2")
    val props = ProviderConfigFile.loadProperties()
    assertEquals(
      "http://localhost:11434",
      props.getProperty("ollama.baseUrl")
    )
    assertEquals(
      "http://localhost:1234",
      props.getProperty("lmstudio.baseUrl")
    )
  }

  @Test
  fun `edits preserve non-provider keys in settings json`() {
    File(tempDir, "settings.json").writeText(
      """
        {
          "${'$'}schema": "https://gradum.dev/schemas/settings.schema.json",
          "server": { "port": 9000 },
          "llm": { "model": "qwen2.5" },
          "commandFilter": { "blockedExecutables": ["dd"] }
        }
      """.trimIndent()
    )
    ProviderConfigFile.updateProviderConfig(configKey = "ollama", baseUrl = "http://localhost:11434", apiKey = "k1")

    val props = ProviderConfigFile.loadProperties()
    assertEquals(
      "http://localhost:11434",
      props.getProperty("ollama.baseUrl")
    )

    val text = File(tempDir, "settings.json").readText()
    assertTrue(text.contains("\"server\""))
    assertTrue(text.contains("\"port\": 9000"))
    assertTrue(text.contains("\"llm\""))
    assertTrue(text.contains("\"model\": \"qwen2.5\""))
    assertTrue(text.contains("\"commandFilter\""))
    assertTrue(text.contains("\"blockedExecutables\""))
    assertTrue(text.contains("\"\$schema\""))
  }
}
