/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 */

package gradum.idea.chat.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ModelInfo.sameAs] — the stable identity key used by
 * model pinning, selection preservation, and unpinned filtering.
 */
class ModelInfoTest {

  @Test
  fun `sameAs matches same name on same server`() {
    val a = ModelInfo(name = "qwen2.5:7b", serverName = "ollama")
    val b = ModelInfo(name = "qwen2.5:7b", serverName = "ollama")
    assertTrue(a.sameAs(b))
    assertTrue(b.sameAs(a))
  }

  @Test
  fun `sameAs rejects same name on a different server`() {
    val a = ModelInfo(name = "qwen2.5:7b", serverName = "ollama")
    val b = ModelInfo(name = "qwen2.5:7b", serverName = "lm-studio")
    assertFalse(a.sameAs(b))
  }

  @Test
  fun `sameAs rejects different names on the same server`() {
    val a = ModelInfo(name = "qwen2.5:7b", serverName = "ollama")
    val b = ModelInfo(name = "llama3:8b", serverName = "ollama")
    assertFalse(a.sameAs(b))
  }

  @Test
  fun `sameAs mirrors equals for identical data class values`() {
    val a = ModelInfo(name = "llama3.1:8b", serverName = "ollama", provider = "meta")
    assertEquals(a, a)
    assertTrue(a.sameAs(ModelInfo(name = "llama3.1:8b", serverName = "ollama", provider = "meta")))
  }
}