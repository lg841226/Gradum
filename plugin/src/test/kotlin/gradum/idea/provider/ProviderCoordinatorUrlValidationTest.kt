/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 */

package gradum.idea.provider

import org.junit.Assert.*
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
    assertTrue(isValidBaseUrl("http://192.168.1.5:1234"))
    assertTrue(isValidBaseUrl("http://localhost:11434"))
    assertTrue(isValidBaseUrl("https://localhost:1234"))
    assertTrue(isValidBaseUrl("http://127.0.0.1:1234"))
  }

  @Test
  fun `accepts v1 openai prefix`() {
    assertTrue(isValidBaseUrl("http://192.168.1.5:1234/v1"))
    assertTrue(isValidBaseUrl("http://192.168.1.5:1234/v1/"))
    assertTrue(isValidBaseUrl("http://localhost:1234/v1"))
  }

  @Test
  fun `accepts real hostnames`() {
    assertTrue(isValidBaseUrl("http://ollama.internal:11434"))
    assertTrue(isValidBaseUrl("https://lmstudio.example.com:1234"))
  }

  @Test
  fun `rejects junk path after scheme`() {
    assertFalse(isValidBaseUrl("http://192.168.1.5:1234/v1832483294239482394"))
    assertFalse(isValidBaseUrl("http://192.168.1.5:1234/whatever/models"))
    assertFalse(isValidBaseUrl("http://192.168.1.5:1234/v1/extra"))
  }

  @Test
  fun `rejects missing or invalid scheme`() {
    assertFalse(isValidBaseUrl(""))
    assertFalse(isValidBaseUrl("192.168.1.5:1234"))
    assertFalse(isValidBaseUrl("ftp://192.168.1.5:1234"))
    assertFalse(isValidBaseUrl("://192.168.1.5:1234"))
  }

  @Test
  fun `rejects invalid host`() {
    assertFalse(isValidBaseUrl("http://:1234"))
    assertFalse(isValidBaseUrl("http://"))
    assertFalse(isValidBaseUrl("http://has space:1234"))
    assertFalse(isValidBaseUrl("http://192.168.999.1:1234"))
    assertFalse(isValidBaseUrl("http://256.168.1.5:1234"))
  }

  @Test
  fun `rejects out of range port`() {
    assertFalse(isValidBaseUrl("http://localhost:0"))
    assertFalse(isValidBaseUrl("http://localhost:70000"))
    assertFalse(isValidBaseUrl("http://localhost:99999"))
  }

  @Test
  fun `rejects url with query fragment or userinfo`() {
    assertFalse(isValidBaseUrl("http://user:pw@192.168.1.5:1234"))
    assertFalse(isValidBaseUrl("http://192.168.1.5:1234/v1?key=value"))
    assertFalse(isValidBaseUrl("http://192.168.1.5:1234/#frag"))
  }
}