/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ThumbnailUrlGuardTest.kt  2026-08-14 12:49:29 Changed by gwy
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
        unsafe.reason.contains(reasonContains, ignoreCase = true)
      )
    }
  }

  @Test
  fun `https url is safe`() {
    assertSafe("https://example.com/favicon.ico")
  }

  @Test
  fun `http url is rejected as cleartext`() {
    // Cleartext would let the user's Referer / cookies leak if a future
    // caller ever attaches an auth header. Cheap to block.
    assertUnsafe("http://example.com/favicon.ico", reasonContains = "https")
  }

  @Test
  fun `file scheme is rejected`() {
    // A malicious Tavily result could publish `favicon: file:///etc/passwd`.
    assertUnsafe("file:///etc/passwd", reasonContains = "scheme")
  }

  @Test
  fun `data scheme is rejected`() {
    assertUnsafe("data:text/plain,hello", reasonContains = "scheme")
  }

  @Test
  fun `javascript scheme is rejected`() {
    assertUnsafe("javascript:alert(1)", reasonContains = "scheme")
  }

  @Test
  fun `ftp scheme is rejected`() {
    assertUnsafe("ftp://example.com/favicon.ico", reasonContains = "scheme")
  }

  @Test
  fun `uppercase HTTPS scheme is normalised and accepted`() {
    // The guard lowercases the scheme before comparing so a hostile
    // `HTTPS://` (legal per RFC 3986) doesn't sneak past.
    assertSafe("HTTPS://example.com/favicon.ico")
  }

  @Test
  fun `missing host is rejected`() {
    assertUnsafe("https:///favicon.ico", reasonContains = "missing host")
  }

  @Test
  fun `garbage url is rejected`() {
    assertUnsafe("not a url at all", reasonContains = "URI")
  }

  @Test
  fun `empty url is rejected`() {
    assertUnsafe("", reasonContains = "URI")
  }

  @Test
  fun `loopback ipv4 is rejected`() {
    assertUnsafe("https://127.0.0.1/favicon.ico", reasonContains = "private")
  }

  @Test
  fun `loopback ipv4 127-anything is rejected`() {
    assertUnsafe("https://127.255.255.254/favicon.ico", reasonContains = "private")
  }

  @Test
  fun `rfc1918 10 dot is rejected`() {
    assertUnsafe("https://10.0.0.1/favicon.ico", reasonContains = "private")
  }

  @Test
  fun `rfc1918 192-168 dot is rejected`() {
    assertUnsafe("https://192.168.1.1/favicon.ico", reasonContains = "private")
  }

  @Test
  fun `rfc1918 172-16 to 172-31 is rejected`() {
    assertUnsafe("https://172.16.0.1/favicon.ico", reasonContains = "private")
    assertUnsafe("https://172.20.10.5/favicon.ico", reasonContains = "private")
    assertUnsafe("https://172.31.255.254/favicon.ico", reasonContains = "private")
  }

  @Test
  fun `cgnat 100-64 is rejected`() {
    assertUnsafe("https://100.64.0.1/favicon.ico", reasonContains = "private")
  }

  @Test
  fun `link-local 169-254 (cloud metadata) is rejected`() {
    // AWS / GCP / Azure all expose instance metadata at 169.254.169.254.
    // A Tavily result that points its favicon here would read the
    // user's cloud credentials if it ever rendered.
    assertUnsafe("https://169.254.169.254/latest/meta-data/", reasonContains = "private")
  }

  @Test
  fun `multicast 224 dot is rejected`() {
    assertUnsafe("https://224.0.0.1/favicon.ico", reasonContains = "private")
  }

  @Test
  fun `reserved 240 dot is rejected`() {
    assertUnsafe("https://240.0.0.1/favicon.ico", reasonContains = "private")
  }

  @Test
  fun `test-net ranges are rejected`() {
    // 198.51.100.0/24 and 203.0.113.0/24 are documentation-only and
    // sometimes misconfigured; an honest site would never be there.
    assertUnsafe("https://198.51.100.1/favicon.ico", reasonContains = "private")
    assertUnsafe("https://203.0.113.1/favicon.ico", reasonContains = "private")
  }

  @Test
  fun `ipv6 loopback is rejected`() {
    assertUnsafe("https://[::1]/favicon.ico", reasonContains = "private")
  }

  @Test
  fun `ipv6 unspecified is rejected`() {
    assertUnsafe("https://[::]/favicon.ico", reasonContains = "private")
  }

  @Test
  fun `ipv6 link-local is rejected`() {
    assertUnsafe("https://[fe80::1]/favicon.ico", reasonContains = "private")
  }

  @Test
  fun `ipv6 unique local fc00 is rejected`() {
    assertUnsafe("https://[fc00::1]/favicon.ico", reasonContains = "private")
  }

  @Test
  fun `ipv6 unique local fd00 is rejected`() {
    assertUnsafe("https://[fd00::1]/favicon.ico", reasonContains = "private")
  }

  @Test
  fun `ipv4-mapped private ipv6 is rejected via recursion`() {
    // ::ffff:10.0.0.1 == 10.0.0.1 in disguise. Must not slip past.
    assertUnsafe("https://[::ffff:10.0.0.1]/favicon.ico", reasonContains = "private")
  }

  @Test
  fun `safe and unsafe are distinct Check instances`() {
    // Defence against a future refactor that collapses the sealed
    // class into a single data class with a nullable reason.
    val safe: Check = ThumbnailUrlGuard.check("https://example.com/")
    val unsafe: Check = ThumbnailUrlGuard.check("http://example.com/")
    assertEquals(Check.Safe, safe)
    assertTrue(unsafe is Check.Unsafe)
  }
}
