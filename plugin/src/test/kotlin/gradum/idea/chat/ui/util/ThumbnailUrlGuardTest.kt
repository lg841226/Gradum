/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ThumbnailUrlGuardTest.kt  2026-08-31 19:21:55 Changed by gwy
 */
package gradum.idea.chat.ui.util

import gradum.idea.chat.ui.util.ThumbnailUrlGuard.Check
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function tests for [ThumbnailUrlGuard]. No sockets are opened;
 * the guard's job is to *decide* whether a URL is safe to ask the JDK
 * to resolve, and these tests pin that decision table down.
 *
 * The guard is the only thing standing between a malicious Tavily
 * result (or a malicious favicon.vip query) and the IDE process
 * talking to `127.0.0.1`, `169.254.169.254`, `10.0.0.0/8`, etc. —
 * treat coverage gaps here as security bugs.
 */
class ThumbnailUrlGuardTest {

  private fun assertSafe(url: String) {
    val check: Check = ThumbnailUrlGuard.check(url)
    assertTrue(
      "expected Safe for $url but got $check",
      check is Check.Safe
    )
  }

  private fun assertUnsafe(url: String, reasonContains: String? = null) {
    val check: Check = ThumbnailUrlGuard.check(url)
    assertTrue(
      "expected Unsafe for $url but got $check",
      check is Check.Unsafe
    )
    if (reasonContains != null) {
      val unsafe: Check.Unsafe = check as Check.Unsafe
      assertTrue(
        "expected reason to contain '$reasonContains' for $url, got '${unsafe.reason}'",
        unsafe.reason.contains(other = reasonContains, ignoreCase = true)
      )
    }
  }

  @Test
  fun `https url is safe`() {
    assertSafe("https://example.com/favicon.ico")
  }

  @Test
  fun `http url is rejected as cleartext`() {
    assertUnsafe(url = "http://example.com/favicon.ico", reasonContains = "https")
  }

  @Test
  fun `file scheme is rejected`() {
    assertUnsafe(url = "file:///etc/passwd", reasonContains = "scheme")
  }

  @Test
  fun `data scheme is rejected`() {
    assertUnsafe(url = "data:text/plain,hello", reasonContains = "scheme")
  }

  @Test
  fun `javascript scheme is rejected`() {
    assertUnsafe(url = "javascript:alert(1)", reasonContains = "scheme")
  }

  @Test
  fun `ftp scheme is rejected`() {
    assertUnsafe(url = "ftp://example.com/favicon.ico", reasonContains = "scheme")
  }

  @Test
  fun `uppercase HTTPS scheme is normalised and accepted`() {
    assertSafe("HTTPS://example.com/favicon.ico")
  }

  @Test
  fun `missing host is rejected`() {
    assertUnsafe(url = "https:///favicon.ico", reasonContains = "missing host")
  }

  @Test
  fun `garbage url is rejected`() {
    assertUnsafe(url = "not a url at all", reasonContains = "URI")
  }

  @Test
  fun `empty url is rejected`() {
    assertUnsafe(url = "", reasonContains = "URI")
  }

  @Test
  fun `loopback ipv4 is rejected`() {
    assertUnsafe(url = "https://127.0.0.1/favicon.ico", reasonContains = "private")
  }

  @Test
  fun `loopback ipv4 127-anything is rejected`() {
    assertUnsafe(url = "https://127.255.255.254/favicon.ico", reasonContains = "private")
  }

  @Test
  fun `rfc1918 10 dot is rejected`() {
    assertUnsafe(url = "https://10.0.0.1/favicon.ico", reasonContains = "private")
  }

  @Test
  fun `rfc1918 192-168 dot is rejected`() {
    assertUnsafe(url = "https://192.168.1.1/favicon.ico", reasonContains = "private")
  }

  @Test
  fun `rfc1918 172-16 to 172-31 is rejected`() {
    assertUnsafe(url = "https://172.16.0.1/favicon.ico", reasonContains = "private")
    assertUnsafe(url = "https://172.20.10.5/favicon.ico", reasonContains = "private")
    assertUnsafe(url = "https://172.31.255.254/favicon.ico", reasonContains = "private")
  }

  @Test
  fun `cgnat 100-64 is rejected`() {
    assertUnsafe(url = "https://100.64.0.1/favicon.ico", reasonContains = "private")
  }

  @Test
  fun `link-local 169-254 (cloud metadata) is rejected`() {
    assertUnsafe(url = "https://169.254.169.254/latest/meta-data/", reasonContains = "private")
  }

  @Test
  fun `multicast 224 dot is rejected`() {
    assertUnsafe(url = "https://224.0.0.1/favicon.ico", reasonContains = "private")
  }

  @Test
  fun `reserved 240 dot is rejected`() {
    assertUnsafe(url = "https://240.0.0.1/favicon.ico", reasonContains = "private")
  }

  @Test
  fun `test-net ranges are rejected`() {
    assertUnsafe(url = "https://198.51.100.1/favicon.ico", reasonContains = "private")
    assertUnsafe(url = "https://203.0.113.1/favicon.ico", reasonContains = "private")
  }

  @Test
  fun `ipv6 loopback is rejected`() {
    assertUnsafe(url = "https://[::1]/favicon.ico", reasonContains = "private")
  }

  @Test
  fun `ipv6 unspecified is rejected`() {
    assertUnsafe(url = "https://[::]/favicon.ico", reasonContains = "private")
  }

  @Test
  fun `ipv6 link-local is rejected`() {
    assertUnsafe(url = "https://[fe80::1]/favicon.ico", reasonContains = "private")
  }

  @Test
  fun `ipv6 unique local fc00 is rejected`() {
    assertUnsafe(url = "https://[fc00::1]/favicon.ico", reasonContains = "private")
  }

  @Test
  fun `ipv6 unique local fd00 is rejected`() {
    assertUnsafe(url = "https://[fd00::1]/favicon.ico", reasonContains = "private")
  }

  @Test
  fun `ipv4-mapped private ipv6 is rejected via recursion`() {
    assertUnsafe(url = "https://[::ffff:10.0.0.1]/favicon.ico", reasonContains = "private")
  }

  @Test
  fun `safe and unsafe are distinct Check instances`() {
    val safe: Check = ThumbnailUrlGuard.check(url = "https://example.com/")
    val unsafe: Check = ThumbnailUrlGuard.check(url = "http://example.com/")
    assertEquals(
      Check.Safe,
      safe
    )
    assertTrue(unsafe is Check.Unsafe)
  }
}
