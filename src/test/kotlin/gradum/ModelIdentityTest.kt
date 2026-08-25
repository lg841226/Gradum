/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ModelIdentityTest.kt  2026-08-16 16:52:39 Changed by gwy
 */

package gradum

import kotlin.test.*

class ModelIdentityTest {

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

  @Test
  fun `schemaVariant returns SIMPLE for small local models`() {
    assertEquals(
          SchemaVariant.SIMPLE,
          ModelIdentity.schemaVariant("qwen-14b")
    )
  }

  @Test
  fun `schemaVariant returns FULL for large models`() {
    assertEquals(
          SchemaVariant.FULL,
          ModelIdentity.schemaVariant("qwen-70b")
    )
  }

  @Test
  fun `schemaVariant returns FULL for blank name`() {
    assertEquals(
          SchemaVariant.FULL,
          ModelIdentity.schemaVariant("")
    )
  }

  @Test
  fun `schemaVariant returns FULL for cloud models`() {
    assertEquals(
          SchemaVariant.FULL,
          ModelIdentity.schemaVariant("gpt-4o")
    )
  }

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
    for ((name, _, _, providerType) in cloudServers) {
      assertEquals(
        Provider.OPENAI.wireType,
        providerType,
        "$name should use the OpenAI wire type"
      )
    }
  }

  @Test
  fun `every cloud server URL ends with v1 or v4 root and uses https`() {
    val cloudServers: List<ServerDef> = ModelIdentity.knownCloudServers
    for ((name, baseUrl, endpoint) in cloudServers) {
      assertTrue(
        baseUrl.startsWith("https://"),
        "$name baseUrl should be https, was: $baseUrl"
      )
      assertTrue(
        baseUrl.endsWith("/v1") || baseUrl.endsWith("/v4"),
        "$name baseUrl should end with /v1 or /v4, was: $baseUrl"
      )
      assertEquals(
        "/models", endpoint,
        "$name should probe via /models"
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
    assertEquals(
          "https://open.bigmodel.cn/api/coding/paas/v4",
          zhipu.baseUrl
    )
    assertEquals(
          "/models",
          zhipu.endpoint
    )
  }

  @Test
  fun `DeepSeek and MiniMax baseUrls match their official OpenAI-compatible hosts`() {
    val byName: Map<String, ServerDef> = ModelIdentity.knownCloudServers.associateBy { it.name }
    assertEquals(
          "https://api.deepseek.com/v1",
          byName.getValue("DeepSeek").baseUrl
    )
    assertEquals(
          "https://api.minimaxi.com/v1",
          byName.getValue("MiniMax").baseUrl
    )
  }

  @Test
  fun `ServerDef default values keep apiKey and apiKeyEnvVar null`() {
    val bareDef = ServerDef(
      name = "Bare",
      endpoint = "/models",
      baseUrl = "https://example.com",
      providerType = Provider.OPENAI.wireType
    )
    assertEquals(
          null,
          bareDef.apiKey
    )
    assertEquals(
          null,
          bareDef.apiKeyEnvVar
    )
  }
}
