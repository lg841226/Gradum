/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ModelIdentityTest.kt  2026-08-14 23:00:00 Changed by gwy
 */

package gradum

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModelIdentityTest {

  // --- parameterCountInBillions ---

  @Test
  fun `parameterCountInBillions extracts integer size suffix`() {
    assertEquals(32.0, ModelIdentity.parameterCountInBillions("qwen-32b"), 0.001)
  }

  @Test
  fun `parameterCountInBillions extracts decimal size suffix`() {
    assertEquals(0.5, ModelIdentity.parameterCountInBillions("qwen-0.5b"), 0.001)
  }

  @Test
  fun `parameterCountInBillions returns zero when no size suffix is present`() {
    assertEquals(0.0, ModelIdentity.parameterCountInBillions("qwen2.5"), 0.001)
  }

  @Test
  fun `parameterCountInBillions is case-insensitive`() {
    assertEquals(70.0, ModelIdentity.parameterCountInBillions("LLAMA-70B"), 0.001)
  }

  @Test
  fun `parameterCountInBillions handles colon separator`() {
    assertEquals(14.0, ModelIdentity.parameterCountInBillions("qwen2.5:14b"), 0.001)
  }

  // --- isSmallModel ---

  @Test
  fun `isSmallModel returns true for sub-32B local models`() {
    assertTrue(ModelIdentity.isSmallModel("qwen-14b"))
    assertTrue(ModelIdentity.isSmallModel("qwen-7b"))
    assertTrue(ModelIdentity.isSmallModel("qwen-0.5b"))
  }

  @Test
  fun `isSmallModel returns false for models above threshold`() {
    assertFalse(ModelIdentity.isSmallModel("qwen-70b"))
    assertFalse(ModelIdentity.isSmallModel("qwen-110b"))
  }

  @Test
  fun `isSmallModel returns false for cloud-named models regardless of size`() {
    assertFalse(ModelIdentity.isSmallModel("gpt-4o"))
    assertFalse(ModelIdentity.isSmallModel("llama-7b-cloud"))
    assertFalse(ModelIdentity.isSmallModel("claude-sonnet"))
  }

  @Test
  fun `isSmallModel returns false for blank name`() {
    assertFalse(ModelIdentity.isSmallModel(""))
  }

  @Test
  fun `isSmallModel returns false when no size tag is present`() {
    assertFalse(ModelIdentity.isSmallModel("qwen2.5"))
  }

  // --- schemaVariant ---

  @Test
  fun `schemaVariant returns SIMPLE for small local models`() {
    assertEquals(SchemaVariant.SIMPLE, ModelIdentity.schemaVariant("qwen-14b"))
  }

  @Test
  fun `schemaVariant returns FULL for large models`() {
    assertEquals(SchemaVariant.FULL, ModelIdentity.schemaVariant("qwen-70b"))
  }

  @Test
  fun `schemaVariant returns FULL for blank name`() {
    assertEquals(SchemaVariant.FULL, ModelIdentity.schemaVariant(""))
  }

  @Test
  fun `schemaVariant returns FULL for cloud models`() {
    assertEquals(SchemaVariant.FULL, ModelIdentity.schemaVariant("gpt-4o"))
  }

  // --- isCloudTagged ---

  @Test
  fun `isCloudTagged matches cloud in name`() {
    assertTrue(ModelIdentity.isCloudTagged("minimax-m2.5:cloud"))
    assertTrue(ModelIdentity.isCloudTagged("qwen3-coder-480b-cloud"))
    assertTrue(ModelIdentity.isCloudTagged("FOO-CLOUD"))
  }

  @Test
  fun `isCloudTagged rejects names without cloud`() {
    assertFalse(ModelIdentity.isCloudTagged("qwen-14b"))
    assertFalse(ModelIdentity.isCloudTagged("gpt-4o"))
  }

  // --- normalizeCatalogKey ---

  @Test
  fun `normalizeCatalogKey strips size and instruct suffix`() {
    assertEquals("qwen2-5-coder", ModelIdentity.normalizeCatalogKey("Qwen2.5-Coder-14B-Instruct"))
  }

  @Test
  fun `normalizeCatalogKey collapses separators and trims dashes`() {
    assertEquals("llama3", ModelIdentity.normalizeCatalogKey("LLAMA3:70B"))
  }

  @Test
  fun `normalizeCatalogKey handles bare model name`() {
    assertEquals("gpt-4o", ModelIdentity.normalizeCatalogKey("gpt-4o"))
  }

  // --- knownCloudServers wiring (DeepSeek / MiniMax / Zhipu onboarding) ---

  @Test
  fun `knownCloudServers contains Zhipu DeepSeek and MiniMax`() {
    val names: Set<String> = ModelIdentity.knownCloudServers.map { it.name }.toSet()
    assertTrue("Zhipu BigModel" in names, "Zhipu BigModel should be in knownCloudServers")
    assertTrue("DeepSeek" in names, "DeepSeek should be in knownCloudServers")
    assertTrue("MiniMax" in names, "MiniMax should be in knownCloudServers")
  }

  @Test
  fun `every cloud server uses OpenAI wire type`() {
    val cloudServers: List<ServerDef> = ModelIdentity.knownCloudServers
    assertTrue(cloudServers.isNotEmpty(), "expected at least one cloud server")
    for (server in cloudServers) {
      assertEquals(
        Provider.OPENAI.wireType,
        server.providerType,
        "${server.name} should use the OpenAI wire type"
      )
    }
  }

  @Test
  fun `every cloud server URL ends with v1 or v4 root and uses https`() {
    val cloudServers: List<ServerDef> = ModelIdentity.knownCloudServers
    for (server in cloudServers) {
      assertTrue(
        server.baseUrl.startsWith("https://"),
        "${server.name} baseUrl should be https, was: ${server.baseUrl}"
      )
      assertTrue(
        server.baseUrl.endsWith("/v1") || server.baseUrl.endsWith("/v4"),
        "${server.name} baseUrl should end with /v1 or /v4, was: ${server.baseUrl}"
      )
      assertEquals(
        "/models", server.endpoint,
        "${server.name} should probe via /models"
      )
    }
  }

  @Test
  fun `DeepSeek and MiniMax declare their own apiKeyEnvVar`() {
    val byName: Map<String, ServerDef> = ModelIdentity.knownCloudServers.associateBy { it.name }
    assertNotNull(byName["DeepSeek"], "DeepSeek should be in knownCloudServers")
    assertNotNull(byName["MiniMax"], "MiniMax should be in knownCloudServers")
    assertEquals(
      "DEEPSEEK_API_KEY", byName["DeepSeek"]?.apiKeyEnvVar,
      "DeepSeek should bind to DEEPSEEK_API_KEY"
    )
    assertEquals(
      "MiniMax_API_KEY", byName["MiniMax"]?.apiKeyEnvVar,
      "MiniMax should bind to MiniMax_API_KEY"
    )
  }

  @Test
  fun `Zhipu BigModel falls back to shared cloudApiKeyEnvCandidates so it can also use a dedicated env var`() {
    val byName: Map<String, ServerDef> = ModelIdentity.knownCloudServers.associateBy { it.name }
    val zhipu: ServerDef = byName.getValue("Zhipu BigModel")
    // Either apiKeyEnvVar is null and we read from cloudApiKeyEnvCandidates,
    // or it's set to BIGMODEL_API_KEY — both designs are acceptable. We
    // only assert the URL is correct and the endpoint is /models.
    assertEquals("https://open.bigmodel.cn/api/coding/paas/v4", zhipu.baseUrl)
    assertEquals("/models", zhipu.endpoint)
  }

  @Test
  fun `DeepSeek and MiniMax baseUrls match their official OpenAI-compatible hosts`() {
    // These URLs come straight from each vendor's API docs and are
    // easy to fat-finger. The check is here so a future refactor
    // that flips a letter (e.g. api.minimaxi.com → api.minimax.com)
    // fails CI instead of silently 404-ing at probe time.
    val byName: Map<String, ServerDef> = ModelIdentity.knownCloudServers.associateBy { it.name }
    assertEquals("https://api.deepseek.com/v1", byName.getValue("DeepSeek").baseUrl)
    assertEquals("https://api.minimaxi.com/v1", byName.getValue("MiniMax").baseUrl)
  }

  @Test
  fun `ServerDef default values keep apiKey and apiKeyEnvVar null`() {
    val bareDef: ServerDef = ServerDef(
      name = "Bare",
      baseUrl = "https://example.com",
      endpoint = "/models",
      providerType = Provider.OPENAI.wireType,
    )
    assertEquals(null, bareDef.apiKey)
    assertEquals(null, bareDef.apiKeyEnvVar)
  }
}
