/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * FaviconHostCacheTest.kt  2026-08-25 13:21:36 Changed by gwy
 */
package gradum.idea.chat.ui.util

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for [FaviconHostCache].
 *
 * The cache is the host-level short-circuit that lets
 * [SearchedRenderer] skip dead chain members on re-render. The
 * invariants under test:
 *  1. `recordSuccess` populates `getWinningUrl`; `isFailed` is false.
 *  2. `recordFailure` populates `isFailed`; `getWinningUrl` is null.
 *  3. `recordSuccess` over a previously-failed host flips it back to
 *     a positive entry — the negative entry is cleared, not stuck.
 *  4. `recordFailure` over a previously-successful host is a no-op —
 *     a positive entry is never silently downgraded.
 *  5. The cache is bounded by `maxEntries` and evicts the eldest
 *     insertion on overflow.
 */
class FaviconHostCacheTest {

  @Test
  fun `recordSuccess stores the winning URL and clears any prior failure`() {
    val cache = FaviconHostCache()
    cache.recordFailure(host = "github.com")
    assertTrue(
      "negative entry should be present",
      cache.isFailed(host = "github.com")
    )

    cache.recordSuccess(host = "github.com", url = "https://github.githubassets.com/favicon.svg")

    assertEquals(
      "https://github.githubassets.com/favicon.svg",
      cache.getWinningUrl(host = "github.com"),
    )
    assertFalse(
      "recording a success must clear the negative entry",
      cache.isFailed(host = "github.com"),
    )
  }

  @Test
  fun `recordSuccess overwrites a previous winning URL for the same host`() {
    val cache = FaviconHostCache()
    cache.recordSuccess(host = "github.com", url = "https://old.example/favicon.ico")
    cache.recordSuccess(host = "github.com", url = "https://new.example/favicon.svg")

    assertEquals(
      "newer success must win over older success for the same host",
      "https://new.example/favicon.svg",
      cache.getWinningUrl(host = "github.com"),
    )
  }

  @Test
  fun `recordFailure stores the negative entry when no success exists`() {
    val cache = FaviconHostCache()
    cache.recordFailure(host = "example.com")

    assertTrue(cache.isFailed(host = "example.com"))
    assertNull(
      "no winning URL should exist for a never-successful host",
      cache.getWinningUrl(host = "example.com")
    )
  }

  @Test
  fun `recordFailure is a no-op when a success already exists for the host`() {
    val cache = FaviconHostCache()
    cache.recordSuccess(host = "github.com", url = "https://ok.example/favicon.ico")
    cache.recordFailure(host = "github.com")

    assertEquals(
      "positive entry must not be silently downgraded by a later failure",
      "https://ok.example/favicon.ico",
      cache.getWinningUrl(host = "github.com"),
    )
    assertFalse(
      "negative entry must not overwrite a positive one",
      cache.isFailed(host = "github.com")
    )
  }

  @Test
  fun `fresh cache is empty for any host`() {
    val cache = FaviconHostCache()

    assertNull(cache.getWinningUrl(host = "anywhere.test"))
    assertFalse(cache.isFailed(host = "anywhere.test"))
  }

  @Test
  fun `cache evicts the eldest insertion when maxEntries is exceeded`() {
    val cache = FaviconHostCache(maxEntries = 4)
    cache.recordSuccess(host = "a.test", url = "https://a/favicon.ico")
    cache.recordSuccess(host = "b.test", url = "https://b/favicon.ico")
    cache.recordSuccess(host = "c.test", url = "https://c/favicon.ico")
    cache.recordSuccess(host = "d.test", url = "https://d/favicon.ico")
    cache.recordSuccess(host = "e.test", url = "https://e/favicon.ico")

    assertNull(
      "eldest entry must have been evicted",
      cache.getWinningUrl(host = "a.test")
    )
    assertEquals(
      "https://b/favicon.ico",
      cache.getWinningUrl(host = "b.test")
    )
    assertEquals(
      "https://e/favicon.ico",
      cache.getWinningUrl(host = "e.test")
    )
  }

  @Test
  fun `eviction drops from whichever side is the larger contributor`() {
    val cache = FaviconHostCache(maxEntries = 3)
    cache.recordFailure(host = "fail1.test")
    cache.recordFailure(host = "fail2.test")
    cache.recordFailure(host = "fail3.test")
    cache.recordSuccess(host = "ok.test", url = "https://ok/favicon.ico")

    assertFalse(
      "eldest failure should have been evicted",
      cache.isFailed(host = "fail1.test")
    )
    assertTrue(
      "younger failures should still be present",
      cache.isFailed(host = "fail2.test")
    )
    assertTrue(cache.isFailed(host = "fail3.test"))
    assertEquals(
      "https://ok/favicon.ico",
      cache.getWinningUrl(host = "ok.test")
    )
  }

  @Test
  fun `clear drops both positive and negative entries`() {
    val cache = FaviconHostCache()
    cache.recordSuccess(
      host = "github.com",
      url = "https://github/favicon.ico"
    )
    cache.recordFailure(host = "broken.test")

    cache.clear()

    assertNull(cache.getWinningUrl(host = "github.com"))
    assertFalse(cache.isFailed(host = "broken.test"))
  }
}
