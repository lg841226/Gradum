/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ConfigOverridesTest.kt  2026-08-10 23:19:40 Changed by gwy
 */

package gradum.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConfigOverridesTest {

  @Test
  fun `null config map yields an empty overrides instance`() {
    val overrides = ConfigOverrides.fromRequestMap(rawConfig = null)

    assertNull(overrides.baseUrl)
    assertNull(overrides.provider)
    assertNull(overrides.think)
    assertNull(overrides.temperature)
    assertNull(overrides.topP)
    assertNull(overrides.numCtx)
    assertNull(overrides.numPredict)
    assertNull(overrides.timeout)
  }

  @Test
  fun `empty config map yields an empty overrides instance`() {
    val overrides = ConfigOverrides.fromRequestMap(rawConfig = emptyMap())

    assertEquals(ConfigOverrides(), overrides)
  }

  @Test
  fun `full config map parses every typed field`() {
    val overrides = ConfigOverrides.fromRequestMap(
      mapOf(
        "baseUrl" to "http://127.0.0.1:11434",
        "provider" to "openai",
        "think" to "true",
        "temperature" to "0.3",
        "topP" to "0.95",
        "numCtx" to "16384",
        "numPredict" to "4096",
        "timeout" to "120",
      )
    )

    assertEquals("http://127.0.0.1:11434", overrides.baseUrl)
    assertEquals("openai", overrides.provider)
    assertEquals(true, overrides.think)
    assertEquals(0.3, overrides.temperature)
    assertEquals(0.95, overrides.topP)
    assertEquals(16384, overrides.numCtx)
    assertEquals(4096, overrides.numPredict)
    assertEquals(120, overrides.timeout)
  }

  @Test
  fun `non numeric strings are dropped to null instead of crashing`() {
    val overrides = ConfigOverrides.fromRequestMap(
      mapOf(
        "temperature" to "hot",
        "topP" to "none",
        "numCtx" to "many",
        "numPredict" to "lots",
        "timeout" to "soon",
      )
    )

    assertNull(overrides.temperature)
    assertNull(overrides.topP)
    assertNull(overrides.numCtx)
    assertNull(overrides.numPredict)
    assertNull(overrides.timeout)
  }

  @Test
  fun `partial config map fills only the supplied fields`() {
    val overrides = ConfigOverrides.fromRequestMap(
      mapOf("baseUrl" to "http://ollama", "think" to "false")
    )

    assertEquals("http://ollama", overrides.baseUrl)
    assertEquals(false, overrides.think)
    assertNull(overrides.temperature)
    assertNull(overrides.numCtx)
  }

  @Test
  fun `non boolean strings parse to false instead of crashing`() {
    val overrides = ConfigOverrides.fromRequestMap(
      mapOf("think" to "certainly")
    )

    assertEquals(overrides.think, false)
  }
}
