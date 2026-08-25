/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ProviderCoordinatorUrlValidationTest.kt  2026-08-25 13:21:36 Changed by gwy
 */

package gradum.idea.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [isValidBaseUrl].
 *
 * The validator must reject garbage URLs such as
 * `http://192.168.1.5:1234/v1832483294239482394` — a scheme-only check is not
 * enough, since users can paste arbitrary junk after a valid scheme. At the
 * same time every legitimately used base URL (plain host, `/v1` OpenAI
 * prefix, real hostnames, IPv4 loopback/LAN) must still be accepted.
 */
class ProviderCoordinatorUrlValidationTest {

  @Test
  fun `accepts plain host and port`() {
    assertTrue(isValidBaseUrl(value = "http://192.168.1.5:1234"))
    assertTrue(isValidBaseUrl(value = "http://localhost:11434"))
    assertTrue(isValidBaseUrl(value = "https://localhost:1234"))
    assertTrue(isValidBaseUrl(value = "http://127.0.0.1:1234"))
  }

  @Test
  fun `accepts v1 openai prefix`() {
    assertTrue(isValidBaseUrl(value = "http://192.168.1.5:1234/v1"))
    assertTrue(isValidBaseUrl(value = "http://192.168.1.5:1234/v1/"))
    assertTrue(isValidBaseUrl(value = "http://localhost:1234/v1"))
  }

  @Test
  fun `accepts real hostnames`() {
    assertTrue(isValidBaseUrl(value = "http://ollama.internal:11434"))
    assertTrue(isValidBaseUrl(value = "https://lmstudio.example.com:1234"))
  }

  @Test
  fun `rejects junk path after scheme`() {
    assertFalse(isValidBaseUrl(value = "http://192.168.1.5:1234/v1832483294239482394"))
    assertFalse(isValidBaseUrl(value = "http://192.168.1.5:1234/whatever/models"))
    assertFalse(isValidBaseUrl(value = "http://192.168.1.5:1234/v1/extra"))
  }

  @Test
  fun `rejects missing or invalid scheme`() {
    assertFalse(isValidBaseUrl(value = ""))
    assertFalse(isValidBaseUrl(value = "192.168.1.5:1234"))
    assertFalse(isValidBaseUrl(value = "ftp://192.168.1.5:1234"))
    assertFalse(isValidBaseUrl(value = "://192.168.1.5:1234"))
  }

  @Test
  fun `rejects invalid host`() {
    assertFalse(isValidBaseUrl(value = "http://:1234"))
    assertFalse(isValidBaseUrl(value = "http://"))
    assertFalse(isValidBaseUrl(value = "http://has space:1234"))
    assertFalse(isValidBaseUrl(value = "http://192.168.999.1:1234"))
    assertFalse(isValidBaseUrl(value = "http://256.168.1.5:1234"))
  }

  @Test
  fun `rejects out of range port`() {
    assertFalse(isValidBaseUrl(value = "http://localhost:0"))
    assertFalse(isValidBaseUrl(value = "http://localhost:70000"))
    assertFalse(isValidBaseUrl(value = "http://localhost:99999"))
  }

  @Test
  fun `rejects url with query fragment or userinfo`() {
    assertFalse(isValidBaseUrl(value = "http://user:pw@192.168.1.5:1234"))
    assertFalse(isValidBaseUrl(value = "http://192.168.1.5:1234/v1?key=value"))
    assertFalse(isValidBaseUrl(value = "http://192.168.1.5:1234/#frag"))
  }

  @Test
  fun `accepts deep cloud base urls`() {
    assertTrue(isValidBaseUrl(value = "https://open.bigmodel.cn/api/paas/v4", kind = ProviderKind.ZHIPU))
    assertTrue(isValidBaseUrl(value = "https://api.deepseek.com/v1", kind = ProviderKind.DEEPSEEK))
    assertTrue(isValidBaseUrl(value = "https://api.minimaxi.com/v1", kind = ProviderKind.MINIMAX))
  }

  @Test
  fun `rejects deep local paths even when a kind is supplied`() {
    assertFalse(isValidBaseUrl(value = "http://localhost:1234/api/whatever", kind = ProviderKind.LM_STUDIO))
    assertFalse(isValidBaseUrl(value = "http://localhost:11434/some/deep/path", kind = ProviderKind.OLLAMA))
  }

  @Test
  fun `cloud kinds still reject malformed urls`() {
    assertFalse(isValidBaseUrl(value = "", kind = ProviderKind.ZHIPU))
    assertFalse(isValidBaseUrl(value = "ftp://api.deepseek.com", kind = ProviderKind.DEEPSEEK))
    assertFalse(isValidBaseUrl(value = "https://api.deepseek.com:99999", kind = ProviderKind.DEEPSEEK))
    assertFalse(isValidBaseUrl(value = "https://has space:1234/v1", kind = ProviderKind.MINIMAX))
  }
}
