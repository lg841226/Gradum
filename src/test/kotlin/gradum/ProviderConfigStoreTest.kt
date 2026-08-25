/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ProviderConfigStoreTest.kt  2026-08-25 21:48:23 Changed by gwy
 */
package gradum

import java.io.File
import java.nio.file.Files
import kotlin.test.*

class ProviderConfigStoreTest {

  private lateinit var tempDir: File

  @BeforeTest
  fun setUp() {
    tempDir = Files.createTempDirectory("gradum-provider-env").toFile()
    System.setProperty("gradum.provider.configDir", tempDir.absolutePath)
  }

  @AfterTest
  fun tearDown() {
    System.clearProperty("gradum.provider.configDir")
    tempDir.deleteRecursively()
  }

  @Test
  fun `load returns empty props when file is missing`() {
    assertTrue(ProviderConfigStore.load().isEmpty)
  }

  @Test
  fun `load parses hand-written env file`() {
    File(tempDir, "provider.env").writeText(
      """
        GRADUM_OLLAMA_BASE_URL=http://192.168.1.50:11434
        GRADUM_DEEPSEEK_API_KEY=sk-123
        # comment line is fine
      """.trimIndent()
    )

    val props = ProviderConfigStore.load()

    assertEquals(
      "http://192.168.1.50:11434",
      props.getProperty("GRADUM_OLLAMA_BASE_URL")
    )
    assertEquals(
      "sk-123",
      props.getProperty("GRADUM_DEEPSEEK_API_KEY")
    )
  }

  @Test
  fun `base url and api key keys are derived from configKey`() {
    assertEquals(
      "GRADUM_OLLAMA_BASE_URL",
      ProviderConfigStore.baseUrlKey(configKey = "ollama")
    )
    assertEquals(
      "GRADUM_LMSTUDIO_API_KEY",
      ProviderConfigStore.apiKeyKey(configKey = "lmstudio")
    )
    assertEquals(
      null,
      ProviderConfigStore.baseUrlKey(configKey = null)
    )
  }

  @Test
  fun `allow remote flag defaults to false and reads from env file`() {
    assertEquals(
      "GRADUM_LMSTUDIO_ALLOW_REMOTE",
      ProviderConfigStore.allowRemoteKey(configKey = "lmstudio")
    )
    assertEquals(
      false,
      ProviderConfigStore.isAllowRemote(configKey = "lmstudio")
    )

    File(tempDir, "provider.env").writeText("GRADUM_LMSTUDIO_ALLOW_REMOTE=true")
    assertEquals(
      true,
      ProviderConfigStore.isAllowRemote(configKey = "lmstudio")
    )
  }

  @Test
  fun `configKeyFor maps probe kinds`() {
    assertEquals(
      "ollama",
      ProviderConfigStore.configKeyFor(kind = "ollama")
    )
    assertEquals(
      "lmstudio",
      ProviderConfigStore.configKeyFor(kind = "lmstudio")
    )
    assertEquals(
      null,
      ProviderConfigStore.configKeyFor(kind = "unknown")
    )
  }

  @Test
  fun `isLocalHostUrl accepts loopback only`() {
    assertTrue(ModelIdentity.isLocalHostUrl("http://localhost:1234"))
    assertTrue(ModelIdentity.isLocalHostUrl("http://127.0.0.1:11434"))
    assertTrue(ModelIdentity.isLocalHostUrl("http://[::1]:1234"))
    assertTrue(!ModelIdentity.isLocalHostUrl("http://192.168.1.50:1234"))
    assertTrue(!ModelIdentity.isLocalHostUrl("https://api.deepseek.com"))
    assertTrue(!ModelIdentity.isLocalHostUrl("not-a-url"))
  }

  @Test
  fun `parseModelNamesFromBody extracts openai data ids`() {
    val body = """{"data":[{"id":"gpt-4o"},{"id":"deepseek-chat"},{"id":""}]}"""
    val names = ModelIdentity.parseModelNamesFromBody(providerType = Provider.OPENAI.wireType, body)
    assertEquals(
      listOf("gpt-4o", "deepseek-chat"),
      names
    )
  }

  @Test
  fun `parseModelNamesFromBody extracts ollama models`() {
    val body = """{"models":[{"name":"llama3"},{"name":"qwen2.5"}]}"""
    val names = ModelIdentity.parseModelNamesFromBody(providerType = Provider.OLLAMA.wireType, body)
    assertEquals(
      listOf("llama3", "qwen2.5"),
      names
    )
  }

