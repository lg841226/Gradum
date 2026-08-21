/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ProviderSettingsPersistenceTest.kt  2026-08-17 19:34:57 Changed by gwy
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
 * accessors) and [ProviderConfigFile] (the shared `provider.env` the
 * embedded server reads). The config file is redirected to a temp dir
 * via `gradum.provider.configDir` so tests never touch the user's real
 * `~/.gradum/provider.env`.
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
    assertEquals(5, state.pollIntervalSeconds)
    assertEquals("http://localhost:11434", state.ollamaBaseUrl)
    assertEquals("http://localhost:1234", state.lmStudioBaseUrl)
    assertFalse(state.zhipuEnabled)
    assertFalse(state.deepseekEnabled)
    assertFalse(state.minimaxEnabled)
    assertEquals("https://open.bigmodel.cn/api/paas/v4", state.zhipuBaseUrl)
    assertEquals("https://api.deepseek.com/v1", state.deepseekBaseUrl)
    assertEquals("https://api.minimaxi.com/v1", state.minimaxBaseUrl)
  }

  @Test
  fun `configFor returns per-kind base url and api key`() {
    val state = ProviderSettings.State(
      ollamaBaseUrl = "http://localhost:11434",
      ollamaApiKey = "k1",
      zhipuBaseUrl = "https://open.bigmodel.cn/api/paas/v4",
      zhipuApiKey = "kz",
    )
    assertEquals("http://localhost:11434" to "k1", state.configFor(ProviderKind.OLLAMA))
    assertEquals("https://open.bigmodel.cn/api/paas/v4" to "kz", state.configFor(ProviderKind.ZHIPU))
  }

  @Test
  fun `setBaseUrl and setApiKey mutate the right kind only`() {
    val state = ProviderSettings.State()
    state.setBaseUrl(ProviderKind.DEEPSEEK, "https://deepseek.example")
    state.setApiKey(ProviderKind.DEEPSEEK, "dk")
    state.setBaseUrl(ProviderKind.OLLAMA, "http://ollama.example")
    assertEquals("https://deepseek.example", state.deepseekBaseUrl)
    assertEquals("dk", state.deepseekApiKey)
    assertEquals("http://ollama.example", state.ollamaBaseUrl)
    assertEquals("other kinds must not be touched", "", state.zhipuApiKey)
    assertEquals("https://open.bigmodel.cn/api/paas/v4", state.zhipuBaseUrl)
  }

  @Test
  fun `isEnabled is always true for local kinds and reflects flags for cloud`() {
    val state = ProviderSettings.State(zhipuEnabled = true)
    assertTrue(state.isEnabled(ProviderKind.OLLAMA))
    assertTrue(state.isEnabled(ProviderKind.LM_STUDIO))
    assertTrue(state.isEnabled(ProviderKind.ZHIPU))
    assertFalse(state.isEnabled(ProviderKind.DEEPSEEK))
    assertFalse(state.isEnabled(ProviderKind.MINIMAX))
  }

  @Test
  fun `setEnabled is a no-op for local kinds`() {
    val state = ProviderSettings.State()
    state.setEnabled(ProviderKind.OLLAMA, false)
    state.setEnabled(ProviderKind.LM_STUDIO, false)
    assertTrue(state.isEnabled(ProviderKind.OLLAMA))
    assertTrue(state.isEnabled(ProviderKind.LM_STUDIO))
    state.setEnabled(ProviderKind.MINIMAX, true)
    assertTrue(state.isEnabled(ProviderKind.MINIMAX))
  }

  @Test
  fun `provider config file round-trips url and api key`() {
    ProviderConfigFile.updateProviderConfig("ollama", "http://localhost:11434", "secret-key")
    val props = ProviderConfigFile.loadProperties()
    assertEquals("http://localhost:11434", props.getProperty("GRADUM_OLLAMA_BASE_URL"))
    assertEquals("secret-key", props.getProperty("GRADUM_OLLAMA_API_KEY"))
  }

  @Test
  fun `provider config file trims whitespace on write`() {
    ProviderConfigFile.updateProviderConfig("deepseek", "  https://api.deepseek.com/v1  ", "  dk  ")
    val props = ProviderConfigFile.loadProperties()
    assertEquals("https://api.deepseek.com/v1", props.getProperty("GRADUM_DEEPSEEK_BASE_URL"))
    assertEquals("dk", props.getProperty("GRADUM_DEEPSEEK_API_KEY"))
  }

  @Test
  fun `removeProviderConfig deletes the provider entries`() {
    ProviderConfigFile.updateProviderConfig("zhipu", "https://open.bigmodel.cn", "zk")
    ProviderConfigFile.updateAllowRemote("zhipu", true)
    ProviderConfigFile.removeProviderConfig("zhipu")
    val props = ProviderConfigFile.loadProperties()
    assertFalse(props.containsKey("GRADUM_ZHIPU_BASE_URL"))
    assertFalse(props.containsKey("GRADUM_ZHIPU_API_KEY"))
    assertFalse(props.containsKey("GRADUM_ZHIPU_ALLOW_REMOTE"))
  }

  @Test
  fun `updateAllowRemote persists the remote flag`() {
    ProviderConfigFile.updateAllowRemote("lmstudio", true)
    assertEquals("true", ProviderConfigFile.loadProperties().getProperty("GRADUM_LMSTUDIO_ALLOW_REMOTE"))
    ProviderConfigFile.updateAllowRemote("lmstudio", false)
    assertEquals("false", ProviderConfigFile.loadProperties().getProperty("GRADUM_LMSTUDIO_ALLOW_REMOTE"))
  }

  @Test
  fun `loadProperties returns empty when file missing`() {
    assertTrue(ProviderConfigFile.loadProperties().isEmpty)
  }

  @Test
  fun `updateProviderConfig preserves other providers entries`() {
    ProviderConfigFile.updateProviderConfig("ollama", "http://localhost:11434", "k1")
    ProviderConfigFile.updateProviderConfig("lmstudio", "http://localhost:1234", "k2")
    val props = ProviderConfigFile.loadProperties()
    assertEquals("http://localhost:11434", props.getProperty("GRADUM_OLLAMA_BASE_URL"))
    assertEquals("http://localhost:1234", props.getProperty("GRADUM_LMSTUDIO_BASE_URL"))
  }
}