  @Test
  fun `parseModelNamesFromBody rejects non model-list bodies`() {
    assertEquals(
      emptyList(),
      ModelIdentity.parseModelNamesFromBody(
        providerType = Provider.OPENAI.wireType, body = "not json"
      )
    )
    assertEquals(
      emptyList(),
      ModelIdentity.parseModelNamesFromBody(
        providerType = Provider.OPENAI.wireType, body = "{}"
      )
    )
    assertEquals(
      emptyList(),
      ModelIdentity.parseModelNamesFromBody(
        providerType = Provider.OPENAI.wireType, body = ""
      )
    )
    assertEquals(
      emptyList(),
      ModelIdentity.parseModelNamesFromBody(
        providerType = Provider.OPENAI.wireType,
        body = """{"data":[]}"""
      )
    )
    assertEquals(
      emptyList(),
      ModelIdentity.parseModelNamesFromBody(
        providerType = Provider.OPENAI.wireType,
        body = """{"error":"boom"}"""
      )
    )
  }

  @Test
  fun `resolveModelsEndpoint does not double the v1 segment`() {
    assertEquals(
      "http://192.168.1.5:1234/v1/models",
      ModelIdentity.resolveModelsEndpoint(baseUrl = "http://192.168.1.5:1234/v1", endpoint = "/v1/models")
    )
    assertEquals(
      "http://192.168.1.5:1234/v1/models",
      ModelIdentity.resolveModelsEndpoint(baseUrl = "http://192.168.1.5:1234", endpoint = "/v1/models")
    )
    assertEquals(
      "http://192.168.1.5:1234/v1/v1/models",
      ModelIdentity.resolveModelsEndpoint(baseUrl = "http://192.168.1.5:1234/v1/v1", endpoint = "/v1/models")
    )
  }

  @Test
  fun `probeProvider rejects malformed url before dialing`() {
    val result = ModelIdentity.probeProvider(
      kind = "lmstudio",
      baseUrl = "http://192.168.1.50:1234/v1832483294239482394",
      apiKey = null,
    )
    assertEquals(
      "failed",
      result.status
    )
    assertTrue(
      result.error!!.contains(other = "Malformed provider URL")
    )
  }

  @Test
  fun `probeProvider rejects malformed localhost url`() {
    val result = ModelIdentity.probeProvider(
      kind = "ollama",
      baseUrl = "http://localhost:11434/whatever/models",
      apiKey = null,
    )
    assertEquals(
      "failed",
      result.status
    )
    assertTrue(result.error!!.contains(other = "Malformed provider URL"))
  }

  @Test
  fun `probeProvider refuses remote lmstudio url when flag off`() {
    val result = ModelIdentity.probeProvider(
      kind = "lmstudio",
      baseUrl = "http://192.168.1.50:1234",
      apiKey = null,
    )
    assertEquals(
      "failed",
      result.status
    )
    assertTrue(result.error!!.contains(other = "Remote provider connections are disabled"))
  }

  @Test
  fun `probeProvider allows remote lmstudio url when flag on`() {
    File(tempDir, "provider.env").writeText("GRADUM_LMSTUDIO_ALLOW_REMOTE=true")
    val result = ModelIdentity.probeProvider(
      apiKey = null,
      kind = "lmstudio",
      baseUrl = "http://192.168.1.50:1234"
    )
    assertTrue(
      result.status != "failed"
        || !result.error!!.contains(other = "Remote provider connections are disabled")
    )
  }

  @Test
  fun `probeProvider always allows localhost lmstudio url`() {
    val result = ModelIdentity.probeProvider(
      kind = "lmstudio",
      baseUrl = "http://localhost:1234",
      apiKey = null,
    )
    assertTrue(
      result.status != "failed" ||
        !result.error!!.contains(other = "Remote provider connections are disabled")
    )
  }

  @Test
  fun `empty config probes no providers (no localhost fallback)`() {
    val entries = ModelIdentity.discoverModels()
    assertTrue(
      entries.isEmpty(),
      "expected no model entries when config is empty"
    )
  }

  @Test
  fun `fingerprint is stable for missing file and changes on edit`() {
    val missingFingerprint: String = ProviderConfigStore.fingerprint()
    assertEquals(
      "missing",
      missingFingerprint
    )

    val configFile = File(tempDir, "provider.env")
    configFile.writeText("GRADUM_OLLAMA_BASE_URL=http://localhost:11434")
    val first: String = ProviderConfigStore.fingerprint()
    assertTrue(first != "missing")

    configFile.writeText("GRADUM_OLLAMA_BASE_URL=http://192.168.1.50:11434")
    val second: String = ProviderConfigStore.fingerprint()
    assertTrue(
      second != first,
      "fingerprint must change when the file content changes"
    )
  }
}
